package app.thoughts.mobile.modules.terminal;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;

/** User-started interactive SSH session. Never restarts or replays commands after process death. */
public final class TerminalService extends Service {

  static final int NOTIFICATION_ID = 4201;
  static final String DISCONNECT = "app.thoughts.mobile.terminal.DISCONNECT";
  private PowerManager.WakeLock cpu;
  private WifiManager.WifiLock wifi;
  private TerminalRuntime runtime;
  private int latestStartId;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    // Always satisfy startForegroundService, including cancellation before service creation.
    NotificationManager notifications = getSystemService(
      NotificationManager.class
    );
    notifications.createNotificationChannel(
      new NotificationChannel(
        "terminal",
        "终端会话",
        NotificationManager.IMPORTANCE_LOW
      )
    );
    PendingIntent open = PendingIntent.getActivity(
      this,
      0,
      new Intent(this, TerminalActivity.class).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK |
          Intent.FLAG_ACTIVITY_CLEAR_TOP |
          Intent.FLAG_ACTIVITY_SINGLE_TOP
      ),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    PendingIntent stop = PendingIntent.getService(
      this,
      1,
      new Intent(this, TerminalService.class).setAction(DISCONNECT),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );
    Notification notice = new Notification.Builder(this, "terminal")
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setContentTitle("终端会话运行中")
      .setContentText("点击返回终端")
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setVisibility(Notification.VISIBILITY_SECRET)
      .addAction(new Notification.Action.Builder(null, "断开", stop).build())
      .build();
    if (Build.VERSION.SDK_INT >= 34) startForeground(
      NOTIFICATION_ID,
      notice,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    );
    else startForeground(NOTIFICATION_ID, notice);
    runtime = TerminalRuntime.current();
    if (runtime == null || !runtime.busy) {
      finishSession();
      return START_NOT_STICKY;
    }
    if (intent != null && DISCONNECT.equals(intent.getAction())) {
      runtime.disconnect("已断开终端");
      finishSession();
      return START_NOT_STICKY;
    }
    try {
      if (cpu == null) {
        cpu = getSystemService(PowerManager.class).newWakeLock(
          PowerManager.PARTIAL_WAKE_LOCK,
          "myserver:terminal"
        );
        cpu.setReferenceCounted(false);
        cpu.acquire();
        WifiManager manager =
          (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager != null) {
          wifi = manager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "myserver:terminal"
          );
          wifi.setReferenceCounted(false);
          wifi.acquire();
        }
      }
      runtime.serviceReady(this);
    } catch (RuntimeException e) {
      runtime.disconnect("无法保持后台终端，请重新连接");
      finishSession();
    }
    return START_NOT_STICKY;
  }

  void finishSession() {
    release();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf(latestStartId);
  }

  private void release() {
    if (wifi != null && wifi.isHeld()) wifi.release();
    if (cpu != null && cpu.isHeld()) cpu.release();
    wifi = null;
    cpu = null;
  }

  boolean holdingWakeLock() {
    return cpu != null && cpu.isHeld();
  }

  @Override
  public void onDestroy() {
    release();
    if (runtime != null) runtime.serviceStopped(this);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
