package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.connection.DeviceAccess;
import app.thoughts.mobile.core.connection.DeviceKey;
import app.thoughts.mobile.core.connection.ServerProfile;
import org.json.JSONObject;

/** The thoughts module uses the shared restricted device identity. */
public final class DeviceEnrollment {

  public static DeviceKey key() throws Exception {
    return DeviceAccess.key();
  }

  public static JSONObject register(ServerProfile profile, byte[] password)
    throws Exception {
    return DeviceAccess.authorize(null, profile, password, false);
  }
}
