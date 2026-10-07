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
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        command.setErrStream(errors);
        InputStream output = command.getInputStream();
        command.connect(10000);
        byte[] buffer = new byte[256];
        while (output.read(buffer) != -1) {}
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!command.isClosed() && System.nanoTime() < deadline) Thread.sleep(20);
        check(command.getExitStatus() == 0,
          "Could not create legacy key fixture: " + errors.toString("UTF-8"));
      } finally {
        command.disconnect();
      }
    } finally {
      shell.disconnect();
    }
    Session denied = SshConnection.open(profile, null, key);
    try {
      expectFailure(denied, profile.port, 403);
      check(denied.getPortForwardingL().length == 0,
        "Denied destinations must not create a local listener");
    } finally {
      denied.disconnect();
    }
    // Upgrading users can already have this stale flag from a terminal login.
    // A successful shell login does not prove that direct-tcpip is authorized.
    ShellIdentity.remember(context, profile);
    context.getSharedPreferences("terminal_keys", 0).edit()
      .putBoolean(ShellIdentity.keyId(profile) + ":forward", true).commit();
    ShellIdentity.enableForwarding(context, profile);
    // Idempotent on an already-updated key; no password or duplicate entry needed.
    ShellIdentity.enableForwarding(context, profile);
    Session tunnel = SshConnection.open(profile, null, key);
    try {
      int local = PortForwardTunnel.listen(tunnel, "127.0.0.1", profile.port);
      check(local > 0, "SSH did not allocate a phone-local port");
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", local), 5000);
        socket.setSoTimeout(5000);
        byte[] banner = new byte[4];
        new java.io.DataInputStream(socket.getInputStream()).readFully(banner);
        check(new String(banner, "US-ASCII").equals("SSH-"),
          "Forwarded socket did not reach the fixture SSH daemon");
      }
      tunnel.delPortForwardingL("127.0.0.1", local);
      checkHttp(tunnel);
      for (String target : new String[] {"0.0.0.0", "example.com", "192.0.2.1"}) {
        try {
          PortForwardTunnel.listen(tunnel, target, profile.port);
          throw new AssertionError("Non-loopback target accepted: " + target);
        } catch (IllegalArgumentException expected) {}
      }
      check(tunnel.getPortForwardingL().length == 0, "Test listeners must be removed");
    } finally {
      tunnel.disconnect();
    }
  }

  private static void expectFailure(Session session, int port, int code) throws Exception {
    try {
      PortForwardTunnel.listen(session, "127.0.0.1", port);
      throw new AssertionError("Unreachable destination reported as ready");
    } catch (ConnectionFailure failure) {
      check(failure.code == code, "Wrong forwarding failure: " + failure.getMessage());
    }
  }

  private static void checkHttp(Session session) throws Exception {
    // Reserve a closed port and a tiny HTTP server for this check only. Stdin EOF
    // shuts both down, even if the test fails or the SSH connection is lost.
    String script =
      "import http.server,socket,sys,threading\n" +
      "class Handler(http.server.BaseHTTPRequestHandler):\n" +
      " def do_GET(self):\n" +
      "  body=b'forwarded-http-ok'\n" +
      "  self.send_response(200); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)\n" +
      " def log_message(self,*args): pass\n" +
      "closed=socket.socket(); closed.bind(('127.0.0.1',0))\n" +
      "server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)\n" +
      "threading.Thread(target=server.serve_forever,daemon=True).start()\n" +
      "print(str(server.server_port)+' '+str(closed.getsockname()[1]),flush=True)\n" +
      "sys.stdin.readline()\n" +
      "server.shutdown(); server.server_close(); closed.close()\n";
    ChannelExec server = (ChannelExec) session.openChannel("exec");
    int local = 0;
    java.io.OutputStream control = server.getOutputStream();
    try {
      server.setCommand("python3 -c '" + script.replace("'", "'\"'\"'") + "'");
      InputStream output = server.getInputStream();
      server.connect(10000);
      StringBuilder line = new StringBuilder();
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (true) {
        if (System.nanoTime() > deadline || server.isClosed())
          throw new AssertionError("HTTP fixture failed to start");
        if (output.available() == 0) { Thread.sleep(20); continue; }
        int b = output.read();
        if (b == '\n') break;
        check(b >= 0 && line.length() < 64, "Unexpected HTTP fixture output");
        line.append((char) b);
      }
      String[] ports = line.toString().split(" ");
      expectFailure(session, Integer.parseInt(ports[1]), 502);
      check(session.getPortForwardingL().length == 0,
        "Closed destinations must not create a local listener");
      local = PortForwardTunnel.listen(session, "127.0.0.1", Integer.parseInt(ports[0]));
      java.net.URI address = java.net.URI.create(PortForwardTunnel.browserUrl(local));
      check(address.getScheme().equals("http") && address.getHost().equals("127.0.0.1") &&
        address.getPort() == local, "Copied browser URL must include the HTTP scheme");
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(address.getHost(), address.getPort()), 5000);
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(("GET / HTTP/1.0\r\nHost: " + address.getAuthority() +
          "\r\n\r\n").getBytes("US-ASCII"));
        java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int n; (n = socket.getInputStream().read(buffer)) != -1;) {
          response.write(buffer, 0, n);
          check(response.size() < 8192, "Unexpected HTTP response size");
        }
        String page = response.toString("US-ASCII");
        check(page.startsWith("HTTP/1.0 200") && page.endsWith("forwarded-http-ok"),
          "Browser-style request did not reach the HTTP server through SSH");
      }
    } finally {
      try { control.close(); } finally {
        server.disconnect();
        if (local != 0) session.delPortForwardingL("127.0.0.1", local);
      }
    }
  }
}
