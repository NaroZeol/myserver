package app.thoughts.mobile.modules.thoughts;

import android.content.Context;

public final class ThoughtsSyncLog {

  public static void record(Context context, String message) {
    context
      .getSharedPreferences("thoughts_sync", 0)
      .edit()
      .putString("message", message)
      .apply();
  }

  public static String last(Context context) {
    return context
      .getSharedPreferences("thoughts_sync", 0)
      .getString("message", "尚未同步");
  }
}
