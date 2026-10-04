package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import app.thoughts.mobile.core.connection.ServerProfile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** A user-started, durable transfer queue; never starts SSH from a boot receiver. */
public final class TransferService extends Service {

  public static final String ACTION_CHANGED = InboxStore.ACTION_CHANGED;
  private static final String PAUSE_ALL = "app.thoughts.mobile.inbox.PAUSE_ALL";
  private static final int NOTIFICATION = 4202;
  private static volatile TransferService active;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile InboxTransfer connection;
  private volatile String currentId;
  private volatile boolean stopped;
  private volatile int latestStart;
  private PowerManager.WakeLock cpu;
  private WifiManager.WifiLock wifi;
  private long lastProgress;

  public static void start(Context context) {
    try {
      context.startForegroundService(
        new Intent(context, TransferService.class)
      );
    } catch (RuntimeException e) {
      throw new IllegalStateException(
        "无法在后台启动传输，请回到收件箱重试",
        e
      );
    }
  }

  public static void pause(Context context, String id) {
    try (InboxStore store = new InboxStore(context)) {
      InboxStore.Task t = store.task(id);
      if (
        t == null || !("running".equals(t.state) || "queued".equals(t.state))
      ) return;
      store.setState(id, "paused", "");
      TransferService service = active;
      if (service != null) service.interrupt(id);
    } catch (Exception e) {
      throw new IllegalStateException("暂停传输失败", e);
    }
  }

  public static void retry(Context context, String id) {
    try (InboxStore store = new InboxStore(context)) {
      InboxStore.Task t = store.task(id);
      if (
        t == null || !("failed".equals(t.state) || "paused".equals(t.state))
      ) return;
      if (
        !InboxStore.sameIdentity(t.profile, ServerProfile.load(context))
      ) throw new IOException(
        "此任务属于原来的服务器，请恢复对应连接配置后继续"
      );
      store.setState(id, "queued", "");
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
    start(context);
  }

  public static void cancel(Context context, String id) {
    try (InboxStore store = new InboxStore(context)) {
      InboxStore.Task t = store.task(id);
      if (
        t == null || "done".equals(t.state) || "canceled".equals(t.state)
      ) return;
      store.setState(id, "canceled", "");
      TransferService service = active;
      if (service != null) service.interrupt(id);
      // Keep a cleanup marker so cancellation while offline is completed on the next queue start.
      JSONObject doc = t.document;
      doc.put("_cleanup", true);
      store.updateDocument(id, doc);
    } catch (Exception e) {
      throw new IllegalStateException("取消传输失败", e);
    }
    start(context);
  }

  private void interrupt(String id) {
    if (id.equals(currentId)) {
      InboxTransfer value = connection;
      if (value != null) value.close();
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    active = this;
    try (InboxStore store = new InboxStore(this)) {
      store.recover();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStart = startId;
    try {
      Notification notice = notice(0, 0);
      if (Build.VERSION.SDK_INT >= 29) startForeground(
        NOTIFICATION,
        notice,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      );
      else startForeground(NOTIFICATION, notice);
      acquire();
      if (intent != null && PAUSE_ALL.equals(intent.getAction())) {
        pauseAll("已暂停");
        finish(startId);
        return START_NOT_STICKY;
      }
      worker.execute(() -> drain(startId));
    } catch (RuntimeException e) {
      pauseAll("传输暂时无法运行，请重试");
      finish(startId);
    }
    return START_NOT_STICKY;
  }

  private Notification notice(long done, long total) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
      new NotificationChannel(
        "inbox",
        "收件箱传输",
        NotificationManager.IMPORTANCE_LOW
      )
    );
    Intent open = getPackageManager().getLaunchIntentForPackage(
      getPackageName()
    );
    if (open == null) open = new Intent();
    open
      .putExtra("open_feature", "inbox")
      .addFlags(
        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
      );
    PendingIntent content = PendingIntent.getActivity(
      this,
      42,
      open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    PendingIntent pause = PendingIntent.getService(
      this,
      43,
      new Intent(this, TransferService.class).setAction(PAUSE_ALL),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    Notification.Builder b = new Notification.Builder(this, "inbox")
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("收件箱正在传输")
      .setContentText("点击查看进度")
      .setContentIntent(content)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setVisibility(Notification.VISIBILITY_SECRET)
      .addAction(new Notification.Action.Builder(null, "暂停", pause).build());
    if (total > 0) b.setProgress(
      100,
      (int) Math.min(100, (done * 100.0) / total),
      false
    );
    else b.setProgress(0, 0, true);
    return b.build();
  }

  private void acquire() {
    if (cpu != null) return;
    cpu = getSystemService(PowerManager.class).newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "myserver:inbox"
    );
    cpu.setReferenceCounted(false);
    cpu.acquire();
    WifiManager manager =
      (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    if (manager != null) {
      wifi = manager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "myserver:inbox"
      );
      wifi.setReferenceCounted(false);
      wifi.acquire();
    }
  }

  private void release() {
    if (cpu != null && cpu.isHeld()) cpu.release();
    if (wifi != null && wifi.isHeld()) wifi.release();
    cpu = null;
    wifi = null;
  }

  private void drain(int startId) {
    try (InboxStore store = new InboxStore(this)) {
      List<InboxStore.Task> tasks = store.tasks();
      Collections.reverse(tasks);
      for (InboxStore.Task task : tasks) {
        if (stopped) return;
        if (
          "canceled".equals(task.state) && task.document.optBoolean("_cleanup")
        ) {
          cleanup(store, task);
          continue;
        }
        if (!"queued".equals(task.state)) continue;
        if (!InboxStore.sameIdentity(task.profile, ServerProfile.load(this))) {
          store.setState(
            task.id,
            "paused",
            "服务器配置已改变；恢复原配置后可继续"
          );
          continue;
        }
        if (!store.claim(task.id)) continue;
        currentId = task.id;
        try {
          check(store, task);
          connection = new InboxTransfer(task.profile);
          check(store, task);
          if ("upload".equals(task.kind)) upload(store, task);
          else download(store, task);
          check(store, task);
          if (store.transition(task.id, "running", "done", "")) {
            store.progress(task.id, task.total);
            if ("upload".equals(task.kind)) store.discardBytes(task);
          }
          try {
            store.cache(
              task.profile,
              connection.request("/inbox", "GET", null)
            );
          } catch (Exception ignored) {}
        } catch (Exception e) {
          store.transition(task.id, "running", "failed", message(e));
        } finally {
          InboxTransfer value = connection;
          connection = null;
          if (value != null) value.close();
          currentId = null;
        }
      }
    } catch (Exception ignored) {
    } finally {
      main.post(() -> finish(startId));
    }
  }

  private static String message(Exception e) {
    String message = e.getMessage();
    return message == null || message.isEmpty()
      ? "传输失败，请检查连接后重试"
      : message;
  }

  private void check(InboxStore store, InboxStore.Task task) throws Exception {
    if (
      stopped || Thread.currentThread().isInterrupted()
    ) throw new InterruptedIOException("传输已暂停");
    InboxStore.Task latest = store.task(task.id);
    if (
      latest == null || !"running".equals(latest.state)
    ) throw new InterruptedIOException("传输已暂停");
    if (!InboxStore.sameIdentity(task.profile, ServerProfile.load(this))) {
      store.transition(
        task.id,
        "running",
        "paused",
        "服务器配置已改变；恢复原配置后可继续"
      );
      throw new InterruptedIOException("服务器配置已改变");
    }
  }

  private void progress(
    InboxStore store,
    InboxStore.Task task,
    long bytes,
    boolean force
  ) {
    long now = SystemClock.elapsedRealtime();
    if (force || now - lastProgress > 300) {
      lastProgress = now;
      store.progress(task.id, bytes);
      try {
        getSystemService(NotificationManager.class).notify(
          NOTIFICATION,
          notice(bytes, task.total)
        );
      } catch (RuntimeException ignored) {}
    }
  }

  private void upload(InboxStore store, InboxStore.Task task) throws Exception {
    JSONObject document = new JSONObject(task.document.toString());
    document.remove("_cleanup");
    JSONArray files = document.getJSONArray("files");
    for (int i = 0; i < files.length(); i++) files
      .getJSONObject(i)
      .remove("_blob");
    JSONObject status = connection.request("/inbox/uploads", "POST", document);
    if ("ready".equals(status.optString("state"))) return;
    JSONArray remote = status.getJSONArray("files");
    Map<String, Long> offsets = new HashMap<>();
    for (int i = 0; i < remote.length(); i++) {
      JSONObject file = remote.getJSONObject(i);
      offsets.put(file.getString("id"), file.getLong("offset"));
    }
    JSONArray local = task.document.getJSONArray("files");
    long before = 0;
    for (int i = 0; i < local.length(); i++) {
      JSONObject file = local.getJSONObject(i);
      long size = file.getLong("size");
      String id = file.getString("id");
      Long known = offsets.get(id);
      if (known == null || known < 0 || known > size) throw new IOException(
        "服务器附件进度无效"
      );
      long offset = known;
      File bytes = store.uploadFile(file);
      if (!bytes.isFile() || bytes.length() != size) throw new IOException(
        "本地附件已移除，请重新添加"
      );
      final long preceding = before;
      progress(store, task, before + offset, true);
      while (offset < size) {
        check(store, task);
        int length = (int) Math.min(InboxTransfer.CHUNK, size - offset);
        offset = connection.upload(task.id, id, bytes, offset, length, value ->
          progress(store, task, preceding + value, false)
        );
        progress(store, task, before + offset, true);
      }
      before += size;
    }
    check(store, task);
    connection.request(
      "/inbox/uploads/" + task.id + "/commit",
      "POST",
      new JSONObject()
    );
  }

  private void download(InboxStore store, InboxStore.Task task)
    throws Exception {
    JSONObject file = task.document.getJSONObject("file");
    long size = file.getLong("size");
    if (size < 0) throw new IOException("附件大小无效");
    File part = store.downloadPart(task);
    long offset = part.exists() ? part.length() : 0;
    if (offset > size) {
      if (!part.delete()) throw new IOException("无法重置未完成的下载");
      offset = 0;
    }
    if (
      size - offset > part.getParentFile().getUsableSpace() - 16 * 1024 * 1024L
    ) throw new IOException("手机空间不足，请清理后继续下载");
    if (!part.exists()) {
      try (FileOutputStream out = new FileOutputStream(part)) {
        out.getFD().sync();
      }
    }
    progress(store, task, offset, true);
    while (offset < size) {
      check(store, task);
      int length = (int) Math.min(InboxTransfer.CHUNK, size - offset);
      offset = connection.download(
        task.document.getJSONObject("item").getString("id"),
        file,
        part,
        offset,
        length,
        value -> progress(store, task, value, false)
      );
      progress(store, task, offset, true);
    }
    check(store, task);
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(part)) {
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) != -1) {
        check(store, task);
        sha.update(buf, 0, n);
      }
    }
    if (!InboxStore.hex(sha.digest()).equals(file.getString("sha256"))) {
      part.delete();
      store.progress(task.id, 0);
      throw new IOException("附件完整性校验失败，请重新下载");
    }
    check(store, task);
    store.finishDownload(task);
  }

  private void cleanup(InboxStore store, InboxStore.Task task) {
    try {
      store.discardBytes(task);
      if ("upload".equals(task.kind)) {
        if (
          !InboxStore.sameIdentity(task.profile, ServerProfile.load(this))
        ) return;
        currentId = task.id;
        try (InboxTransfer cleanup = new InboxTransfer(task.profile)) {
          connection = cleanup;
          if (stopped) return;
          try {
            cleanup.request("/inbox/uploads/" + task.id, "DELETE", null);
          } catch (app.thoughts.mobile.core.connection.ConnectionFailure e) {
            if (e.code != 404 && e.code != 409) throw e;
          }
        }
      }
      task.document.remove("_cleanup");
      store.updateDocument(task.id, task.document);
    } catch (Exception ignored) {
    } finally {
      connection = null;
      currentId = null;
    }
  }

  private void pauseAll(String message) {
    try (InboxStore store = new InboxStore(this)) {
      for (InboxStore.Task task : store.tasks())
        if (
          "running".equals(task.state) || "queued".equals(task.state)
        ) store.setState(task.id, "paused", message);
    } catch (Exception ignored) {}
    InboxTransfer value = connection;
    if (value != null) value.close();
  }

  private void finish(int startId) {
    if (startId != latestStart) return;
    release();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf(startId);
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    stopped = true;
    pauseAll("系统已暂停长时间传输，回到收件箱可继续");
    release();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    stopped = true;
    if (active == this) active = null;
    String interrupted = currentId;
    if (interrupted != null) {
      try (InboxStore store = new InboxStore(this)) {
        store.transition(
          interrupted,
          "running",
          "paused",
          "传输已中断，点击继续"
        );
      } catch (Exception ignored) {}
    }
    InboxTransfer value = connection;
    if (value != null) value.close();
    worker.shutdownNow();
    release();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
