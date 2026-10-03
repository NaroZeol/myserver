package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.core.connection.SshRpc;
import org.json.JSONObject;

/** Adapter from the thoughts transport contract to the shared SSH RPC connection. */
public final class SshTransport implements Transport {

  private final SshRpc rpc;

  public SshTransport(ServerProfile profile) {
    rpc = new SshRpc(profile);
  }

  public JSONObject request(String path, String method, JSONObject body)
    throws Exception {
    return rpc.request(path, method, body);
  }
}
