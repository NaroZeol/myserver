package app.thoughts.mobile.modules.server;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.core.connection.*;
import app.thoughts.mobile.modules.terminal.TerminalActivity;
import org.json.JSONObject;

/** Server workspace: connection, monitoring and terminal entry, independent of installed modules. */
public final class ServerFeature extends Ui implements Feature {

  private final DeviceAccount account;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final RefreshLoop polling;
  private JSONObject snapshot;
  private LinearLayout metrics, storage;
  private TextView issue;
  private boolean loading, visible;
  private String problem = "";
  private long checkedAt;

  public ServerFeature(Feature.Host host) {
    super(host);
    account = host.account();
    polling = new RefreshLoop(
      new RefreshLoop.Scheduler() {
        public void post(Runnable task, long delay) {
          handler.postDelayed(task, delay);
        }

        public void remove(Runnable task) {
          handler.removeCallbacks(task);
        }
      },
      () -> refresh()
    );
    try {
      SharedPreferences saved = activity.getSharedPreferences(
        "server_status",
        0
      );
      String value = saved.getString("snapshot", "");
      if (!value.isEmpty()) snapshot = new JSONObject(value);
      checkedAt = saved.getLong("checked_at", 0);
    } catch (Exception ignored) {}
  }

  public String id() {
    return "server";
  }

  public String label() {
    return "服务器";
  }

  public String headerAction() {
    return account.isVerified() && account.can("system.read") ? "刷新" : "";
  }

  public String headerIcon() {
    return "sync";
  }

  public void performHeaderAction() {
    refresh();
  }

  public void resume() {
    visible = true;
    polling.resume(MonitorSettings.seconds(activity) * 1000L);
  }

  public void pause() {
    visible = false;
    polling.pause();
  }

  public void render(LinearLayout surface) {
    ServerProfile profile = host.serverProfile();
    space(surface, 12);
    if (profile == null) {
      surface.addView(text("连接你的服务器", 26, INK));
      space(surface, 16);
      surface.addView(
        text(
          "在手机上查看运行状态、打开终端，使用服务器提供的工具。",
          14,
          MUTED
        )
      );
      space(surface, 28);
      surface.addView(button("添加服务器", () -> configuration(), true));
      return;
    }
    surface.addView(text(profile.name, 28, INK));
    space(surface, 8);
    surface.addView(text(profile.address(), 13, MUTED));
    space(surface, 20);
    surface.addView(
      button(
        "打开终端",
        () ->
          activity.startActivity(new Intent(activity, TerminalActivity.class)),
        true
      )
    );
    space(surface, 8);
    surface.addView(
      text(
        ShellIdentity.registered(activity, profile)
          ? "设备密钥已授权 · 免密连接"
          : "首次登录后可记住设备，后续无需重复输入密码。",
        12,
        MUTED
      )
    );
    space(surface, 16);
    setting(surface, "连接配置", "修改", () -> configuration());
    if (!account.isVerified()) {
      space(surface, 18);
      surface.addView(
        button(loading ? "正在连接…" : "连接服务", () -> connect(), true)
      );
      space(surface, 8);
      surface.addView(
        text(
          "监控和扩展模块需要服务器部署 myserver；终端可独立使用。",
          12,
          MUTED
        )
      );
    }
    issue = text(problem, 13, ALERT);
    issue.setVisibility(
      problem.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE
    );
    surface.addView(issue);
    metrics = column();
    surface.addView(metrics);
    setting(surface, "监控刷新", MonitorSettings.label(activity), () ->
      MonitorSettings.show(activity, () -> {
        polling.interval(MonitorSettings.seconds(activity) * 1000L);
        host.redraw();
      })
    );
    storage = column();
    surface.addView(storage);
    updatePanels();
    LinearLayout device = card(surface, "设备授权", "");
    setting(
      device,
      "服务权限",
      account.isVerified() ? "已验证" : "未连接",
      () -> connect()
    );
    setting(device, "公钥与手动登记", "查看", () -> enrollment());
    setting(device, "重新授权设备", "", () -> passwordEnrollment());
  }

  private void updatePanels() {
    if (metrics == null || !host.activeFeature().equals(id())) return;
    metrics.removeAllViews();
    new SystemOverview(host).render(
      metrics,
      snapshot == null ? null : snapshot.optJSONObject("metrics"),
      checkedAt,
      account.isVerified() && account.can("system.read"),
      loading
    );
    if (issue != null) {
      issue.setText(problem);
      issue.setVisibility(
        problem.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE
      );
    }
    if (storage == null) return;
    storage.removeAllViews();
    if (snapshot == null) return;
    JSONObject disk = snapshot.optJSONObject("storage"),
      backup = snapshot.optJSONObject("backup");
    LinearLayout panel = card(storage, "服务存储", "");
    setting(
      panel,
      "数据库",
      disk == null ? "—" : size(disk.optLong("database_bytes")),
      null
    );
    setting(
      panel,
      "磁盘可用",
      disk == null ? "—" : size(disk.optLong("free_bytes")),
      null
    );
    setting(
      panel,
      "最近备份",
      backup == null ? "—" : date(backup.optString("latest_at", "")),
      null
    );
    setting(
      panel,
      "备份数量",
      backup == null ? "—" : backup.optInt("count") + " 份",
      null
    );
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

  public void refresh() {
    if (
      loading ||
      host.api().profile == null ||
      !account.isVerified() ||
      !account.can("system.read") ||
      !polling.begin()
    ) return;
    loading = true;
    updatePanels();
    final ServerApi api = host.api();
    IO.execute(() -> {
      JSONObject result = null;
      String error = "";
      boolean revoked = false;
      try {
        result = api.request("/system", "GET", null);
      } catch (Exception e) {
        error = errorMessage(e);
        revoked =
          e instanceof ConnectionFailure && ((ConnectionFailure) e).code == 401;
      }
      final JSONObject value = result;
      final String message = error;
      final boolean denied = revoked;
      runOnUiThread(() -> {
        loading = false;
        if (api == host.api()) {
          if (denied) account.clear();
          problem = message;
          if (value != null) {
            snapshot = value;
            checkedAt = System.currentTimeMillis();
            activity
              .getSharedPreferences("server_status", 0)
              .edit()
              .putString("snapshot", value.toString())
              .putLong("checked_at", checkedAt)
              .apply();
          }
          if (visible) {
            if (denied) host.redraw();
            else updatePanels();
          }
        }
        if (denied) polling.pause();
        polling.finished();
      });
    });
  }

  public void configuration() {
    if (!loading) new ServerConfiguration(host).show();
  }

  /** Try existing restricted authority before asking for another password. */
  public void connect() {
    if (loading || host.serverProfile() == null) return;
    loading = true;
    final ServerProfile profile = host.serverProfile();
    IO.execute(() -> {
      try {
        JSONObject session;
        try {
          session = new SshRpc(profile).request("/session", "GET", null);
        } catch (ConnectionFailure e) {
          if (e.code != 401 && e.code != 403) throw e;
          if (!ShellIdentity.registered(activity, profile)) {
            runOnUiThread(() -> {
              loading = false;
              passwordEnrollment();
            });
            return;
          }
          session = DeviceAccess.authorize(activity, profile, null, false);
        }
        final JSONObject verified = session;
        runOnUiThread(() -> authorized(profile, verified));
      } catch (Exception e) {
        runOnUiThread(() -> {
          loading = false;
          problem = errorMessage(e);
          host.redraw();
        });
      }
    });
  }

  private void authorized(ServerProfile profile, JSONObject session) {
    loading = false;
    if (!sameIdentity(profile)) return;
    account.verified(session);
    problem = "";
    host.redraw();
    status("设备已连接，后续使用密钥访问");
    host.authorizationChanged();
    if (visible) polling.resume(MonitorSettings.seconds(activity) * 1000L);
  }

  private boolean sameIdentity(ServerProfile profile) {
    return (
      profile.sameEndpoint(host.serverProfile()) &&
      profile.knownHost.equals(host.serverProfile().knownHost)
    );
  }

  public void passwordEnrollment() {
    if (loading || host.serverProfile() == null) return;
    final ServerProfile profile = host.serverProfile();
    LinearLayout form = column();
    form.setPadding(dp(24), dp(8), dp(24), dp(8));
    form.addView(text(profile.address(), 13, MUTED));
    EditText password = input("服务器密码", false);
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
    CheckBox shell = new CheckBox(activity);
    shell.setText("同时启用终端免密连接");
    shell.setChecked(true);
    shell.setMinHeight(dp(48));
    form.addView(shell);
    form.addView(
      text(
        "一次授权后，服务和终端使用各自的设备密钥。终端可执行此账户的命令；密码不会保存。",
        12,
        MUTED
      )
    );
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(activity)
      .setTitle("授权此设备")
      .setView(scroll)
      .setNegativeButton("取消", null)
      .setPositiveButton("授权并连接", null)
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
        boolean enableShell = shell.isChecked();
        dialog.dismiss();
        loading = true;
        problem = "";
        host.redraw();
        IO.execute(() -> {
          try {
            JSONObject session = DeviceAccess.authorize(
              activity,
              profile,
              secret,
              enableShell
            );
            runOnUiThread(() -> authorized(profile, session));
          } catch (Exception e) {
            runOnUiThread(() -> {
              loading = false;
              problem = errorMessage(e);
              host.redraw();
            });
          } finally {
            java.util.Arrays.fill(secret, (byte) 0);
          }
        });
      });
    });
    dialog.setOnDismissListener(d -> password.getText().clear());
    dialog.show();
  }

  private void enrollment() {
    IO.execute(() -> {
      try {
        DeviceKey key = DeviceAccess.key();
        String publicKey = key.publicKey(),
          fingerprint = key.fingerprint();
        runOnUiThread(() ->
          new AlertDialog.Builder(activity)
            .setTitle("设备服务公钥")
            .setMessage(
              "服务器登记命令：\n\npython3 ~/.local/share/myserver/deploy/register-device.py\n\n按提示粘贴公钥，再点击连接服务。\n\n" +
                fingerprint
            )
            .setPositiveButton("复制公钥", (d, w) -> copy(publicKey))
            .setNeutralButton("复制命令", (d, w) ->
              copy("python3 ~/.local/share/myserver/deploy/register-device.py")
            )
            .setNegativeButton("关闭", null)
            .show()
        );
      } catch (Exception e) {
        runOnUiThread(() -> status("无法读取设备密钥，请检查手机安全存储。"));
      }
    });
  }
}
