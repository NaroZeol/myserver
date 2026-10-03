package app.thoughts.mobile.modules.thoughts;

import android.content.Context;
import android.content.SharedPreferences;
import app.thoughts.mobile.core.connection.DeviceKey;
import org.json.JSONObject;

/** Local enrollment status only. Authentication always proves possession of DeviceKey. */
public final class Account {

  private final SharedPreferences prefs;

  public Account(Context context) {
    prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE);
  }

  public boolean isVerified() {
    return prefs.getBoolean("ssh_registered", false);
  }

  public void verified(JSONObject session) {
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

  public boolean can(String capability) {
    return prefs
      .getString("capabilities", "[]")
      .contains("\"" + capability + "\"");
  }

  public void recordSync(String message) {
    prefs
      .edit()
      .putString("sync_message", message)
      .putLong("sync_at", System.currentTimeMillis())
      .apply();
  }

  public String lastSync() {
    return prefs.getString("sync_message", "尚未同步");
  }

  public void clear() {
    prefs.edit().clear().commit();
  }
}
