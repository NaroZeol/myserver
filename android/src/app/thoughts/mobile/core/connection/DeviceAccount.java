package app.thoughts.mobile.core.connection;

import android.content.Context;
import android.content.SharedPreferences;
import app.thoughts.mobile.core.connection.DeviceKey;
import org.json.JSONObject;

/** Local enrollment status only. Authentication always proves possession of DeviceKey. */
public final class DeviceAccount {

  private final SharedPreferences prefs;

  public DeviceAccount(Context context) {
    prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE);
  }

  public boolean isVerified() {
    return prefs.getBoolean("ssh_registered", false);
  }

  public void verified(JSONObject session) {
    if (
      !prefs
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
        .commit()
    ) throw new IllegalStateException("设备授权状态保存失败，请重试连接");
  }

  public boolean can(String capability) {
    return prefs
      .getString("capabilities", "[]")
      .contains("\"" + capability + "\"");
  }

  public void clear() {
    prefs.edit().clear().commit();
  }
}
