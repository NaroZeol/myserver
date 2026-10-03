package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.connection.ConnectionFailure;
import app.thoughts.mobile.core.connection.ServerProfile;
import org.json.JSONObject;

public final class Api {

  public final ServerProfile profile;
  private final Transport transport;

  public Api(ServerProfile profile) {
    this.profile = profile;
    transport = profile == null ? null : new SshTransport(profile);
  }

  public JSONObject request(String path, String method, JSONObject body)
    throws Exception {
    if (transport == null) throw new ConnectionFailure(
      400,
      "请先配置服务器连接"
    );
    return transport.request(path, method, body);
  }
}
