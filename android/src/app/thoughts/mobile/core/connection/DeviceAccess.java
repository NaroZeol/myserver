package app.thoughts.mobile.core.connection;

import android.content.Context;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.json.JSONObject;

/** One explicit authorization flow; passwords never survive the initial SSH authentication. */
public final class DeviceAccess {

  public static DeviceKey key() throws Exception {
    return new DeviceKey("thoughts-ssh-device-v1");
  }

  public static JSONObject authorize(
    Context context,
    ServerProfile profile,
    byte[] password,
    boolean enableShell
  ) throws Exception {
    Session session = null;
    boolean passwordAuthentication = password != null;
    try {
      session = passwordAuthentication
        ? SshConnection.open(profile, password)
        : SshConnection.open(
            profile,
            null,
            new DeviceKey(ShellIdentity.keyId(profile))
          );
      if (password != null) Arrays.fill(password, (byte) 0);
      if (enableShell) {
        DeviceKey shell = new DeviceKey(ShellIdentity.keyId(profile));
        ShellIdentity.register(session, shell);
        passwordAuthentication = false;
        Session proof = SshConnection.open(profile, null, shell);
        proof.disconnect();
        ShellIdentity.remember(context, profile);
      }
      registerRpc(session, "");
    } catch (JSchException e) {
      throw SshConnection.failure(e, passwordAuthentication);
    } finally {
      if (password != null) Arrays.fill(password, (byte) 0);
      if (session != null) session.disconnect();
    }
    return new SshRpc(profile).request("/session", "GET", null);
  }

  /** Grant just the inbox permissions, using existing shell authority when available. */
  public static JSONObject enableInbox(ServerProfile profile, byte[] password)
    throws Exception {
    Session session = null;
    boolean passwordAuthentication = password != null;
    try {
      session = passwordAuthentication
        ? SshConnection.open(profile, password)
        : SshConnection.open(
            profile,
            null,
            new DeviceKey(ShellIdentity.keyId(profile))
          );
      if (password != null) Arrays.fill(password, (byte) 0);
      registerRpc(session, " --add-capabilities inbox.read,inbox.write");
    } catch (JSchException e) {
      throw SshConnection.failure(e, passwordAuthentication);
    } finally {
      if (password != null) Arrays.fill(password, (byte) 0);
      if (session != null) session.disconnect();
    }
    return new SshRpc(profile).request("/session", "GET", null);
  }

  private static void registerRpc(Session session, String permissionOptions)
    throws Exception {
    ChannelExec channel = null;
    try {
      channel = (ChannelExec) session.openChannel("exec");
      channel.setCommand(
        "python3 \"$HOME/.local/share/myserver/deploy/register-device.py\" --name Android --key-file /dev/stdin" +
          permissionOptions
      );
      channel.setPty(false);
      channel.setAgentForwarding(false);
      channel.setInputStream(
        new ByteArrayInputStream((key().publicKey() + "\n").getBytes("UTF-8"))
      );
      InputStream input = channel.getInputStream();
      channel.connect(10000);
      byte[] buffer = new byte[1024];
      int total = 0;
      long deadline = System.nanoTime() + 35_000_000_000L;
      while (!channel.isClosed() || input.available() > 0) {
        if (System.nanoTime() > deadline) throw new ConnectionFailure(
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
        if (total > 16384) throw new ConnectionFailure(
          503,
          "服务器登记响应异常，请改用手动登记。"
        );
      }
      if (channel.getExitStatus() != 0) throw new ConnectionFailure(
        503,
        "登录成功，但设备登记未完成。请检查服务器登记脚本或使用手动登记。"
      );
    } finally {
      if (channel != null) channel.disconnect();
    }
  }
}
