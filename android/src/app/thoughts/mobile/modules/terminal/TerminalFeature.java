package app.thoughts.mobile.modules.terminal;

import android.content.Intent;
import android.widget.LinearLayout;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.core.connection.ServerConfiguration;
import app.thoughts.mobile.core.connection.ServerProfile;

public final class TerminalFeature extends Ui implements Feature {

  public TerminalFeature(Feature.Host host) {
    super(host);
  }

  public String id() {
    return "terminal";
  }

  public String label() {
    return "终端";
  }

  public void render(LinearLayout surface) {
    space(surface, 18);
    surface.addView(text("SSH 连接", 11, MUTED));
    space(surface, 20);
    ServerProfile profile = host.serverProfile();
    if (profile == null) {
      surface.addView(text("连接服务器", 24, INK));
      space(surface, 16);
      surface.addView(
        text("先配置服务器地址与账户，并核对服务器身份。", 14, MUTED)
      );
      space(surface, 28);
      surface.addView(
        button(
          "配置服务器",
          () -> {
            new ServerConfiguration(host).show();
          },
          true
        )
      );
      return;
    }
    surface.addView(text(profile.name, 24, INK));
    space(surface, 8);
    surface.addView(text(profile.address(), 13, MUTED));
    space(surface, 28);
    surface.addView(
      button(
        "打开终端",
        () ->
          activity.startActivity(new Intent(activity, TerminalActivity.class)),
        true
      )
    );
    space(surface, 28);
    divider(surface);
    space(surface, 22);
    surface.addView(text("实时输入与输出", 15, INK));
    space(surface, 8);
    surface.addView(
      text("支持方向键、Tab 补全、Ctrl 组合键与横屏。", 13, MUTED)
    );
    space(surface, 22);
    surface.addView(text("独立登录", 15, INK));
    space(surface, 8);
    surface.addView(
      text("使用服务器密码，或登记终端专用密钥。无需安装想法服务。", 13, MUTED)
    );
  }
}
