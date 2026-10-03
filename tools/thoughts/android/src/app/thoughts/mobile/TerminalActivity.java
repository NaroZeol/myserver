package app.thoughts.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Full-height terminal workspace. Rotation keeps the same PTY and emulator. */
public final class TerminalActivity extends Activity {

  private static final int BACKGROUND = 0xff1c1d20,
    FOREGROUND = 0xffe8e6df,
    MUTED = 0xffaaa69f;
  private ServerProfile profile;
  private TerminalSurface terminal;
  private volatile TerminalSession connection;
  private TextView feedback, placeholder;
  private Button connect, control;
  private boolean busy, ctrl;
  private int fontSize;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    profile = ServerProfile.load(this);
    if (profile == null) {
      finish();
      return;
    }
    fontSize = getSharedPreferences("terminal_ui", 0).getInt("font", 14);
    getWindow().setStatusBarColor(BACKGROUND);
    getWindow().setNavigationBarColor(BACKGROUND);
    getWindow().getDecorView().setSystemUiVisibility(0);
    // Terminal output can include passwords or tokens; do not expose it in recent-app snapshots.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BACKGROUND);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      view.setPadding(
        insets.getSystemWindowInsetLeft(),
        insets.getSystemWindowInsetTop(),
        insets.getSystemWindowInsetRight(),
        insets.getSystemWindowInsetBottom()
      );
      return insets;
    });
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(button("返回", () -> onBackPressed()));
    TextView name = text(profile.name, 15, FOREGROUND);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    connect = button("连接", () -> {
      if (busy) disconnectPrompt();
      else login(false);
    });
    connect.setEnabled(false);
    header.addView(connect);
    header.addView(button("更多", () -> menu()));
    root.addView(header);
    feedback = text("正在准备终端…", 12, MUTED);
    feedback.setPadding(dp(12), dp(4), dp(12), dp(10));
    feedback.setAccessibilityLiveRegion(
      android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    );
    root.addView(feedback);
    FrameLayout frame = new FrameLayout(this);
    terminal = new TerminalSurface(
      this,
      new TerminalSurface.Listener() {
        public void ready() {
          connect.setEnabled(true);
          terminal.evaluateJavascript(
            "TerminalUI.font(" + fontSize + ")",
            null
          );
          feedback.setText("未连接 · " + profile.address());
        }

        public void input(byte[] bytes) {
          TerminalSession active = connection;
          if (
            active != null && active.isConnected() && !active.send(bytes)
          ) runOnUiThread(() ->
            feedback.setText("输入未发送，请等待连接恢复后重试")
          );
        }

        public void resize(int cols, int rows) {
          TerminalSession active = connection;
          if (active != null) active.resize(cols, rows);
        }

        public void controlReleased() {
          ctrl = false;
          updateControl();
        }

        public void failed() {
          if (connection != null) connection.close();
          busy = false;
          connect.setEnabled(false);
          feedback.setText(
            "终端组件未能加载，请更新 Android System WebView 后重新打开"
          );
        }
      }
    );
    frame.addView(terminal, new FrameLayout.LayoutParams(-1, -1));
    placeholder = text(
      "连接你的服务器\n\n点击右上角「连接」开始会话",
      15,
      MUTED
    );
    placeholder.setGravity(Gravity.CENTER);
    placeholder.setPadding(dp(24), dp(24), dp(24), dp(24));
    frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    HorizontalScrollView keys = new HorizontalScrollView(this);
    keys.setHorizontalScrollBarEnabled(false);
    LinearLayout row = new LinearLayout(this);
    row.addView(button("键盘", () -> keyboard()));
    row.addView(button("Esc", () -> key("\u001b")));
    row.addView(button("Tab", () -> key("\t")));
    control = button("Ctrl", () -> {
      ctrl = !ctrl;
      updateControl();
      terminal.evaluateJavascript("TerminalUI.control(" + ctrl + ")", null);
      keyboard();
    });
    row.addView(control);
    row.addView(button("^C", () -> key("\u0003")));
    row.addView(button("↑", () -> key("\u001b[A")));
    row.addView(button("↓", () -> key("\u001b[B")));
    row.addView(button("←", () -> key("\u001b[D")));
    row.addView(button("→", () -> key("\u001b[C")));
    row.addView(button("粘贴", () -> paste()));
    row.addView(button("Enter", () -> key("\r")));
    keys.addView(row);
    root.addView(keys);
    setContentView(root);
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(12);
    b.setAllCaps(false);
    b.setTextColor(FOREGROUND);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setPadding(dp(10), 0, dp(10), 0);
    b.setBackgroundColor(Color.TRANSPARENT);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void updateControl() {
    control.setTextColor(ctrl ? 0xffe6b68c : FOREGROUND);
    control.setSelected(ctrl);
  }

  private void key(String value) {
    if (busy) terminal.call("key", value);
  }

  private void keyboard() {
    terminal.requestFocus();
    terminal.call("focus", "");
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(
      terminal,
      InputMethodManager.SHOW_IMPLICIT
    );
  }

  private void login(boolean forcePassword) {
    if (busy || !terminal.loaded) return;
    if (!forcePassword && TerminalAuth.registered(this, profile)) {
      begin(null, false);
      return;
    }
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(8), dp(24), dp(8));
    TextView destination = new TextView(this);
    destination.setText(profile.address());
    form.addView(destination);
    EditText password = new EditText(this);
    password.setHint("SSH 登录密码");
    password.setSingleLine(true);
    password.setInputType(
      android.text.InputType.TYPE_CLASS_TEXT |
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    );
    password.setSaveEnabled(false);
    password.setImportantForAutofill(
      android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    );
    form.addView(password);
    CheckBox remember = new CheckBox(this);
    remember.setText("记住此设备：登记终端专用密钥");
    remember.setTextSize(14);
    remember.setMinHeight(dp(48));
    form.addView(remember);
    TextView note = new TextView(this);
    note.setText(
      "终端拥有该 SSH 账户的命令执行权限。密码只用于本次登录；勾选后独立登记终端密钥，不改变想法同步密钥。"
    );
    note.setTextSize(12);
    form.addView(note);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle("登录终端")
      .setView(scroll)
      .setNegativeButton("取消", null)
      .setPositiveButton("连接", null)
      .create();
    dialog.setOnShowListener(d -> {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        if (password.length() == 0) {
          password.setError("请输入密码");
          return;
        }
        char[] chars = new char[password.length()];
        password.getText().getChars(0, chars.length, chars, 0);
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(
          java.nio.CharBuffer.wrap(chars)
        );
        byte[] secret = new byte[encoded.remaining()];
        encoded.get(secret);
        java.util.Arrays.fill(chars, '\0');
        if (encoded.hasArray()) java.util.Arrays.fill(
          encoded.array(),
          (byte) 0
        );
        password.getText().clear();
        boolean enroll = remember.isChecked();
        dialog.dismiss();
        begin(secret, enroll);
      });
    });
    dialog.setOnDismissListener(d -> password.getText().clear());
    dialog.show();
  }

  void begin(byte[] password, boolean enroll) {
    if (busy || isDestroyed()) {
      if (password != null) java.util.Arrays.fill(password, (byte) 0);
      return;
    }
    busy = true;
    connect.setText("取消");
    feedback.setText(
      enroll ? "正在登录并登记终端密钥…" : "正在连接 " + profile.address()
    );
    placeholder.setVisibility(android.view.View.GONE);
    final TerminalSession[] started = new TerminalSession[1];
    TerminalSession next = new TerminalSession(
      new TerminalSession.Listener() {
        public void connected(boolean registered) {
          runOnUiThread(() -> {
            if (isDestroyed() || connection != started[0]) return;
            if (registered) {
              try {
                TerminalAuth.remember(TerminalActivity.this, profile);
              } catch (Exception e) {
                feedback.setText(e.getMessage());
              }
            }
            connect.setText("断开");
            feedback.setText("已连接 · " + profile.address());
          });
        }

        public void output(byte[] data) throws Exception {
          terminal.writeBlocking(data);
        }

        public void ended(String reason) {
          runOnUiThread(() -> {
            if (isDestroyed() || connection != started[0]) return;
            busy = false;
            ctrl = false;
            updateControl();
            connect.setText("重连");
            feedback.setText(reason);
            terminal.evaluateJavascript("TerminalUI.control(false)", null);
          });
        }
      }
    );
    started[0] = next;
    connection = next;
    next.resize(terminal.columns, terminal.rows);
    next.start(profile, password, enroll);
  }

  private void disconnectPrompt() {
    new AlertDialog.Builder(this)
      .setTitle("断开终端？")
      .setMessage("当前 shell 会话将关闭，依附会话运行的命令可能中止。")
      .setNegativeButton("继续使用", null)
      .setPositiveButton("断开", (d, w) -> disconnect())
      .show();
  }

  void disconnect() {
    if (connection != null) connection.close();
  }

  public void onBackPressed() {
    if (!busy) {
      super.onBackPressed();
      return;
    }
    new AlertDialog.Builder(this)
      .setTitle("结束终端并返回？")
      .setMessage("返回后关闭当前 SSH 会话。")
      .setNegativeButton("留在终端", null)
      .setPositiveButton("结束并返回", (d, w) -> {
        disconnect();
        finish();
      })
      .show();
  }

  protected void onDestroy() {
    disconnect();
    if (terminal != null) terminal.dispose();
    super.onDestroy();
  }

  private void paste() {
    if (connection == null || !connection.isConnected()) {
      feedback.setText("请先连接服务器");
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) getSystemService(
      CLIPBOARD_SERVICE
    );
    ClipData data = clipboard.getPrimaryClip();
    if (data == null || data.getItemCount() == 0) return;
    CharSequence raw = data.getItemAt(0).getText();
    if (raw == null) {
      feedback.setText("剪贴板中没有文字");
      return;
    }
    String value = raw.toString();
    if (value.getBytes(StandardCharsets.UTF_8).length > 60000) {
      feedback.setText("粘贴内容过长，请分段发送");
      return;
    }
    if (
      value.indexOf('\n') >= 0 ||
      value.indexOf('\r') >= 0 ||
      value.indexOf('\u001b') >= 0 ||
      value.indexOf('\t') >= 0
    ) new AlertDialog.Builder(this)
      .setTitle("粘贴多行或控制字符？")
      .setMessage(
        "内容可能立即执行命令，请确认来源。\n\n" +
          value.substring(0, Math.min(800, value.length()))
      )
      .setNegativeButton("取消", null)
      .setPositiveButton("粘贴", (d, w) -> terminal.call("paste", value))
      .show();
    else terminal.call("paste", value);
  }

  private void menu() {
    new AlertDialog.Builder(this)
      .setTitle("终端选项")
      .setItems(
        new String[] {
          "粘贴",
          "复制选中内容",
          "字号",
          "清除屏幕滚动记录",
          "使用密码重新连接",
        },
        (d, index) -> {
          if (index == 0) paste();
          if (index == 1) terminal.evaluateJavascript(
            "TerminalUI.selection()",
            result -> {
              try {
                String value = new org.json.JSONArray(
                  "[" + result + "]"
                ).getString(0);
                if (value.isEmpty()) {
                  feedback.setText("请先在终端中选择文字");
                  return;
                }
                (
                  (ClipboardManager) getSystemService(CLIPBOARD_SERVICE)
                ).setPrimaryClip(ClipData.newPlainText("终端", value));
              } catch (Exception ignored) {}
            }
          );
          if (index == 2) new AlertDialog.Builder(this)
            .setTitle("终端字号")
            .setSingleChoiceItems(
              new String[] { "小 · 12", "标准 · 14", "大 · 16", "更大 · 18" },
              Math.max(0, Math.min(3, (fontSize - 12) / 2)),
              (a, n) -> {
                fontSize = 12 + n * 2;
                getSharedPreferences("terminal_ui", 0)
                  .edit()
                  .putInt("font", fontSize)
                  .apply();
                terminal.evaluateJavascript(
                  "TerminalUI.font(" + fontSize + ")",
                  null
                );
                a.dismiss();
              }
            )
            .show();
          if (index == 3) terminal.call("clear", "");
          if (index == 4) {
            if (busy) {
              feedback.setText("请先断开当前会话");
            } else login(true);
          }
        }
      )
      .show();
  }
}
