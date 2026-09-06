package app.thoughts.mobile;

import android.app.AlertDialog;
import android.widget.LinearLayout;

final class SettingsFeature extends Ui implements Feature {

  SettingsFeature(Feature.Host host) {
    super(host);
  }

  public String id() {
    return "settings";
  }

  public String label() {
    return "设置";
  }

  public void render(LinearLayout surface) {
    int count = 0;
    try {
      count = store.entries().size();
    } catch (Exception ignored) {}
    LinearLayout syncSettings = card(surface, "同步", "");
    android.widget.Switch automatic = new android.widget.Switch(activity);
    automatic.setText("自动同步");
    automatic.setTextSize(15);
    automatic.setTextColor(INK);
    automatic.setMinHeight(dp(52));
    automatic.setChecked(host.automaticSync());
    automatic.setOnCheckedChangeListener((button, checked) ->
      host.setAutomaticSync(checked)
    );
    syncSettings.addView(automatic);
    syncSettings.addView(
      text(
        host.automaticSync()
          ? "保存、打开 App 或恢复网络时自动同步。"
          : "只保存到本机，点击同步后统一发布。",
        12,
        MUTED
      )
    );
    LinearLayout data = card(surface, "数据", "");
    setting(data, "本机记录", count + " 条", null);
    setting(data, "导出记录与草稿", "", () -> host.export(false));
    if (account.isVerified() && account.can("thoughts")) setting(
      data,
      "导出服务器历史",
      "",
      () -> host.export(true)
    );
    setting(data, "同步记录", "", () ->
      new AlertDialog.Builder(activity)
        .setTitle("最近同步")
        .setMessage(account.lastSync())
        .setPositiveButton("关闭", null)
        .show()
    );
    LinearLayout device = card(surface, "设备", "");
    setting(device, "断开并清除本机记录", "", () -> host.disconnect());
    LinearLayout about = card(surface, "关于", "");
    setting(about, "想法", "1.4.0", null);
    setting(about, "开源许可", "", () -> showLicenses());
    space(surface, 32);
    android.widget.TextView note = text("一些想法，一点记录。", 12, MUTED);
    note.setGravity(android.view.Gravity.CENTER);
    surface.addView(note);
  }

  private void showLicenses() {
    try (
      java.io.InputStream input = activity
        .getAssets()
        .open("THIRD_PARTY_NOTICES.txt")
    ) {
      java.io.ByteArrayOutputStream output =
        new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      android.widget.TextView content = text(output.toString("UTF-8"), 13, INK);
      content.setPadding(dp(24), dp(16), dp(24), dp(16));
      content.setTextIsSelectable(true);
      android.widget.ScrollView scroll = new android.widget.ScrollView(
        activity
      );
      scroll.addView(content);
      new AlertDialog.Builder(activity)
        .setTitle("开源组件与许可")
        .setView(scroll)
        .setPositiveButton("关闭", null)
        .show();
    } catch (Exception e) {
      status("暂时无法读取许可文件");
    }
  }
}
