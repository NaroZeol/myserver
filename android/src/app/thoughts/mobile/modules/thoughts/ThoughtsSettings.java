package app.thoughts.mobile.modules.thoughts;

import android.app.AlertDialog;
import android.widget.LinearLayout;

/** Preferences and data actions owned by the thoughts module. */
final class ThoughtsSettings extends ThoughtsUi {

  ThoughtsSettings(ThoughtsHost host) {
    super(host);
  }

  void render(LinearLayout surface) {
    int count = 0;
    try {
      count = store.entries().size();
    } catch (Exception ignored) {}
    LinearLayout syncSettings = card(surface, "想法同步", "");
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
    LinearLayout data = card(surface, "想法数据", "");
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
        .setMessage(ThoughtsSyncLog.last(activity))
        .setPositiveButton("关闭", null)
        .show()
    );
    setting(data, "清除本机记录与草稿", "", () -> host.disconnect());
  }
}
