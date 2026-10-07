package app.thoughts.mobile.modules.server;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import app.thoughts.mobile.core.connection.*;
import com.jcraft.jsch.Session;

/** One user-started, phone-local SSH tunnel. Never restarts after process death. */
public final class PortForwardService extends Service {

  private static final String START = "app.thoughts.mobile.forward.START";
  private static final String STOP = "app.thoughts.mobile.forward.STOP";
  private static final int NOTIFICATION_ID = 4203;
  private static volatile State state = new State("idle", "", 0, 0, "", "");
  private volatile Session session;
  private volatile int generation;
  private int latestStartId;
  private PowerManager.WakeLock cpu;
  private WifiManager.WifiLock wifi;

  public static final class State {
    public final String phase, label, target, error;
    public final int serverPort, localPort;

    State(String phase, String label, int serverPort, int localPort, String target, String error) {
      this.phase = phase;
      this.label = label;
      this.serverPort = serverPort;
      this.localPort = localPort;
      this.target = target;
      this.error = error;
    }
  }

  public static State snapshot() {
    return state;
  }

  public static void start(Context context, ServerProfile profile, int port, String target, String label) {
    Intent request = new Intent(context, PortForwardService.class).setAction(START)
      .putExtra("port", port).putExtra("target", target).putExtra("label", label)
      .putExtra("identity", ShellIdentity.keyIdUnchecked(profile));
    context.startForegroundService(request);
  }

  public static void stop(Context context) {
    context.startService(new Intent(context, PortForwardService.class).setAction(STOP));
  }

  @Override
  public synchronized int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    int token = ++generation;
    showNotification("正在连接服务器", false);
    if (intent == null || STOP.equals(intent.getAction())) {
      state = new State("idle", "", 0, 0, "", "");
      finish(token);
      return START_NOT_STICKY;
    }
    int port = intent.getIntExtra("port", 0);
    String target = intent.getStringExtra("target");
    String label = intent.getStringExtra("label");
    ServerProfile profile = ServerProfile.load(this);
    if (!START.equals(intent.getAction()) || profile == null || port < 1 || port > 65535 ||
      !("127.0.0.1".equals(target) || "::1".equals(target)) ||
      !ShellIdentity.keyIdUnchecked(profile).equals(intent.getStringExtra("identity"))) {
      state = new State("error", "", 0, 0, "", "连接配置已变化，请重新选择端口");
      finish(token);
      return START_NOT_STICKY;
    }
    if (session != null) session.disconnect();
    state = new State("connecting", label == null ? "" : label, port, 0, target, "");
    try {
      holdLocks();
    } catch (RuntimeException e) {
      state = new State("error", "", 0, 0, "", "无法保持后台连接");
      finish(token);
      return START_NOT_STICKY;
    }
    new Thread(() -> connect(profile, port, target, label == null ? "" : label, token),
      "ssh-port-forward").start();
    return START_NOT_STICKY;
  }

  private void connect(ServerProfile profile, int port, String target, String label, int token) {
    Session opened = null;
    try {
      opened = SshConnection.open(profile, null, new DeviceKey(ShellIdentity.keyId(profile)));
      opened.setServerAliveInterval(30000);
      opened.setServerAliveCountMax(3);
      int local = opened.setPortForwardingL("127.0.0.1", 0, target, port);
      synchronized (this) {
        if (token != generation) return;
        session = opened;
        state = new State("active", label, port, local, target, "");
        showNotification(port + " → 手机 " + local, true);
      }
      while (token == generation && opened.isConnected()) Thread.sleep(2000);
      synchronized (this) {
        if (token == generation) {
          state = new State("error", label, port, 0, target, "SSH 连接已断开");
          finish(token);
        }
      }
    } catch (Exception e) {
      synchronized (this) {
        if (token == generation) {
          state = new State("error", label, port, 0, target,
            e instanceof com.jcraft.jsch.JSchException
              ? "无法建立端口转发，请检查 SSH 授权与服务器端口"
              : "无法连接服务器，请检查网络后重试");
          finish(token);
        }
      }
    } finally {
      if (opened != null) opened.disconnect();
    }
  }

  private void showNotification(String detail, boolean active) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("port_forward", "端口转发",
      NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0,
      new Intent(this, PortForwardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 1,
      new Intent(this, PortForwardService.class).setAction(STOP),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notice = new Notification.Builder(this, "port_forward")
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setContentTitle(active ? "端口转发运行中" : "端口转发正在连接")
      .setContentText(detail).setContentIntent(open).setOngoing(true)
      .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
      .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notice,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(NOTIFICATION_ID, notice);
  }

  private void holdLocks() {
    if (cpu != null) return;
    cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
      "myserver:port-forward");
    cpu.setReferenceCounted(false);
    cpu.acquire();
    WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    if (manager != null) {
      wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "myserver:port-forward");
      wifi.setReferenceCounted(false);
      wifi.acquire();
    }
  }

  private synchronized void finish(int token) {
    if (token != generation) return;
    ++generation;
    if (session != null) session.disconnect();
    session = null;
    releaseLocks();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelfResult(latestStartId);
  }

  private void releaseLocks() {
    if (wifi != null && wifi.isHeld()) wifi.release();
    if (cpu != null && cpu.isHeld()) cpu.release();
    wifi = null;
    cpu = null;
  }

  @Override
  public synchronized void onDestroy() {
    ++generation;
    if (session != null) session.disconnect();
    releaseLocks();
    if ("active".equals(state.phase) || "connecting".equals(state.phase))
      state = new State("idle", "", 0, 0, "", "");
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
