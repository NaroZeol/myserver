package app.thoughts.mobile.shell.updates;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Durable OS-managed downloads followed by metadata, digest and signing checks. */
public final class UpdateManager {

  public static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final ExecutorService NETWORK =
    Executors.newSingleThreadExecutor();
  public static final String CHANGED = "app.thoughts.mobile.UPDATE_CHANGED";
  private static final int NOTIFICATION = 7811;
  private static boolean checking;

  private UpdateManager() {}

  public static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
  }

  public static List<UpdateCatalog.Release> cached(Context context) {
    List<UpdateCatalog.Release> result = new ArrayList<>();
    try {
      JSONArray data = new JSONArray(
        preferences(context).getString("catalog", "[]")
      );
      for (int i = 0; i < data.length(); i++) result.add(
        new UpdateCatalog.Release(data.getJSONObject(i))
      );
    } catch (Exception ignored) {}
    return result;
  }

  public static UpdateCatalog.Release selected(Context context) {
    return UpdateCatalog.select(
      cached(context),
      UpdateCatalog.Source.load(context)
    );
  }

  public static long installedCode(Context context, String name) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
      return Build.VERSION.SDK_INT >= 28
        ? info.getLongVersionCode()
        : info.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      return 0;
    }
  }

  public static void check(Context context, Runnable complete) {
    final Context app = context.getApplicationContext();
    final UpdateCatalog.Source source = UpdateCatalog.Source.load(app);
    synchronized (UpdateManager.class) {
      if (checking) return;
      checking = true;
    }
    preferences(app)
      .edit()
      .putLong("attempt", System.currentTimeMillis())
      .putString("check_error", "")
      .apply();
    NETWORK.execute(() -> {
      try {
        List<UpdateCatalog.Release> result = UpdateCatalog.fetch(
          source.repository
        );
        if (
          !UpdateCatalog.Source.load(app).repository.equals(source.repository)
        ) return;
        JSONArray data = new JSONArray();
        for (UpdateCatalog.Release value : result) data.put(value.metadata);
        preferences(app)
          .edit()
          .putString("catalog", data.toString())
          .putLong("checked", System.currentTimeMillis())
          .apply();
        UpdateCatalog.Release latest = selected(app);
        if (
          latest != null &&
          latest.minSdk <= Build.VERSION.SDK_INT &&
          latest.code > installedCode(app, latest.packageName) &&
          !latest.apkUrl.equals(preferences(app).getString("notified", ""))
        ) {
          notify(app, "发现新版本", latest.label());
          preferences(app).edit().putString("notified", latest.apkUrl).apply();
        }
      } catch (Exception e) {
        if (
          UpdateCatalog.Source.load(app).repository.equals(source.repository)
        ) preferences(app).edit().putString("check_error", error(e)).apply();
      } finally {
        synchronized (UpdateManager.class) {
          checking = false;
        }
        changed(app);
        if (complete != null) new Handler(Looper.getMainLooper()).post(
          complete
        );
        if (
          !UpdateCatalog.Source.load(app).repository.equals(source.repository)
        ) check(app, complete);
      }
    });
  }

  public static boolean checking() {
    synchronized (UpdateManager.class) {
      return checking;
    }
  }

  public static void automatic(Context context) {
    Context app = context.getApplicationContext();
    pruneInstalled(app);
    SharedPreferences prefs = preferences(app);
    long now = System.currentTimeMillis();
    if (
      prefs.getBoolean("automatic", true) &&
      UpdateCatalog.validRepository(
        UpdateCatalog.Source.load(app).repository
      ) &&
      now - prefs.getLong("checked", 0) > 12 * 60 * 60 * 1000L &&
      now - prefs.getLong("attempt", 0) > 15 * 60 * 1000L
    ) check(app, null);
    if (pending(app) != null) IO.execute(() -> verifyCompleted(app));
  }

  private static DownloadManager downloads(Context context) {
    return (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
  }

  static File file(Context context, UpdateCatalog.Release release)
    throws IOException {
    File folder = new File(context.getFilesDir(), "updates");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException(
      "无法创建更新目录"
    );
    return new File(folder, release.sha256 + ".apk");
  }

  private static File transportFile(
    Context context,
    UpdateCatalog.Release release
  ) throws IOException {
    File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (base == null) throw new IOException("下载目录不可用");
    File folder = new File(base, "updates");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException(
      "无法创建下载目录"
    );
    return new File(folder, release.sha256 + ".apk");
  }

  public static synchronized void start(
    Context context,
    UpdateCatalog.Release release
  ) throws Exception {
    if (release.minSdk > Build.VERSION.SDK_INT) throw new IOException(
      "此版本不支持当前 Android"
    );
    if (
      release.code <= installedCode(context, release.packageName)
    ) throw new IOException("已安装相同或更新版本");
    cancel(context);
    File target = transportFile(context, release);
    if (target.exists() && !target.delete()) throw new IOException(
      "无法清理旧安装包"
    );
    if (
      target.getParentFile().getUsableSpace() <
        release.size * 2 + 16 * 1024 * 1024 ||
      context.getFilesDir().getUsableSpace() <
        release.size * 2 + 16 * 1024 * 1024
    ) throw new IOException("手机存储空间不足");
    DownloadManager.Request request = new DownloadManager.Request(
      Uri.parse(release.apkUrl)
    )
      .setTitle("myserver · " + release.version)
      .setDescription("下载安装包")
      .setMimeType("application/vnd.android.package-archive")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      .setAllowedOverRoaming(false)
      .setDestinationInExternalFilesDir(
        context,
        Environment.DIRECTORY_DOWNLOADS,
        "updates/" + release.sha256 + ".apk"
      );
    long id = downloads(context).enqueue(request);
    if (
      !preferences(context)
        .edit()
        .putLong("download_id", id)
        .putString("download", release.metadata.toString())
        .putString("download_state", "downloading")
        .putString("download_error", "")
        .commit()
    ) {
      downloads(context).remove(id);
      throw new IOException("无法保存下载任务");
    }
    changed(context);
  }

  public static synchronized void cancel(Context context) {
    SharedPreferences prefs = preferences(context);
    long id = prefs.getLong("download_id", -1);
    if (id != -1) downloads(context).remove(id);
    try {
      UpdateCatalog.Release old = pending(context);
      if (old != null) {
        file(context, old).delete();
        transportFile(context, old).delete();
      }
    } catch (Exception ignored) {}
    prefs
      .edit()
      .remove("download_id")
      .remove("download")
      .remove("download_state")
      .remove("download_error")
      .remove("install_pending")
      .apply();
    changed(context);
  }

  public static synchronized void pruneInstalled(Context context) {
    UpdateCatalog.Release release = pending(context);
    if (
      release == null ||
      installedCode(context, release.packageName) < release.code
    ) return;
    try {
      PackageInfo info = context
        .getPackageManager()
        .getPackageInfo(release.packageName, signatureFlags());
      if (certificates(info).contains(release.certificate)) {
        cancel(context);
        context
          .getSystemService(NotificationManager.class)
          .cancel(NOTIFICATION);
      }
    } catch (Exception ignored) {}
  }

  public static UpdateCatalog.Release pending(Context context) {
    try {
      return new UpdateCatalog.Release(
        new JSONObject(preferences(context).getString("download", ""))
      );
    } catch (Exception e) {
      return null;
    }
  }

  public static long[] progress(Context context) {
    long id = preferences(context).getLong("download_id", -1);
    if (id == -1) return new long[] { 0, 0, 0 };
    try (
      Cursor cursor = downloads(context).query(
        new DownloadManager.Query().setFilterById(id)
      )
    ) {
      if (cursor == null || !cursor.moveToFirst()) return new long[] {
        -1,
        0,
        0,
      };
      return new long[] {
        cursor.getInt(
          cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
        ),
        cursor.getLong(
          cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
          )
        ),
        cursor.getLong(
          cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        ),
      };
    }
  }

  public static synchronized void verifyCompleted(Context context) {
    SharedPreferences prefs = preferences(context);
    UpdateCatalog.Release release = pending(context);
    if (
      release != null && prefs.getString("download_state", "").equals("ready")
    ) {
      cleanTransport(context);
      return;
    }
    if (
      release == null ||
      !prefs.getString("download_state", "").equals("downloading")
    ) return;
    // Recover a completed private rename even if the process died before its state commit.
    try {
      File verified = file(context, release);
      if (verified.isFile()) {
        try {
          verify(context, verified, release);
          ready(context, release);
          return;
        } catch (Exception ignored) {
          verified.delete();
        }
      }
    } catch (IOException ignored) {}
    long[] progress = progress(context);
    if (
      progress[0] == DownloadManager.STATUS_FAILED ||
      progress[0] == -1 ||
      prefs.getLong("download_id", -1) == -1
    ) {
      prefs
        .edit()
        .putString("download_state", "failed")
        .putString("download_error", "下载失败，请重试")
        .apply();
      changed(context);
      return;
    }
    if (progress[0] != DownloadManager.STATUS_SUCCESSFUL) return;
    File temporary = null;
    try {
      File verified = file(context, release);
      temporary = File.createTempFile(
        "download-",
        ".part",
        verified.getParentFile()
      );
      // External storage is transport staging only, especially on Android 8/9.
      // The installer receives the verified private copy, never this mutable source.
      try (
        InputStream input = new FileInputStream(
          transportFile(context, release)
        );
        FileOutputStream output = new FileOutputStream(temporary)
      ) {
        byte[] buffer = new byte[65536];
        int n;
        long total = 0;
        while ((n = input.read(buffer)) != -1) {
          total += n;
          if (total > release.size) throw new IOException("安装包大小不一致");
          output.write(buffer, 0, n);
        }
        output.getFD().sync();
      }
      verify(context, temporary, release);
      if (!temporary.renameTo(verified)) throw new IOException(
        "无法保存已校验安装包"
      );
      verified.setReadOnly();
      ready(context, release);
    } catch (Exception e) {
      try {
        file(context, release).delete();
      } catch (Exception ignored) {}
      prefs
        .edit()
        .putString("download_state", "failed")
        .putString("download_error", error(e))
        .apply();
    } finally {
      if (temporary != null) temporary.delete();
    }
    changed(context);
  }

  private static void ready(Context context, UpdateCatalog.Release release)
    throws IOException {
    if (
      !preferences(context).edit().putString("download_state", "ready").commit()
    ) throw new IOException("无法保存更新状态");
    cleanTransport(context);
    notify(context, "安装包已准备好", release.label());
    changed(context);
  }

  private static void cleanTransport(Context context) {
    long id = preferences(context).getLong("download_id", -1);
    if (id != -1) downloads(context).remove(id);
    preferences(context).edit().remove("download_id").apply();
    try {
      UpdateCatalog.Release release = pending(context);
      if (release != null) transportFile(context, release).delete();
    } catch (Exception ignored) {}
  }

  public static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    return hex(digest.digest());
  }

  public static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes)
      result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private static int signatureFlags() {
    return Build.VERSION.SDK_INT >= 28
      ? PackageManager.GET_SIGNING_CERTIFICATES
      : PackageManager.GET_SIGNATURES;
  }

  public static Set<String> certificates(PackageInfo info) throws Exception {
    Set<String> result = new HashSet<>();
    android.content.pm.Signature[] signatures =
      Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
        ? info.signingInfo.getApkContentsSigners()
        : info.signatures;
    if (
      signatures != null
    ) for (android.content.pm.Signature value : signatures)
      result.add(
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
      );
    return result;
  }

  public static void verify(
    Context context,
    File apk,
    UpdateCatalog.Release release
  ) throws Exception {
    if (
      apk.length() != release.size || !hash(apk).equals(release.sha256)
    ) throw new IOException("安装包校验失败，请重新下载");
    PackageManager manager = context.getPackageManager();
    PackageInfo info = manager.getPackageArchiveInfo(
      apk.getPath(),
      // Android 10 collects archive certificates only with the legacy flag.
      signatureFlags() | PackageManager.GET_SIGNATURES
    );
    if (info == null) throw new IOException("系统无法读取安装包信息");
    if (!info.packageName.equals(release.packageName)) throw new IOException(
      "安装包包名与发布信息不一致"
    );
    if (
      (Build.VERSION.SDK_INT >= 28
        ? info.getLongVersionCode()
        : info.versionCode) != release.code ||
      !release.version.equals(info.versionName)
    ) throw new IOException("安装包版本与发布信息不一致");
    if (
      !certificates(info).equals(Collections.singleton(release.certificate))
    ) throw new IOException("安装包签名与发布信息不一致");
    try {
      PackageInfo installed = manager.getPackageInfo(
        release.packageName,
        signatureFlags()
      );
      if (
        !certificates(installed).equals(certificates(info))
      ) throw new IOException("安装包签名与当前应用不一致");
      if (
        (Build.VERSION.SDK_INT >= 28
          ? installed.getLongVersionCode()
          : installed.versionCode) > release.code
      ) throw new IOException("已安装更新版本，不能降级");
    } catch (PackageManager.NameNotFoundException ignored) {}
  }

  public static void install(Activity activity) {
    UpdateCatalog.Release release = pending(activity);
    if (
      release == null ||
      !preferences(activity).getString("download_state", "").equals("ready")
    ) return;
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      new AlertDialog.Builder(activity)
        .setTitle("允许安装更新")
        .setMessage("请在系统设置中允许 myserver 安装应用。")
        .setNegativeButton("取消", null)
        .setPositiveButton("打开设置", (d, w) -> {
          try {
            preferences(activity)
              .edit()
              .putBoolean("install_pending", true)
              .apply();
            activity.startActivity(
              new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())
              )
            );
          } catch (ActivityNotFoundException e) {
            preferences(activity).edit().remove("install_pending").apply();
            android.widget.Toast.makeText(
              activity,
              "无法打开安装权限设置",
              1
            ).show();
          }
        })
        .show();
      return;
    }
    IO.execute(() -> {
      try {
        verify(activity, file(activity, release), release);
        activity.runOnUiThread(() -> {
          if (activity.isFinishing() || activity.isDestroyed()) return;
          try {
            Uri uri = Uri.parse(
              "content://" +
                activity.getPackageName() +
                ".updates/" +
                release.sha256
            );
            Intent install = new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("myserver", uri));
            activity.startActivity(install);
          } catch (Exception e) {
            android.widget.Toast.makeText(activity, error(e), 1).show();
          }
        });
      } catch (Exception e) {
        activity.runOnUiThread(() ->
          android.widget.Toast.makeText(activity, error(e), 1).show()
        );
      }
    });
  }

  public static void resumeInstall(Activity activity) {
    SharedPreferences prefs = preferences(activity);
    if (prefs.getBoolean("install_pending", false)) {
      prefs.edit().remove("install_pending").apply();
      if (activity.getPackageManager().canRequestPackageInstalls()) install(
        activity
      );
    }
  }

  private static void notify(Context context, String title, String text) {
    if (
      Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(
        android.Manifest.permission.POST_NOTIFICATIONS
      ) != PackageManager.PERMISSION_GRANTED
    ) return;
    NotificationManager manager = context.getSystemService(
      NotificationManager.class
    );
    manager.createNotificationChannel(
      new NotificationChannel(
        "app_updates",
        "应用更新",
        NotificationManager.IMPORTANCE_DEFAULT
      )
    );
    PendingIntent intent = PendingIntent.getActivity(
      context,
      NOTIFICATION,
      new Intent(context, UpdateActivity.class),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    manager.notify(
      NOTIFICATION,
      new Notification.Builder(context, "app_updates")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(intent)
        .setAutoCancel(true)
        .build()
    );
  }

  static void changed(Context context) {
    context.sendBroadcast(
      new Intent(CHANGED).setPackage(context.getPackageName())
    );
  }

  public static String error(Exception error) {
    String value = error.getMessage();
    return value == null || value.length() > 180
      ? "更新暂时不可用，请稍后重试"
      : value;
  }
}
