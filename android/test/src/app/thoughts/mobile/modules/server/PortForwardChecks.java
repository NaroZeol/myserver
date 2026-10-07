package app.thoughts.mobile.modules.server;

import android.content.Context;
import app.thoughts.mobile.core.connection.*;
import com.jcraft.jsch.*;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Real SSH fixture: upgrade an old terminal key, then carry TCP over localhost only. */
public final class PortForwardChecks {

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void run(Context context, ServerProfile profile) throws Exception {
    DeviceKey key = new DeviceKey(ShellIdentity.keyId(profile));
    Session shell = SshConnection.open(profile, null, key);
    try {
      String script =
        "from pathlib import Path\n" +
        "p=Path.home()/'.ssh/authorized_keys'\n" +
        "s=p.read_text()\n" +
        "new='no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:*\",permitopen=\"[::1]:*\",permitlisten=\"127.0.0.1:1\",permitlisten=\"[::1]:1\"'\n" +
        "old='no-agent-forwarding,no-port-forwarding,no-X11-forwarding'\n" +
        "assert s.count(new)==1\n" +
        "p.write_text(s.replace(new,old,1))\n" +
        "p.chmod(0o600)\n";
      ChannelExec command = (ChannelExec) shell.openChannel("exec");
      try {
        command.setCommand("python3 -c '" + script.replace("'", "'\"'\"'") + "'");
        InputStream output = command.getInputStream();
        command.connect(10000);
        byte[] buffer = new byte[256];
        while (output.read(buffer) != -1) {}
        check(command.getExitStatus() == 0, "Could not create legacy key fixture");
      } finally {
        command.disconnect();
      }
    } finally {
      shell.disconnect();
    }
    context.getSharedPreferences("terminal_keys", 0).edit()
      .putBoolean(ShellIdentity.keyId(profile) + ":forward", false).commit();
    ShellIdentity.enableForwarding(context, profile);
    Session tunnel = SshConnection.open(profile, null, key);
    try {
      int local = tunnel.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", profile.port);
      check(local > 0, "SSH did not allocate a phone-local port");
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", local), 5000);
        socket.setSoTimeout(5000);
        byte[] banner = new byte[4];
        int count = socket.getInputStream().read(banner);
        check(count == 4 && new String(banner, "US-ASCII").equals("SSH-"),
          "Forwarded socket did not reach the fixture SSH daemon");
      }
      tunnel.delPortForwardingL("127.0.0.1", local);
    } finally {
      tunnel.disconnect();
    }
  }
}
