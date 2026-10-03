package app.thoughts.mobile;

import android.content.Context;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shell authority uses an independent per-server key, never the restricted RPC identity. */
final class TerminalAuth {

  static String keyId(ServerProfile profile) throws Exception {
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
      (
        profile.user +
        "\n" +
        profile.host.toLowerCase(java.util.Locale.ROOT) +
        "\n" +
        profile.port +
        "\n" +
        profile.knownHost
      ).getBytes(StandardCharsets.UTF_8)
    );
    StringBuilder id = new StringBuilder("terminal-v1-");
    for (byte b : digest)
      id.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return id.toString();
  }

  static boolean registered(Context context, ServerProfile profile) {
    try {
      return context
        .getSharedPreferences("terminal_keys", 0)
        .getBoolean(keyId(profile), false);
    } catch (Exception e) {
      return false;
    }
  }

  static void remember(Context context, ServerProfile profile)
    throws Exception {
    if (
      !context
        .getSharedPreferences("terminal_keys", 0)
        .edit()
        .putBoolean(keyId(profile), true)
        .commit()
    ) throw new Exception(
      "终端密钥已登记，但本机状态保存失败，请使用密码重新连接"
    );
  }

  static void register(Session session, DeviceKey key) throws Exception {
    // Fixed program, public key over stdin. Preserve unrelated keys and serialize our own registrations.
    String script =
      "import os,sys,pathlib,fcntl,base64,contextlib\n" +
      "key=sys.stdin.buffer.readline(16385).decode('ascii').strip()\n" +
      "parts=key.split()\n" +
      "assert len(parts)==3 and parts[0]=='ssh-rsa' and len(key)<16384\n" +
      "base64.b64decode(parts[1],validate=True)\n" +
      "root=pathlib.Path.home()/'.ssh'\n" +
      "root.mkdir(mode=0o700,exist_ok=True)\n" +
      "with contextlib.ExitStack() as stack:\n" +
      " locks=[root/'terminal-registration.lock']\n" +
      " devices=pathlib.Path.home()/'.local/share/thoughts/devices'\n" +
      " if devices.is_dir(): locks.append(devices/'register.lock')\n" +
      " for path in locks:\n" +
      "  lock=stack.enter_context(path.open('a')); os.chmod(path,0o600); fcntl.flock(lock,fcntl.LOCK_EX)\n" +
      " p=root/'authorized_keys'\n" +
      " old=p.read_text() if p.exists() else ''\n" +
      " line='no-agent-forwarding,no-port-forwarding,no-X11-forwarding '+parts[0]+' '+parts[1]+' thoughts-terminal'\n" +
      " if line not in old.splitlines():\n" +
      "  with p.open('a') as out:\n" +
      "   os.chmod(p,0o600)\n" +
      "   out.write(('\\n' if old and not old.endswith('\\n') else '')+line+'\\n')\n" +
      "   out.flush(); os.fsync(out.fileno())\n";
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    try {
      channel.setCommand("python3 -c '" + script.replace("'", "'\"'\"'") + "'");
      channel.setInputStream(
        new ByteArrayInputStream(
          (key.publicKey() + "\n").getBytes(StandardCharsets.US_ASCII)
        )
      );
      channel.setErrStream(
        new java.io.OutputStream() {
          public void write(int b) {}
        }
      );
      InputStream input = channel.getInputStream();
      channel.connect(10000);
      long deadline = System.nanoTime() + 20_000_000_000L;
      byte[] discard = new byte[1024];
      int total = 0;
      while (!channel.isClosed() || input.available() > 0) {
        if (
          Thread.currentThread().isInterrupted()
        ) throw new InterruptedException();
        if (System.nanoTime() > deadline) throw new Exception(
          "终端密钥登记超时，可先使用本次密码登录"
        );
        if (input.available() > 0) {
          total += input.read(
            discard,
            0,
            Math.min(input.available(), discard.length)
          );
          if (total > 16384) throw new Exception("终端密钥登记响应异常");
        } else Thread.sleep(20);
      }
      if (channel.getExitStatus() != 0) throw new Exception(
        "未能登记终端密钥，请确认服务器安装 Python 3 且可以写入 authorized_keys；也可取消记住设备，仅用密码登录"
      );
    } finally {
      channel.disconnect();
    }
  }
}
