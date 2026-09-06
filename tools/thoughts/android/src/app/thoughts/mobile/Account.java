package app.thoughts.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Local enrollment status only. Authentication always proves possession of DeviceKey. */
final class Account {

  private final SharedPreferences prefs;

  Account(Context context) {
    prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE);
  }

  boolean isVerified() {
    return prefs.getBoolean("ssh_registered", false);
  }

  void verified(JSONObject session) {
    prefs
      .edit()
      .remove("token")
      .putBoolean("ssh_registered", true)
      .putString(
        "capabilities",
        session.optJSONArray("capabilities") == null
          ? "[]"
          : session.optJSONArray("capabilities").toString()
      )
      .putLong("verified_at", System.currentTimeMillis())
      .commit();
  }

  boolean can(String capability) {
    return prefs
      .getString("capabilities", "[]")
      .contains("\"" + capability + "\"");
  }

  void recordSync(String message) {
    prefs
      .edit()
      .putString("sync_message", message)
      .putLong("sync_at", System.currentTimeMillis())
      .apply();
  }

  String lastSync() {
    return prefs.getString("sync_message", "尚未同步");
  }

  void clear() {
    prefs.edit().clear().commit();
  }
}
