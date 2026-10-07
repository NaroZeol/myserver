package app.thoughts.mobile.modules.server;

import app.thoughts.mobile.core.connection.ConnectionFailure;
import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/** Validate the SSH destination before advertising a phone-local listener. */
final class PortForwardTunnel {

  static int listen(Session session, String target, int port) throws Exception {
    if (!("127.0.0.1".equals(target) || "::1".equals(target)) || port < 1 || port > 65535)
      throw new IllegalArgumentException("只能转发服务器本机端口");
    ChannelDirectTCPIP probe = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
    try {
      probe.setHost(target);
      probe.setPort(port);
      // No input stream: JSch waits synchronously for the SSH channel-open reply.
      // Do not send HTTP or other application bytes to an arbitrary TCP service.
      probe.setOutputStream(new java.io.OutputStream() {
        public void write(int value) {}
        public void write(byte[] value, int offset, int length) {}
      });
      probe.connect(10000);
    } catch (JSchException e) {
      // JSch stores SSH_MSG_CHANNEL_OPEN_FAILURE's reason in the exit status.
      if (probe.getExitStatus() == 1) throw new ConnectionFailure(403,
        "SSH 拒绝端口转发，请检查服务器的转发权限");
      if (probe.getExitStatus() == 2) throw new ConnectionFailure(502,
        "服务器端口 " + port + " 无法连接，请确认程序已启动并监听本机地址");
      throw new ConnectionFailure(504, "无法连通服务器端口，请检查网络和服务后重试");
    } finally {
      probe.disconnect();
    }
    // Binding locally alone cannot detect a refused or unauthorized destination.
    return session.setPortForwardingL("127.0.0.1", 0, target, port);
  }

  static String browserUrl(int localPort) {
    return "http://127.0.0.1:" + localPort + "/";
  }
}
