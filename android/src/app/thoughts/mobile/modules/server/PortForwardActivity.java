package app.thoughts.mobile.modules.server;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.core.connection.*;
import app.thoughts.mobile.modules.terminal.TerminalActivity;
import org.json.*;

/** Server workspace for one explicit SSH tunnel and a read-only listener list. */
public final class PortForwardActivity extends Activity {

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = new Runnable() {
    public void run() {
      updateState();
      handler.postDelayed(this, 1000);
    }
  };
  private ServerProfile profile;
  private LinearLayout root, statePanel, listPanel;
  private EditText manual;
  private TextView feedback;
  private boolean busy, loading, loadedOnce;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    profile = ServerProfile.load(this);
    if (profile == null) {
      finish();
      return;
    }
    getWindow().setStatusBarColor(Ui.PAPER);
    getWindow().setNavigationBarColor(Ui.PAPER);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
      View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(Ui.PAPER);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(12), dp(20), dp(32));
    scroll.addView(root);
    setContentView(scroll);

    LinearLayout header = row();
    header.addView(Ui.iconButton(this, "back", "返回", this::finish, Ui.INK));
    TextView title = label("端口转发", 22, Ui.INK);
    title.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
    header.addView(Ui.iconButton(this, "sync", "刷新端口列表", this::loadListeners, Ui.INK));
    root.addView(header);
    gap(20);
    statePanel = column();
    root.addView(statePanel);
    gap(24);
    root.addView(label("手动转发", 17, Ui.INK));
    gap(10);
    LinearLayout form = row();
    manual = new EditText(this);
    manual.setSingleLine(true);
    manual.setInputType(InputType.TYPE_CLASS_NUMBER);
    manual.setHint("服务器端口");
    manual.setTextSize(16);
    manual.setTextColor(Ui.INK);
    manual.setPadding(dp(12), 0, dp(12), 0);
    manual.setBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
    form.addView(manual, new LinearLayout.LayoutParams(0, dp(48), 1));
    form.addView(action("转发", () -> {
      int port;
      try {
        port = Integer.parseInt(manual.getText().toString().trim());
      } catch (NumberFormatException e) {
        manual.setError("请输入 1–65535 的端口");
        return;
      }
      if (port < 1 || port > 65535) {
        manual.setError("请输入 1–65535 的端口");
        return;
      }
      choose(port, "127.0.0.1", "端口 " + port);
    }));
    root.addView(form);
    feedback = label("", 13, Ui.ALERT);
    feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(feedback);
    gap(28);
    root.addView(label("服务器监听", 17, Ui.INK));
    gap(6);
    listPanel = column();
    root.addView(listPanel);
    updateState();
  }

  @Override
  public void onResume() {
    super.onResume();
    ServerProfile configured = ServerProfile.load(this);
    if (configured == null || !profile.sameEndpoint(configured) ||
      !profile.knownHost.equals(configured.knownHost)) {
      finish();
      return;
    }
    handler.removeCallbacks(tick);
    handler.post(tick);
    if (!loadedOnce) loadListeners();
  }

  @Override
  public void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  private void loadListeners() {
    if (loading || profile == null) return;
    if (!new DeviceAccount(this).can("system.read")) {
      listPanel.removeAllViews();
      listPanel.addView(label("连接服务后可查看监听端口", 13, Ui.MUTED));
      return;
    }
    loading = true;
    listPanel.removeAllViews();
    listPanel.addView(label("正在读取…", 13, Ui.MUTED));
    new Thread(() -> {
      JSONArray items = null;
      String error = "";
      try {
        items = new SshRpc(profile).request("/system/listeners", "GET", null).getJSONArray("items");
      } catch (Exception e) {
        error = "无法获取端口列表，可手动输入端口";
      }
      JSONArray result = items;
      String message = error;
      runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        loading = false;
        loadedOnce = true;
        renderListeners(result, message);
      });
    }, "server-listeners").start();
  }

  private void renderListeners(JSONArray items, String error) {
    listPanel.removeAllViews();
    if (!error.isEmpty()) {
      listPanel.addView(label(error, 13, Ui.MUTED));
      return;
    }
    if (items == null || items.length() == 0) {
      listPanel.addView(label("暂无可转发的本机 TCP 端口", 13, Ui.MUTED));
      return;
    }
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null) continue;
      int port = item.optInt("port");
      String target = item.optString("target", "");
      if (port < 1 || port > 65535 ||
        !("127.0.0.1".equals(target) || "::1".equals(target))) continue;
      String program = item.optString("program", "");
      if (program.isEmpty() || "null".equals(program)) program = "未知程序";
      String name = program;
      LinearLayout entry = row();
      entry.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout words = column();
      words.addView(label(name, 15, Ui.INK));
      words.addView(label(item.optString("bind", "") + ":" + port, 12, Ui.MUTED));
      entry.addView(words, new LinearLayout.LayoutParams(0, dp(58), 1));
      entry.addView(action("转发", () -> choose(port, target, name)));
      listPanel.addView(entry);
      View line = new View(this);
      line.setBackgroundColor(Ui.LINE);
      listPanel.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }
  }

  private void choose(int port, String target, String label) {
    PortForwardService.State state = PortForwardService.snapshot();
    if ("active".equals(state.phase) || "connecting".equals(state.phase)) {
      if (state.serverPort == port && state.target.equals(target)) return;
      new AlertDialog.Builder(this).setMessage("停止当前转发并切换到 " + port + "？")
        .setNegativeButton("取消", null)
        .setPositiveButton("切换", (dialog, which) -> begin(port, target, label)).show();
    } else begin(port, target, label);
  }

  private void begin(int port, String target, String label) {
    if (busy) return;
    if (!ShellIdentity.registered(this, profile)) {
      new AlertDialog.Builder(this).setMessage("请先在终端启用免密连接")
        .setNegativeButton("取消", null)
        .setPositiveButton("打开终端", (dialog, which) ->
          startActivity(new Intent(this, TerminalActivity.class))).show();
      return;
    }
    busy = true;
    feedback.setText("正在准备 SSH 转发…");
    new Thread(() -> {
      String error = "";
      try {
        ShellIdentity.enableForwarding(this, profile);
      } catch (Exception e) {
        error = e instanceof ConnectionFailure ? e.getMessage() : "无法更新终端密钥权限，请检查服务器连接";
      }
      String message = error;
      runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        feedback.setText(message);
        if (message.isEmpty()) PortForwardService.start(this, profile, port, target, label);
      });
    }, "forward-permission").start();
  }

  private void updateState() {
    if (statePanel == null) return;
    statePanel.removeAllViews();
    PortForwardService.State state = PortForwardService.snapshot();
    if ("active".equals(state.phase)) {
      statePanel.addView(label("正在转发 · " + state.label, 17, Ui.INK));
      String address = "127.0.0.1:" + state.localPort;
      statePanel.addView(label("服务器 " + state.serverPort + "  →  手机 " + state.localPort, 13, Ui.MUTED));
      gap(statePanel, 12);
      LinearLayout actions = row();
      actions.addView(action("浏览器打开", () -> {
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + address + "/")));
        } catch (Exception e) {
          feedback.setText("没有可打开网页的应用");
        }
      }));
      actions.addView(action("复制地址", () -> {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
          .setPrimaryClip(ClipData.newPlainText("SSH 转发地址", address));
        Toast.makeText(this, "已复制本机地址", Toast.LENGTH_SHORT).show();
      }));
      actions.addView(action("停止", () -> PortForwardService.stop(this)));
      statePanel.addView(actions);
    } else if ("connecting".equals(state.phase)) {
      statePanel.addView(label("正在连接 " + state.serverPort + "…", 15, Ui.MUTED));
    } else if ("error".equals(state.phase)) {
      statePanel.addView(label(state.error, 13, Ui.ALERT));
    } else {
      statePanel.addView(label("未开启转发", 15, Ui.MUTED));
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout row() {
    LinearLayout result = new LinearLayout(this);
    result.setOrientation(LinearLayout.HORIZONTAL);
    result.setGravity(Gravity.CENTER_VERTICAL);
    return result;
  }

  private LinearLayout column() {
    LinearLayout result = new LinearLayout(this);
    result.setOrientation(LinearLayout.VERTICAL);
    return result;
  }

  private TextView label(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  private Button action(String value, Runnable click) {
    Button button = new Button(this);
    button.setText(value);
    button.setTextSize(13);
    button.setAllCaps(false);
    button.setTextColor(Ui.INK);
    button.setStateListAnimator(null);
    button.setMinHeight(dp(48));
    button.setMinimumWidth(dp(48));
    GradientDrawable surface = new GradientDrawable();
    surface.setColor(Color.TRANSPARENT);
    surface.setCornerRadius(dp(8));
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x189b5a43), surface, null));
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private void gap(int height) {
    gap(root, height);
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }
}
