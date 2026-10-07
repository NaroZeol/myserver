package app.thoughts.mobile.core.connection;

import android.content.Context;
import app.thoughts.mobile.core.connection.DeviceKey;
import app.thoughts.mobile.core.connection.ServerProfile;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shell authority uses an independent per-server key, never the restricted RPC identity. */
public final class ShellIdentity {

  public static String keyIdUnchecked(ServerProfile profile) {
    try {
      return keyId(profile);
    } catch (Exception e) {
      throw new IllegalStateException("无法识别服务器密钥", e);
    }
  }

  public static String keyId(ServerProfile profile) throws Exception {
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

  public static boolean registered(Context context, ServerProfile profile) {
    try {
      return context
        .getSharedPreferences("terminal_keys", 0)
        .getBoolean(keyId(profile), false);
    } catch (Exception e) {
      return false;
    }
  }

  public static void remember(Context context, ServerProfile profile)
    throws Exception {
    if (
      !context
        .getSharedPreferences("terminal_keys", 0)
        .edit()
        .putBoolean(keyId(profile), true)
        .putBoolean(keyId(profile) + ":forward", true)
        .commit()
    ) throw new Exception("设备密钥已登记，但本机状态保存失败，请重试连接");
  }

  /** Upgrade a legacy terminal key once, using its existing shell access. */
  public static void enableForwarding(Context context, ServerProfile profile)
    throws Exception {
    if (!registered(context, profile)) throw new ConnectionFailure(
      401,
      "请先在终端启用免密连接"
    );
    if (context.getSharedPreferences("terminal_keys", 0).getBoolean(
      keyId(profile) + ":forward",
      false
    )) return;
    DeviceKey key = new DeviceKey(keyId(profile));
    Session session = SshConnection.open(profile, null, key);
    try {
      register(session, key);
    } finally {
      session.disconnect();
    }
    if (!context.getSharedPreferences("terminal_keys", 0).edit().putBoolean(
      keyId(profile) + ":forward",
      true
    ).commit()) throw new Exception("端口转发授权已更新，但本机状态保存失败");
  }

  public static void register(Session session, DeviceKey key) throws Exception {
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
      " devices=pathlib.Path.home()/'.local/share/myserver/devices'\n" +
      " if devices.is_dir(): locks.append(devices/'register.lock')\n" +
      " for path in locks:\n" +
      "  lock=stack.enter_context(path.open('a')); os.chmod(path,0o600); fcntl.flock(lock,fcntl.LOCK_EX)\n" +
      " if (pathlib.Path.home()/'.local/share/myserver/maintenance').exists(): raise RuntimeError('maintenance')\n" +
      " p=root/'authorized_keys'\n" +
      " old=p.read_text() if p.exists() else ''\n" +
      " legacy='no-agent-forwarding,no-port-forwarding,no-X11-forwarding '+parts[0]+' '+parts[1]+' myserver-terminal'\n" +
      " line='no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:*\",permitopen=\"[::1]:*\",permitlisten=\"127.0.0.1:1\",permitlisten=\"[::1]:1\" '+parts[0]+' '+parts[1]+' myserver-terminal'\n" +
      " matches=[existing for existing in old.splitlines() if parts[1] in existing.split()]\n" +
      " if any(existing not in (legacy,line) for existing in matches): raise RuntimeError('key has different authority')\n" +
      " if len(matches)>1: raise RuntimeError('duplicate terminal key')\n" +
      " if not matches or matches[0]==legacy:\n" +
      "  rows=old.splitlines()\n" +
      "  if matches: rows=[line if row==legacy else row for row in rows]\n" +
      "  else: rows.append(line)\n" +
      "  temp=root/('authorized_keys.'+str(os.getpid())+'.tmp')\n" +
      "  fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)\n" +
      "  try:\n" +
      "   with os.fdopen(fd,'w') as out:\n" +
      "    out.write('\\n'.join(rows)+'\\n'); out.flush(); os.fsync(out.fileno())\n" +
      "   os.replace(temp,p)\n" +
      "   directory=os.open(root,os.O_RDONLY); os.fsync(directory); os.close(directory)\n" +
      "  finally:\n" +
      "   if temp.exists(): temp.unlink()\n";
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
