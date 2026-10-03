package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.connection.ConnectionFailure;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.core.connection.SshConnection;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

public final class SshTransport implements Transport {

  private final ServerProfile profile;

  public SshTransport(ServerProfile profile) {
    this.profile = profile;
  }

  public JSONObject request(String path, String method, JSONObject body)
    throws Exception {
    Session session = null;
    ChannelExec channel = null;
    try {
      session = SshConnection.open(profile, null, DeviceEnrollment.key());
      channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand("thoughts-rpc-v1");
      channel.setPty(false);
      channel.setAgentForwarding(false);
      byte[] payload = (
        new JSONObject()
          .put("path", path)
          .put("method", method)
          .put("body", body == null ? JSONObject.NULL : body)
          .toString() + "\n"
      ).getBytes("UTF-8");
      if (payload.length > 140 * 1024) throw new ConnectionFailure(
        400,
        "内容过长，请分成多条想法"
      );
      channel.setInputStream(new ByteArrayInputStream(payload));
      InputStream input = channel.getInputStream();
      channel.connect(10000);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
        if (output.size() > 16 * 1024 * 1024) throw new ConnectionFailure(
          413,
          "响应过大，请在服务器导出备份"
        );
      }
      JSONObject response;
      try {
        response = new JSONObject(output.toString("UTF-8"));
      } catch (Exception e) {
        throw new ConnectionFailure(
          503,
          "服务器连接已建立，但想法服务未就绪，请检查设备登记"
        );
      }
      int status = response.getInt("status");
      JSONObject result = response.getJSONObject("body");
      if (status >= 400) throw new ConnectionFailure(
        status,
        result.optString("error", "请求失败")
      );
      return result;
    } catch (JSchException e) {
      throw SshConnection.failure(e, false);
    } finally {
      if (channel != null) channel.disconnect();
      if (session != null) session.disconnect();
    }
  }
}
