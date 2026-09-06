package app.thoughts.mobile;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import org.json.JSONObject;

/** Server overview is a separate capability; it never receives a shell or private key. */
final class ServerFeature extends Ui implements Feature {

  private JSONObject snapshot;
  private boolean loading;
  private String problem = "";
  private long checkedAt;

  ServerFeature(Feature.Host host) {
    super(host);
    try {
      SharedPreferences p = activity.getSharedPreferences("server_status", 0);
      String value = p.getString("snapshot", "");
      if (!value.isEmpty()) snapshot = new JSONObject(value);
      checkedAt = p.getLong("checked_at", 0);
    } catch (Exception ignored) {}
  }

  public String id() {
    return "server";
  }

  public String label() {
    return "服务";
  }

  public void render(LinearLayout surface) {
    space(surface, 12);
    surface.addView(text("我的服务器", 11, MUTED));
    space(surface, 14);
    ServerProfile profile = host.api().profile;
    if (profile == null) {
      space(surface, 14);
      surface.addView(text("连接你的服务器", 24, INK));
      space(surface, 12);
      surface.addView(text("配置 SSH 地址与账户，再登记这台设备。", 13, MUTED));
      space(surface, 24);
      surface.addView(button("配置服务器", () -> configuration(), true));
      return;
    }
    surface.addView(text(profile.name, 28, INK));
    space(surface, 8);
    surface.addView(text(profile.address(), 13, MUTED));
    space(surface, 26);
    setting(surface, "连接配置", "修改", () -> configuration());
    space(surface, 16);
    if (!account.isVerified()) {
      surface.addView(
        button(
          loading ? "正在连接…" : "连接服务器",
          () -> passwordEnrollment(),
          true
        )
      );
      space(surface, 12);
      surface.addView(
        text(
          "首次输入密码，自动登记设备。之后使用手机专属密钥连接。",
          12,
          MUTED
        )
      );
      space(surface, 20);
      setting(surface, "手动登记公钥", "", () -> enrollment());
      setting(surface, "已登记，验证连接", "", () -> verify());
    } else {
      setting(surface, "设备连接", loading ? "读取中" : "已验证", () ->
        verify()
      );
    }
    if (!problem.isEmpty()) {
      space(surface, 14);
      surface.addView(text(problem, 13, ALERT));
    }
    LinearLayout services = card(surface, "服务", "");
    if (
      !account.isVerified() || snapshot == null || !account.can("system.read")
    ) {
      setting(services, "想法", "等待连接", null);
      setting(services, "博客发布", "Gist", null);
      setting(services, "备份与存储", "—", null);
    } else {
      JSONObject counts = snapshot.optJSONObject("records");
      setting(
        services,
        "公开想法",
        counts == null ? "—" : counts.optInt("active") + " 条",
        null
      );
      JSONObject publication = snapshot.optJSONObject("publication");
      boolean pending =
        publication != null &&
        publication.optInt("generation") >
          publication.optInt("published_generation");
      setting(
        services,
        "博客发布",
        pending ? "待发布，重试" : "已同步 Gist",
        pending ? () -> host.sync() : null
      );
      if (pending && !publication.isNull("last_error")) {
        space(services, 10);
        services.addView(text(publication.optString("last_error"), 12, ALERT));
      }
      JSONObject backup = snapshot.optJSONObject("backup"),
        storage = snapshot.optJSONObject("storage");
      LinearLayout data = card(surface, "备份与存储", "");
      setting(
        data,
        "最近备份",
        backup == null ? "—" : date(backup.optString("latest_at", "")),
        null
      );
      setting(
        data,
        "备份数量",
        backup == null ? "—" : backup.optInt("count") + " 份",
        null
      );
      setting(
        data,
        "数据库",
        storage == null ? "—" : size(storage.optLong("database_bytes")),
        null
      );
      setting(
        data,
        "磁盘可用",
        storage == null ? "—" : size(storage.optLong("free_bytes")),
        null
      );
      space(surface, 14);
      surface.addView(
        text(
          "更新于 " +
            java.time.Instant.ofEpochMilli(checkedAt)
              .atZone(java.time.ZoneId.systemDefault())
              .format(
                java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm")
              ),
          11,
          MUTED
        )
      );
    }
    if (account.isVerified()) {
      LinearLayout device = card(surface, "设备", "");
      setting(device, "设备公钥", "查看", () -> enrollment());
      setting(device, "使用密码重新登记", "", () -> passwordEnrollment());
    }
  }

  private String date(String value) {
    try {
      return java.time.OffsetDateTime.parse(value)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"));
    } catch (Exception e) {
      return "暂无备份";
    }
  }

  void verify() {
    if (loading) return;
    loading = true;
    problem = "";
    host.redraw();
    status("正在验证设备与服务器身份…");
    IO.execute(() -> {
      try {
        JSONObject session = host.api().request("/session", "GET", null);
        account.verified(session);
        if (account.can("system.read")) {
          readSnapshot();
        }
        runOnUiThread(() -> {
          status("设备验证通过");
          if (account.can("thoughts")) host.autoSync();
        });
      } catch (Exception e) {
        problem = errorMessage(e);
        runOnUiThread(() -> status(problem));
      } finally {
        loading = false;
        runOnUiThread(() -> {
          if (host.activeFeature().equals(id())) host.redraw();
        });
      }
    });
  }

  private void readSnapshot() throws Exception {
    snapshot = host.api().request("/system", "GET", null);
    checkedAt = System.currentTimeMillis();
    activity
      .getSharedPreferences("server_status", 0)
      .edit()
      .putString("snapshot", snapshot.toString())
      .putLong("checked_at", checkedAt)
      .apply();
  }

  public void refresh() {
    if (loading || !account.isVerified() || !account.can("system.read")) return;
    loading = true;
    IO.execute(() -> {
      try {
        readSnapshot();
        problem = "";
      } catch (Exception e) {
        problem = errorMessage(e);
      } finally {
        loading = false;
        runOnUiThread(() -> {
          if (host.activeFeature().equals(id())) host.redraw();
        });
      }
    });
  }

  void configuration() {
    if (loading) return;
    ServerProfile previous = host.api().profile;
    LinearLayout form = column();
    form.setPadding(dp(24), dp(8), dp(24), dp(12));
    android.widget.EditText name = input("连接名称（可选）", false),
      address = input("服务器地址，例如 server.example.com", false),
      port = input("SSH 端口", false),
      user = input("SSH 用户名", false);
    for (android.widget.EditText field : new android.widget.EditText[] {
      name,
      address,
      port,
      user,
    }) {
      field.setSingleLine(true);
      form.addView(field);
      space(form, 12);
    }
    port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    port.setText(previous == null ? "22" : String.valueOf(previous.port));
    if (previous != null) {
      name.setText(previous.name);
      address.setText(previous.host);
      user.setText(previous.user);
    }
    form.addView(
      text("下一步读取服务器指纹，不发送密码。请核对身份后再信任。", 12, MUTED)
    );
    android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
    scroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(activity)
      .setTitle("服务器连接")
      .setView(scroll)
      .setNegativeButton("取消", null)
      .setPositiveButton("校验服务器", null)
      .create();
    dialog.setOnShowListener(d ->
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        try {
          ServerProfile candidate = new ServerProfile(
            "default",
            name.getText().toString().trim(),
            address.getText().toString().trim(),
            Integer.parseInt(port.getText().toString()),
            user.getText().toString().trim(),
            ""
          );
          dialog.dismiss();
          loading = true;
          status("正在读取服务器身份…");
          host.redraw();
          IO.execute(() -> {
            try {
              ServerProfile inspected = SshConnection.inspect(candidate);
              String fingerprint = inspected.fingerprint();
              runOnUiThread(() ->
                new AlertDialog.Builder(activity)
                  .setTitle("核对服务器身份")
                  .setMessage(
                    inspected.address() +
                      "\n\n" +
                      fingerprint +
                      "\n\n请与服务器管理员提供的指纹核对一致后再信任。这里只读取公钥，还没有发送密码。"
                  )
                  .setNegativeButton("取消", null)
                  .setPositiveButton("信任并保存", (a, b) ->
                    host.configure(inspected)
                  )
                  .show()
              );
            } catch (Exception e) {
              runOnUiThread(() -> status(errorMessage(e)));
            } finally {
              loading = false;
            }
          });
        } catch (Exception e) {
          address.setError(e.getMessage());
        }
      })
    );
    dialog.show();
  }

  void passwordEnrollment() {
    if (loading) return;
    LinearLayout form = column();
    form.setPadding(dp(24), dp(8), dp(24), dp(4));
    form.addView(text(host.api().profile.address(), 13, MUTED));
    space(form, 16);
    android.widget.EditText password = input("服务器密码", false);
    password.setInputType(
      android.text.InputType.TYPE_CLASS_TEXT |
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    );
    password.setSingleLine(true);
    password.setSaveEnabled(false);
    password.setImportantForAutofill(
      android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    );
    form.addView(password);
    space(form, 16);
    form.addView(
      text(
        "密码仅用于本次登记，不会保存。之后使用这台手机的专属密钥连接。",
        12,
        MUTED
      )
    );
    AlertDialog dialog = new AlertDialog.Builder(activity)
      .setTitle("连接服务器")
      .setView(form)
      .setNegativeButton("取消", null)
      .setPositiveButton("连接并登记", null)
      .create();
    dialog.setOnShowListener(d -> {
      dialog
        .getWindow()
        .addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        if (password.length() == 0) {
          password.setError("请输入服务器密码");
          return;
        }
        char[] characters = new char[password.length()];
        password.getText().getChars(0, characters.length, characters, 0);
        java.nio.ByteBuffer encoded =
          java.nio.charset.StandardCharsets.UTF_8.encode(
            java.nio.CharBuffer.wrap(characters)
          );
        byte[] secret = new byte[encoded.remaining()];
        encoded.get(secret);
        java.util.Arrays.fill(characters, '\0');
        if (encoded.hasArray()) java.util.Arrays.fill(
          encoded.array(),
          (byte) 0
        );
        password.getText().clear();
        dialog.dismiss();
        loading = true;
        problem = "";
        host.redraw();
        status("正在连接并登记设备…");
        IO.execute(() -> {
          try {
            JSONObject session = DeviceEnrollment.register(
              host.api().profile,
              secret
            );
            account.verified(session);
            if (account.can("system.read")) readSnapshot();
            runOnUiThread(() -> {
              status("设备已连接");
              if (account.can("thoughts")) host.autoSync();
            });
          } catch (Exception e) {
            problem = errorMessage(e);
            runOnUiThread(() -> status(problem));
          } finally {
            java.util.Arrays.fill(secret, (byte) 0);
            loading = false;
            runOnUiThread(() -> {
              if (host.activeFeature().equals(id())) host.redraw();
            });
          }
        });
      });
    });
    dialog.setOnDismissListener(d -> password.getText().clear());
    dialog.show();
  }

  void enrollment() {
    status("正在准备设备公钥…");
    IO.execute(() -> {
      try {
        DeviceKey key = new DeviceKey();
        String publicKey = key.publicKey(),
          fingerprint = key.fingerprint();
        runOnUiThread(() ->
          new AlertDialog.Builder(activity)
            .setTitle("登记这台手机")
            .setMessage(
              "先复制公钥，再在服务器执行：\n\npython3 ~/.local/share/thoughts/deploy/register-device.py\n\n按提示粘贴公钥，完成后点击验证连接。\n\n设备指纹\n" +
                fingerprint
            )
            .setPositiveButton("复制公钥", (d, w) -> copy(publicKey))
            .setNeutralButton("复制登记命令", (d, w) ->
              copy("python3 ~/.local/share/thoughts/deploy/register-device.py")
            )
            .setNegativeButton("关闭", null)
            .show()
        );
      } catch (Exception e) {
        runOnUiThread(() -> status("无法创建设备密钥，请检查手机安全存储。"));
      }
    });
  }
}
