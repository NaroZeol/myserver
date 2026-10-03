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
  }

  private final Actions actions;
  private final Handler handler = new Handler();
  private boolean symbols, moreSymbols, function, shifted, caps, repeated;
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
    if (function) {
      row(new String[] { "F1", "F2", "F3", "F4", "F5", "F6" });
      row(new String[] { "F7", "F8", "F9", "F10", "F11", "F12" });
      row(new String[] { "Ins", "Del", "Home", "End", "PgUp", "PgDn" });
    } else if (symbols) {
      if (moreSymbols) {
        row(new String[] { "!", "@", "#", "$", "%", "^", "&", "*", "(", ")" });
        row(new String[] { "[", "]", "{", "}", "<", ">", "_", "|", "\\", "~" });
        row(new String[] { "123", "`", "\"", "'", ":", ";", "+", "=", "⌫" });
      } else {
        row(new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" });
        row(new String[] { "-", "/", ":", ";", "(", ")", "$", "&", "@", "\"" });
        row(new String[] { "#+=", ".", ",", "?", "!", "'", "+", "=", "⌫" });
      }
    } else {
      row(letters("qwertyuiop"));
      row(letters("asdfghjkl"), 0.5f);
      row(new String[] {
        caps ? "⇧ 锁" : "⇧",
        letter('z'),
        letter('x'),
        letter('c'),
        letter('v'),
        letter('b'),
        letter('n'),
        letter('m'),
        "⌫",
      });
    }
    // Stable bottom row across all layers; room for space and Enter on narrow screens.
    row(new String[] {
      "Fn",
      symbols || function ? "ABC" : "123",
      function ? "⇤" : "/",
      "空格",
      function ? "⌫" : ".",
      "↵",
    });
  }

  private String letter(char value) {
    return shifted || caps
      ? String.valueOf(Character.toUpperCase(value))
      : String.valueOf(value);
  }

  private String[] letters(String value) {
    String[] result = new String[value.length()];
    for (int i = 0; i < result.length; i++) result[i] = letter(value.charAt(i));
    return result;
  }

  private void row(String[] labels) {
    row(labels, 0);
  }

  private void spacer(LinearLayout row, float weight) {
    if (weight > 0) row.addView(
      new android.view.View(getContext()),
      new LinearLayout.LayoutParams(0, 1, weight)
    );
  }

  private void row(String[] labels, float inset) {
    LinearLayout row = new LinearLayout(getContext());
    spacer(row, inset);
    for (String label : labels) {
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
        (label.startsWith("⇧") && (shifted || caps)) ||
          (label.equals("Fn") && function)
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
            : label.equals("⇤")
              ? "Shift+Tab"
              : label.startsWith("⇧")
                ? "Shift，长按锁定大写"
                : label
      );
      key.setSelected(label.equals("Fn") && function);
      key.setOnClickListener(v -> press(label));
      if (label.startsWith("⇧")) key.setOnLongClickListener(v -> {
        caps = !caps;
        shifted = false;
        render();
        return true;
      });
      if (label.equals("⌫")) key.setOnTouchListener((v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
          stopRepeating();
          repeated = false;
          repeat = new Runnable() {
            public void run() {
              if (repeat != this || !key.isPressed()) return;
              repeated = true;
              actions.special("BACKSPACE");
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
        label.equals("空格")
          ? 3.5f
          : label.equals("↵")
            ? 1.5f
            : labels.length == 9 &&
                (label.startsWith("⇧") ||
                  label.equals("⌫") ||
                  label.equals("#+=") ||
                  label.equals("123"))
              ? 1.5f
              : 1f
      );
      size.setMargins(dp(2), dp(2), dp(2), dp(2));
      row.addView(key, size);
    }
    spacer(row, inset);
    addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
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
      if (caps) caps = false;
      else shifted = !shifted;
      render();
      return;
    }
    if (label.equals("⌫")) sendSpecial("BACKSPACE");
    else if (label.equals("↵")) sendSpecial("ENTER");
    else if (label.matches("F([1-9]|1[0-2])")) sendSpecial(label);
    else if (label.equals("⇤")) sendSpecial("BACKTAB");
    else if (label.equals("Ins")) sendSpecial("INSERT");
    else if (label.equals("Del")) sendSpecial("DELETE");
    else if (
      label.equals("Home") ||
      label.equals("End") ||
      label.equals("PgUp") ||
      label.equals("PgDn")
    ) sendSpecial(label.toUpperCase(java.util.Locale.ROOT));
    else {
      actions.text(label.equals("空格") ? " " : label);
      consumeShift();
    }
  }

  /** Shared by this keyboard and the always-visible terminal key deck. */
  void sendSpecial(String name) {
    if (name.length() == 1) {
      String value = name;
      if (shifted) {
        if (name.equals("/")) value = "?";
        else if (name.equals("-")) value = "_";
      }
      actions.text(value);
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
