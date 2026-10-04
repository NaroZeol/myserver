package app.thoughts.mobile.core.connection;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** A module requests new authority explicitly; restricted RPC keys cannot grant it. */
public final class CapabilityEnrollment {

  public static void show(
    Feature.Host host,
    String capability,
    Runnable onSuccess
  ) {
    if (!"inbox.read".equals(capability) && !"inbox.write".equals(capability)) {
      throw new IllegalArgumentException("Unsupported permission request");
    }
    ServerProfile profile = host.serverProfile();
    if (profile == null) {
      new ServerConfiguration(host).show();
      return;
    }
    boolean reuseKey = ShellIdentity.registered(host.activity(), profile);
    Ui ui = new Ui(host);
    LinearLayout form = ui.column();
    form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(12));
    form.addView(ui.text("允许这台设备读取、上传和管理收件箱。", 15, Ui.INK));
    EditText password = new EditText(host.activity());
    password.setSingleLine(true);
    password.setHint("SSH 登录密码");
    password.setInputType(
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
    );
    if (!reuseKey) form.addView(password);
    AlertDialog dialog = new AlertDialog.Builder(host.activity())
      .setTitle("启用收件箱")
      .setView(form)
      .setNegativeButton("取消", null)
      .setPositiveButton("启用", null)
      .create();
    dialog.setOnShowListener(ignored -> {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        byte[] secret = reuseKey
          ? null
          : password.getText().toString().getBytes(StandardCharsets.UTF_8);
        if (secret != null && secret.length == 0) {
          password.setError("请输入密码");
          return;
        }
        password.setText("");
        dialog.setCancelable(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("正在启用…");
        host.executor().execute(() -> {
          try {
            JSONObject session = DeviceAccess.enableInbox(profile, secret);
            host.activity().runOnUiThread(() -> {
              if (
                host.activity().isFinishing() || host.activity().isDestroyed()
              ) return;
              ServerProfile current = host.serverProfile();
              dialog.dismiss();
              if (
                current == null ||
                !profile.sameEndpoint(current) ||
                !profile.knownHost.equals(current.knownHost)
              ) {
                host.status("服务器已切换，请重新验证当前连接");
                return;
              }
              host.account().verified(session);
              host.authorizationChanged();
              onSuccess.run();
            });
          } catch (Exception error) {
            host.activity().runOnUiThread(() -> {
              if (
                host.activity().isFinishing() || host.activity().isDestroyed()
              ) return;
              dialog.dismiss();
              host.status(
                error.getMessage() == null
                  ? "启用失败，请检查服务端版本和连接"
                  : error.getMessage()
              );
            });
          } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
          }
        });
      });
    });
    dialog.show();
  }
}
