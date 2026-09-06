package app.thoughts.mobile;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.json.JSONObject;

/** Password is used once to run the fixed registration helper; no general shell is exposed. */
final class DeviceEnrollment {

  static JSONObject register(ServerProfile profile, byte[] password)
    throws Exception {
    Session session = null;
    ChannelExec channel = null;
    try {
      session = SshConnection.open(profile, password);
      Arrays.fill(password, (byte) 0);
      channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand(
        "python3 \"$HOME/.local/share/thoughts/deploy/register-device.py\" --name Android --key-file /dev/stdin"
      );
      channel.setPty(false);
      channel.setAgentForwarding(false);
      channel.setInputStream(
        new ByteArrayInputStream(
          (new DeviceKey().publicKey() + "\n").getBytes("UTF-8")
        )
      );
      InputStream input = channel.getInputStream();
      channel.connect(10000);
      byte[] buffer = new byte[1024];
      int total = 0;
      long deadline = System.nanoTime() + 35_000_000_000L;
      while (!channel.isClosed() || input.available() > 0) {
        if (System.nanoTime() > deadline) throw new Api.Failure(
          408,
          "设备登记超时，请重试或使用手动登记。"
        );
        int available = input.available();
        if (available == 0) {
          Thread.sleep(25);
          continue;
        }
        int count = input.read(buffer, 0, Math.min(available, buffer.length));
        if (count < 0) break;
        total += count;
        if (total > 16384) throw new Api.Failure(
          503,
          "服务器登记响应异常，请改用手动登记。"
        );
      }
      if (channel.getExitStatus() != 0) throw new Api.Failure(
        503,
        "登录成功，但设备登记未完成。请检查服务器登记脚本或使用手动登记。"
      );
    } catch (JSchException e) {
      throw SshConnection.failure(e, true);
    } finally {
      Arrays.fill(password, (byte) 0);
      if (channel != null) channel.disconnect();
      if (session != null) session.disconnect();
    }
    // A successful password login is insufficient: prove the restricted device key now works.
    return new SshTransport(profile).request("/session", "GET", null);
  }
}
