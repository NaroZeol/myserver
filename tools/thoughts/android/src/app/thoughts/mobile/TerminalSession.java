package app.thoughts.mobile;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A single PTY; independent of the API worker, with bounded input and back-pressured output. */
final class TerminalSession {

  interface Listener {
    void connected(boolean registered);
    void output(byte[] data) throws Exception;
    void ended(String reason);
  }

  private final Listener listener;
  private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
    1,
    1,
    0,
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(64)
  );
  private volatile Session session;
  private volatile ChannelShell channel;
  private volatile OutputStream input;
  private volatile boolean closed, connected;
  private volatile int columns = 80,
    rows = 24;
  private Thread reader;

  TerminalSession(Listener listener) {
    this.listener = listener;
  }

  boolean isConnected() {
    return connected && !closed;
  }

  synchronized void start(
    ServerProfile profile,
    byte[] password,
    boolean enroll
  ) {
    if (reader != null) throw new IllegalStateException(
      "Session already started"
    );
    reader = new Thread(() -> run(profile, password, enroll), "terminal-ssh");
    reader.start();
  }

  private void run(ServerProfile profile, byte[] password, boolean enroll) {
    String reason = "连接已关闭";
    boolean usedPassword = password != null;
    try {
      if (closed) return;
      DeviceKey key =
        !usedPassword || enroll
          ? new DeviceKey(TerminalAuth.keyId(profile))
          : null;
      Session opened = SshConnection.open(profile, password, key);
      session = opened;
      if (password != null) Arrays.fill(password, (byte) 0);
      if (closed) return;
      if (enroll) {
        TerminalAuth.register(opened, key);
        opened.disconnect();
        if (closed) return;
        usedPassword = false;
        session = opened = SshConnection.open(profile, null, key);
      }
      if (closed) return;
      opened.setTimeout(0);
      opened.setServerAliveInterval(15000);
      opened.setServerAliveCountMax(3);
      channel = (ChannelShell) opened.openChannel("shell");
      channel.setAgentForwarding(false);
      channel.setPty(true);
      channel.setPtyType("xterm-256color", columns, rows, 0, 0);
      InputStream output = channel.getInputStream();
      input = channel.getOutputStream();
      channel.connect(10000);
      if (closed) return;
      channel.setPtySize(columns, rows, 0, 0);
      connected = true;
      listener.connected(enroll);
      byte[] buffer = new byte[8192];
      int count;
      while (!closed && (count = output.read(buffer)) != -1) {
        if (count > 0) listener.output(Arrays.copyOf(buffer, count));
      }
      reason =
        channel.getExitStatus() >= 0
          ? "会话已结束 · 退出码 " + channel.getExitStatus()
          : "连接已断开，可重新连接";
    } catch (Exception e) {
      if (!closed) {
        if (e instanceof JSchException) {
          Api.Failure failure = SshConnection.failure(
            (JSchException) e,
            usedPassword
          );
          reason =
            failure.code == 401 && !usedPassword
              ? "终端密钥未获授权，请在「更多」中使用密码连接。"
              : failure.getMessage().replace("本机记录已保留。", "");
        } else reason =
          e.getMessage() == null ? "终端连接中断，请重新连接" : e.getMessage();
      }
    } finally {
      if (password != null) Arrays.fill(password, (byte) 0);
      close();
      listener.ended(reason);
    }
  }

  boolean send(byte[] data) {
    if (!isConnected() || data.length > 65536) return false;
    return enqueue(() -> {
      try {
        if (isConnected()) {
          input.write(data);
          input.flush();
        }
      } catch (Exception e) {
        close();
      } finally {
        Arrays.fill(data, (byte) 0);
      }
    });
  }

  void resize(int columns, int rows) {
    this.columns = Math.max(2, Math.min(500, columns));
    this.rows = Math.max(2, Math.min(300, rows));
    if (isConnected()) enqueue(() -> {
      if (isConnected()) channel.setPtySize(this.columns, this.rows, 0, 0);
    });
  }

  private boolean enqueue(Runnable action) {
    try {
      writer.execute(action);
      return true;
    } catch (java.util.concurrent.RejectedExecutionException e) {
      return false;
    }
  }

  void close() {
    closed = true;
    connected = false;
    if (channel != null) channel.disconnect();
    if (session != null) session.disconnect();
    writer.shutdownNow();
    if (reader != null && reader != Thread.currentThread()) reader.interrupt();
  }
}
