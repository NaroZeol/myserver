package app.thoughts.mobile.modules.inbox;

import app.thoughts.mobile.core.connection.*;
import com.jcraft.jsch.*;
import java.io.*;
import org.json.*;

/** Restricted RPC and bounded binary channels share one verified SSH session. */
public final class InboxTransfer implements AutoCloseable {

  public static final int CHUNK = 4 * 1024 * 1024;
  private volatile Session session;
  private volatile boolean closed;

  public interface Progress {
    void bytes(long absolute);
  }

  public InboxTransfer(ServerProfile profile) throws Exception {
    try {
      session = SshConnection.open(profile, null, DeviceAccess.key());
      session.setServerAliveInterval(15000);
    } catch (JSchException e) {
      throw SshConnection.failure(e, false);
    }
  }

  private ChannelExec channel(String command) throws Exception {
    if (
      closed || session == null || !session.isConnected()
    ) throw new InterruptedIOException("传输连接已关闭");
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(command);
    channel.setPty(false);
    channel.setAgentForwarding(false);
    return channel;
  }

  public JSONObject request(String path, String method, JSONObject body)
    throws Exception {
    ChannelExec channel = channel("myserver-rpc-v1");
    try {
      byte[] payload = (
        new JSONObject()
          .put("path", path)
          .put("method", method)
          .put("body", body == null ? JSONObject.NULL : body)
          .toString() + "\n"
      ).getBytes("UTF-8");
      if (payload.length > 140 * 1024) throw new IOException("收件内容过大");
      channel.setInputStream(new ByteArrayInputStream(payload));
      InputStream in = channel.getInputStream();
      channel.connect(10000);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        bytes.write(buffer, 0, n);
        if (bytes.size() > 16 * 1024 * 1024) throw new IOException(
          "收件箱列表过大，请先整理内容"
        );
      }
      return body(new JSONObject(bytes.toString("UTF-8")));
    } catch (JSchException e) {
      throw SshConnection.failure(e, false);
    } finally {
      channel.disconnect();
    }
  }

  public long upload(
    String item,
    String file,
    File source,
    long offset,
    int length,
    Progress progress
  ) throws Exception {
    checkChunk(offset, length);
    ChannelExec channel = channel("myserver-transfer-v1");
    try (RandomAccessFile data = new RandomAccessFile(source, "r")) {
      if (offset + length > data.length()) throw new IOException(
        "本地附件已改变，请重新添加"
      );
      channel.setInputStream(null);
      OutputStream out = channel.getOutputStream();
      InputStream in = channel.getInputStream();
      channel.connect(10000);
      writeHeader(
        out,
        new JSONObject()
          .put("op", "upload")
          .put("item", item)
          .put("file", file)
          .put("offset", offset)
          .put("length", length)
      );
      JSONObject ready = body(readHeader(in));
      if (ready.getLong("offset") != offset) throw new IOException(
        "服务器上传进度已变化，请重试"
      );
      data.seek(offset);
      byte[] buffer = new byte[65536];
      long sent = 0;
      while (sent < length) {
        int n = data.read(
          buffer,
          0,
          (int) Math.min(buffer.length, length - sent)
        );
        if (n < 0) throw new EOFException("本地附件不完整");
        out.write(buffer, 0, n);
        sent += n;
        if (progress != null) progress.bytes(offset + sent);
      }
      out.flush();
      JSONObject result = body(readHeader(in));
      long next = result.getLong("offset");
      if (next != offset + length) throw new IOException(
        "服务器未确认完整附件块"
      );
      return next;
    } catch (JSchException e) {
      throw SshConnection.failure(e, false);
    } finally {
      channel.disconnect();
    }
  }

  public long download(
    String item,
    JSONObject file,
    File target,
    long offset,
    int length,
    Progress progress
  ) throws Exception {
    checkChunk(offset, length);
    ChannelExec channel = channel("myserver-transfer-v1");
    try (RandomAccessFile data = new RandomAccessFile(target, "rw")) {
      channel.setInputStream(null);
      OutputStream out = channel.getOutputStream();
      InputStream in = channel.getInputStream();
      channel.connect(10000);
      writeHeader(
        out,
        new JSONObject()
          .put("op", "download")
          .put("item", item)
          .put("file", file.getString("id"))
          .put("offset", offset)
          .put("length", length)
      );
      JSONObject header = body(readHeader(in));
      long size = file.getLong("size");
      if (
        header.getLong("offset") != offset ||
        header.getLong("size") != size ||
        header.getLong("length") != length ||
        !header.getString("sha256").equals(file.getString("sha256"))
      ) throw new IOException("下载附件校验信息已变化");
      data.seek(offset);
      long received = 0;
      byte[] buffer = new byte[65536];
      while (received < length) {
        int n = in.read(
          buffer,
          0,
          (int) Math.min(buffer.length, length - received)
        );
        if (n < 0) throw new EOFException("附件下载中断");
        if (n == 0) continue;
        data.write(buffer, 0, n);
        received += n;
        if (progress != null) progress.bytes(offset + received);
      }
      data.getFD().sync();
      return offset + received;
    } catch (JSchException e) {
      throw SshConnection.failure(e, false);
    } finally {
      channel.disconnect();
    }
  }

  static void checkChunk(long offset, int length) throws IOException {
    if (
      offset < 0 ||
      length < 0 ||
      length > CHUNK ||
      offset > Long.MAX_VALUE - length
    ) throw new IOException("附件块无效");
  }

  private static void writeHeader(OutputStream out, JSONObject header)
    throws Exception {
    byte[] bytes = (header.toString() + "\n").getBytes("UTF-8");
    if (bytes.length > 8192) throw new IOException("传输请求过长");
    out.write(bytes);
    out.flush();
  }

  static JSONObject readHeader(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < 8192; i++) {
      int b = in.read();
      if (b < 0) throw new EOFException("服务器未返回传输状态");
      if (b == '\n') return new JSONObject(out.toString("UTF-8"));
      out.write(b);
    }
    throw new IOException("服务器传输响应过长");
  }

  private static JSONObject body(JSONObject response) throws Exception {
    int status = response.getInt("status");
    JSONObject body = response.getJSONObject("body");
    if (status >= 400) throw new ConnectionFailure(
      status,
      body.optString("error", "收件箱操作失败")
    );
    return body;
  }

  @Override
  public void close() {
    closed = true;
    Session value = session;
    session = null;
    if (value != null) value.disconnect();
  }
}
