package app.thoughts.mobile;

import org.json.JSONObject;

final class Api {

  final ServerProfile profile;
  private final Transport transport;

  Api(ServerProfile profile) {
    this.profile = profile;
    transport = profile == null ? null : new SshTransport(profile);
  }

  static final class Failure extends Exception {

    final int code;

    Failure(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  JSONObject request(String path, String method, JSONObject body)
    throws Exception {
    if (transport == null) throw new Failure(400, "请先配置服务器连接");
    return transport.request(path, method, body);
  }
}
