package app.thoughts.mobile.modules.terminal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;

/** Local key surface; text uses the same terminal modifier/encoding path as physical input. */
final class VirtualKeyboard extends LinearLayout {

  interface Actions {
    void text(String value);
    void special(String value);
    void modifier(String name, boolean lock);
  }

  private final Actions actions;
  private final Handler handler = new Handler();
  private boolean symbols, moreSymbols, function, shifted, caps, repeated;
  private int controlState, altState;
  private Button controlKey, altKey;
  private Runnable repeat;

  VirtualKeyboard(Context context, Actions actions) {
    super(context);
    this.actions = actions;
    setOrientation(VERTICAL);
    setPadding(dp(3), dp(3), dp(3), dp(3));
    setBackgroundColor(0xff222326);
    setContentDescription("内置终端键盘");
    render();
  }

  private void render() {
    stopRepeating();
    removeAllViews();
    controlKey = altKey = null;
    if (function) {
      row(new String[] { "F1", "F2", "F3", "F4", "F5", "F6" });
      row(new String[] { "F7", "F8", "F9", "F10", "F11", "F12" });
      row(new String[] { "Esc", "Tab", "Ins", "Del", "Home", "End" });
      row(new String[] { "PgUp", "PgDn", "⇧", "⌫" });
    } else if (symbols) {
      if (moreSymbols) {
        row(new String[] { "!", "@", "#", "$", "%", "^", "&", "*", "(", ")" });
        row(new String[] { "[", "]", "{", "}", "<", ">", "_", "|", "\\", "~" });
        row(new String[] {
          "123",
          "`",
          "\"",
          "'",
          ":",
          ";",
          "+",
          "=",
          "⇧",
          "⌫",
        });
      } else {
        row(new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" });
        row(new String[] { "-", "/", ":", ";", "(", ")", "$", "&", "@", "\"" });
        row(new String[] {
          "#+=",
          ".",
          ",",
          "?",
          "!",
          "'",
          "+",
          "=",
          "⇧",
          "⌫",
        });
      }
      row(new String[] { "Esc", "Tab", "Home", "End", "PgUp", "PgDn" });
    } else {
      row(new String[] {
        "Esc",
        "1",
        "2",
        "3",
        "4",
        "5",
        "6",
        "7",
        "8",
        "9",
        "0",
        "-",
        "=",
        "⌫",
      });
      row(letters("Tab", "qwertyuiop", "[", "]", "\\"));
      row(letters("Caps", "asdfghjkl", ";", "'", "↵"));
      row(letters("⇧", "zxcvbnm", ",", ".", "/", "↑", ""));
    }
    // Desktop modifiers, space, Enter and an inverted-T cursor cluster stay put.
    row(new String[] {
      "Ctrl",
      "Fn",
      "Alt",
      "空格",
      symbols || function ? "ABC" : "123",
      "↵",
      "←",
      "↓",
      "→",
    });
  }

  private String letter(char value) {
    return shifted != caps
      ? String.valueOf(Character.toUpperCase(value))
      : String.valueOf(value);
  }

  private String[] letters(String first, String value, String... last) {
    String[] result = new String[value.length() + last.length + 1];
    result[0] = first;
    for (int i = 0; i < value.length(); i++) result[i + 1] = letter(
      value.charAt(i)
    );
    System.arraycopy(last, 0, result, value.length() + 1, last.length);
    return result;
  }

  private void row(String[] labels) {
    LinearLayout row = new LinearLayout(getContext());
    for (int index = 0; index < labels.length; index++) {
      String label = labels[index];
      float weight = weight(labels, index);
      if (label.isEmpty()) {
        row.addView(
          new android.view.View(getContext()),
          new LinearLayout.LayoutParams(0, -1, weight)
        );
        continue;
      }
      Button key = new Button(getContext());
      key.setText(label.equals("Fn") && function && shifted ? "Fn ⇧" : label);
      key.setAllCaps(false);
      key.setTextSize(label.length() > 1 ? 11 : 16);
      key.setTypeface(android.graphics.Typeface.MONOSPACE);
      key.setTextColor(0xffe8e6df);
      key.setPadding(0, 0, 0, 0);
      key.setMinHeight(0);
      key.setMinimumHeight(0);
      key.setMinWidth(0);
      key.setMinimumWidth(0);
      key.setStateListAnimator(null);
      GradientDrawable background = new GradientDrawable();
      background.setColor(
        (label.startsWith("⇧") && shifted) ||
          (label.equals("Fn") && function) ||
          (label.equals("Caps") && caps) ||
          (label.equals("Ctrl") && controlState != 0) ||
          (label.equals("Alt") && altState != 0)
          ? 0xff63513e
          : label.equals("↵")
            ? 0xff454e57
            : 0xff33353a
      );
      background.setCornerRadius(dp(5));
      key.setBackground(
        new RippleDrawable(ColorStateList.valueOf(0x559c9c9c), background, null)
      );
      key.setContentDescription(
        label.equals("⌫")
          ? "退格"
          : label.equals("↵")
            ? "回车"
            : label.equals("Esc")
              ? "Escape"
              : label.equals("Caps")
                ? "Caps Lock" + (caps ? "，已开启" : "，已关闭")
                : label.startsWith("⇧")
                  ? "Shift，长按锁定大写"
                  : label.equals("Ctrl") || label.equals("Alt")
                    ? label + "，点击单次启用，长按锁定"
                    : label
      );
      key.setTooltipText(key.getContentDescription());
      key.setSelected(
        (label.equals("Fn") && function) ||
          (label.equals("Caps") && caps) ||
          (label.startsWith("⇧") && shifted) ||
          (label.equals("Ctrl") && controlState != 0) ||
          (label.equals("Alt") && altState != 0)
      );
      if (label.equals("Ctrl")) controlKey = key;
      if (label.equals("Alt")) altKey = key;
      key.setOnClickListener(v -> press(label));
      if (label.startsWith("⇧")) key.setOnLongClickListener(v -> {
        caps = !caps;
        shifted = false;
        render();
        return true;
      });
      if (
        label.equals("Ctrl") || label.equals("Alt")
      ) key.setOnLongClickListener(v -> {
        actions.modifier(label.toUpperCase(java.util.Locale.ROOT), true);
        return true;
      });
      if (
        label.equals("⌫") ||
        label.equals("↑") ||
        label.equals("↓") ||
        label.equals("←") ||
        label.equals("→")
      ) key.setOnTouchListener((v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
          stopRepeating();
          repeated = false;
          repeat = new Runnable() {
            public void run() {
              if (repeat != this || !key.isPressed()) return;
              repeated = true;
              if (label.equals("⌫")) actions.special("BACKSPACE");
              else press(label);
              handler.postDelayed(this, 70);
            }
          };
          handler.postDelayed(repeat, ViewConfiguration.getLongPressTimeout());
        } else if (
          event.getActionMasked() == MotionEvent.ACTION_UP ||
          event.getActionMasked() == MotionEvent.ACTION_CANCEL ||
          (event.getActionMasked() == MotionEvent.ACTION_MOVE &&
            (event.getX() < 0 ||
              event.getX() > v.getWidth() ||
              event.getY() < 0 ||
              event.getY() > v.getHeight()))
        ) {
          stopRepeating();
          if (event.getActionMasked() == MotionEvent.ACTION_UP && repeated) {
            key.setPressed(false);
            return true;
          }
        }
        return false;
      });
      LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
        0,
        -1,
        weight
      );
      size.setMargins(dp(1), dp(2), dp(1), dp(2));
      row.addView(key, size);
    }
    addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private float weight(String[] labels, int index) {
    String label = labels[index];
    if (labels.length == 14 && labels[0].equals("Esc")) return label.equals("⌫")
      ? 1.5f
      : 1f;
    if (labels.length == 14 && labels[0].equals("Tab")) return label.equals(
      "Tab"
    )
      ? 1.5f
      : 1f;
    if (labels.length == 13 && labels[0].equals("Caps")) return label.equals(
      "Caps"
    )
      ? 1.5f
      : label.equals("↵")
        ? 2f
        : 1f;
    if (labels.length == 13 && labels[0].equals("⇧")) return label.equals("⇧")
      ? 1.5f
      : label.isEmpty()
        ? 2f
        : 1f;
    if (labels.length == 9 && labels[0].equals("Ctrl")) {
      if (label.equals("Ctrl") || label.equals("Alt")) return 1.5f;
      if (label.equals("空格")) return 5f;
      if (label.equals("↵")) return 1.5f;
    }
    return 1f;
  }

  void modifiers(int control, int alternate) {
    controlState = control;
    altState = alternate;
    updateModifier(controlKey, "Ctrl", control);
    updateModifier(altKey, "Alt", alternate);
  }

  void refreshLayout() {
    render();
  }

  private void updateModifier(Button button, String label, int state) {
    if (button == null) return;
    button.setSelected(state != 0);
    button.setTextColor(state == 0 ? 0xffe8e6df : 0xffe6b68c);
    button.setContentDescription(
      label +
        "，点击单次启用，长按锁定" +
        (state == 2 ? "，已锁定" : state == 1 ? "，已启用" : "")
    );
    button.setTooltipText(button.getContentDescription());
  }

  private void press(String label) {
    if (label.equals("Fn")) {
      function = !function;
      render();
      return;
    }
    if (label.equals("123") || label.equals("ABC") || label.equals("#+=")) {
      symbols = !label.equals("ABC");
      moreSymbols = label.equals("#+=");
      function = false;
      render();
      return;
    }
    if (label.startsWith("⇧")) {
      shifted = !shifted;
      render();
      return;
    }
    if (label.equals("Caps")) {
      caps = !caps;
      render();
      return;
    }
    if (label.equals("Ctrl") || label.equals("Alt")) {
      actions.modifier(label.toUpperCase(java.util.Locale.ROOT), false);
      return;
    }
    if (label.equals("⌫")) sendSpecial("BACKSPACE");
    else if (label.equals("↵")) sendSpecial("ENTER");
    else if (label.equals("Esc")) sendSpecial("ESC");
    else if (label.equals("Tab")) sendSpecial("TAB");
    else if (label.equals("↑")) sendSpecial("UP");
    else if (label.equals("↓")) sendSpecial("DOWN");
    else if (label.equals("←")) sendSpecial("LEFT");
    else if (label.equals("→")) sendSpecial("RIGHT");
    else if (label.matches("F([1-9]|1[0-2])")) sendSpecial(label);
    else if (label.equals("Ins")) sendSpecial("INSERT");
    else if (label.equals("Del")) sendSpecial("DELETE");
    else if (
      label.equals("Home") ||
      label.equals("End") ||
      label.equals("PgUp") ||
      label.equals("PgDn")
    ) sendSpecial(label.toUpperCase(java.util.Locale.ROOT));
    else {
      actions.text(label.equals("空格") ? " " : shiftedCharacter(label));
      consumeShift();
    }
  }

  private String shiftedCharacter(String value) {
    if (!shifted || value.length() != 1) return value;
    String lower = "1234567890-=[]\\;',./`";
    String upper = "!@#$%^&*()_+{}|:\"<>?~";
    int index = lower.indexOf(value);
    return index < 0 ? value : String.valueOf(upper.charAt(index));
  }

  /** Shared by this keyboard and the always-visible terminal key deck. */
  void sendSpecial(String name) {
    if (name.length() == 1) {
      actions.text(shiftedCharacter(name));
      consumeShift();
    } else if (name.equals("BACKSPACE")) {
      // Editing a typo must not consume a pending Shift, including during repeat.
      actions.special(name);
    } else {
      actions.special((shifted ? "SHIFT+" : "") + name);
      consumeShift();
    }
  }

  private void consumeShift() {
    if (shifted) {
      shifted = false;
      render();
    }
  }

  void stopRepeating() {
    if (repeat != null) handler.removeCallbacks(repeat);
    repeat = null;
  }

  protected void onDetachedFromWindow() {
    stopRepeating();
    super.onDetachedFromWindow();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
