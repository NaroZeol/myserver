package app.thoughts.mobile.modules.server;

import android.app.AlertDialog;
import android.content.Context;

public final class MonitorSettings {

  private static final int[] SECONDS = { 0, 2, 5, 10, 30, 60 };
  private static final String[] LABELS = {
    "手动刷新",
    "2 秒",
    "5 秒",
    "10 秒",
    "30 秒",
    "60 秒",
  };

  public static int seconds(Context context) {
    int value = context
      .getSharedPreferences("monitor_settings", 0)
      .getInt("seconds", 5);
    for (int allowed : SECONDS) if (value == allowed) return value;
    return 5;
  }

  public static String label(Context context) {
    int value = seconds(context);
    return value == 0 ? "手动刷新" : value + " 秒";
  }

  public static void show(Context context, Runnable changed) {
    int selected = 0;
    for (int i = 0; i < SECONDS.length; i++) if (
      seconds(context) == SECONDS[i]
    ) selected = i;
    new AlertDialog.Builder(context)
      .setTitle("监控刷新间隔")
      .setSingleChoiceItems(LABELS, selected, (dialog, index) -> {
        boolean saved = context
          .getSharedPreferences("monitor_settings", 0)
          .edit()
          .putInt("seconds", SECONDS[index])
          .commit();
        if (saved) {
          dialog.dismiss();
          changed.run();
        }
      })
      .setNegativeButton("取消", null)
      .show();
  }
}
