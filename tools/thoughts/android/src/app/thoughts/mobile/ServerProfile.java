package app.thoughts.mobile;

import android.content.Context;
import android.util.Base64;
import com.jcraft.jsch.HostKey;

/** One configurable connection. Credentials are never stored in the profile. */
final class ServerProfile {

  final String id, name, host, user, knownHost;
  final int port;

  ServerProfile(
    String id,
    String name,
    String host,
    int port,
    String user,
    String knownHost
  ) {
    if (!id.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException(
      "Invalid profile ID"
    );
    if (
      host == null ||
      !host.matches("[A-Za-z0-9._:-]{1,253}") ||
      host.startsWith("-") ||
      port < 1 ||
      port > 65535
    ) throw new IllegalArgumentException("请填写正确的服务器地址与 SSH 端口");
    if (
      user == null || !user.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}")
    ) throw new IllegalArgumentException("请填写正确的 SSH 用户名");
    this.id = id;
    this.name = name.isEmpty() ? host : name;
    this.host = host;
    this.port = port;
    this.user = user;
    this.knownHost = knownHost.trim();
    if (!this.knownHost.isEmpty()) {
      try {
        String[] fields = this.knownHost.split("\\s+");
        HostKey key = new HostKey(
          host,
          Base64.decode(fields[1], Base64.NO_WRAP)
        );
        if (
          fields.length != 2 ||
          !fields[0].equals(key.getType()) ||
          !(
            fields[0].equals("ecdsa-sha2-nistp256") ||
            fields[0].equals("ssh-rsa")
          )
        ) throw new Exception();
      } catch (Exception e) {
        throw new IllegalArgumentException(
          "服务器公钥格式无效，请使用 ECDSA P-256 或 RSA 公钥"
        );
      }
    }
  }

  String address() {
    return user + "@" + host + ":" + port;
  }

  boolean sameEndpoint(ServerProfile other) {
    return (
      other != null &&
      host.equalsIgnoreCase(other.host) &&
      port == other.port &&
      user.equals(other.user)
    );
  }

  ServerProfile trusted(String key) {
    return new ServerProfile(id, name, host, port, user, key);
  }

  String fingerprint() throws Exception {
    return (
      "SHA256:" +
      Base64.encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(
          Base64.decode(knownHost.split("\\s+")[1], Base64.NO_WRAP)
        ),
        Base64.NO_WRAP | Base64.NO_PADDING
      )
    );
  }

  void save(Context context) {
    if (knownHost.isEmpty()) throw new IllegalStateException(
      "请先校验服务器身份"
    );
    if (
      !context
        .getSharedPreferences("server_config", 0)
        .edit()
        .putString("name", name)
        .putString("host", host)
        .putInt("port", port)
        .putString("user", user)
        .putString("key", knownHost)
        .commit()
    ) throw new IllegalStateException("连接配置保存失败");
  }

  static ServerProfile load(Context context) {
    android.content.SharedPreferences p = context.getSharedPreferences(
      "server_config",
      0
    );
    if (!p.contains("host") || p.getString("key", "").isEmpty()) return null;
    try {
      return new ServerProfile(
        "default",
        p.getString("name", ""),
        p.getString("host", ""),
        p.getInt("port", 22),
        p.getString("user", ""),
        p.getString("key", "")
      );
    } catch (Exception e) {
      return null;
    }
  }
}
