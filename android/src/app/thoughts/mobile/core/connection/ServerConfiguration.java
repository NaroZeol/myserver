package app.thoughts.mobile.core.connection;

import android.app.AlertDialog;
import android.widget.LinearLayout;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;

/** The server configuration dialog is shared by every SSH feature. */
public final class ServerConfiguration extends Ui {

  private boolean loading;

  public ServerConfiguration(Feature.Host host) {
    super(host);
  }

  public void show() {
    if (loading) return;
    ServerProfile previous = host.serverProfile();
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
          loading = true;
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("正在读取…");
          dialog.setCancelable(false);
          dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
          IO.execute(() -> {
            try {
              ServerProfile inspected = SshConnection.inspect(candidate);
              String fingerprint = inspected.fingerprint();
              runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dialog.dismiss();
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
                  .show();
              });
            } catch (Exception e) {
              runOnUiThread(() -> address.setError(errorMessage(e)));
            } finally {
              runOnUiThread(() -> {
                loading = false;
                if (dialog.isShowing()) {
                  dialog.setCancelable(true);
                  dialog
                    .getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setEnabled(true);
                  dialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(true);
                  dialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setText("校验服务器");
                }
              });
            }
          });
        } catch (Exception e) {
          address.setError(e.getMessage());
        }
      })
    );
    dialog.show();
  }
}
