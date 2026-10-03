package app.thoughts.mobile.shell;

import android.app.AlertDialog;
import android.widget.LinearLayout;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.modules.server.MonitorSettings;

public final class SettingsFeature extends Ui implements Feature {

  public SettingsFeature(Feature.Host host) {
    super(host);
  }

  public String id() {
    return "settings";
  }

  public String label() {
    return "设置";
  }

  public void render(LinearLayout surface) {
    LinearLayout monitoring = card(surface, "监控", "");
    setting(monitoring, "刷新间隔", MonitorSettings.label(activity), () ->
      MonitorSettings.show(activity, () -> host.redraw())
    );
    monitoring.addView(
      text("仅在服务器页可见时自动刷新，离开页面或锁屏后暂停。", 12, MUTED)
    );
    host.renderFeatureSettings(surface);
    LinearLayout about = card(surface, "关于", "");
    String version = "";
    try {
      version = activity
        .getPackageManager()
        .getPackageInfo(activity.getPackageName(), 0)
        .versionName;
    } catch (Exception ignored) {}
    setting(about, "myserver", version, null);
    setting(about, "开源许可", "", () -> showLicenses());
    space(surface, 32);
    android.widget.TextView note = text("你的服务器，随身可用。", 12, MUTED);
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
