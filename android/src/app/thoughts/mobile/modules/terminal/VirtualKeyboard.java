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
    void systemInput();
  }

  private final Actions actions;
  private final Handler handler = new Handler();
  private boolean symbols, shifted, caps, repeated;
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
    if (symbols) {
      row(new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" });
      row(new String[] { "!", "@", "#", "$", "%", "^", "&", "*", "(", ")" });
      row(new String[] { "[", "]", "{", "}", "<", ">", "\\", "|", "~", "⌫" });
      row(new String[] { "ABC", "`", "'", "\"", "空格", ":", ";", "=", "↵" });
    } else {
      row(letters("qwertyuiop"));
      row(letters("asdfghjkl"));
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
      row(new String[] { "123", "系统", "/", "-", "空格", ",", ".", "↵" });
    }
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
    LinearLayout row = new LinearLayout(getContext());
    for (String label : labels) {
      Button key = new Button(getContext());
      key.setText(label);
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
        label.startsWith("⇧") && (shifted || caps) ? 0xff63513e : 0xff33353a
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
            : label.startsWith("⇧")
              ? "Shift，长按锁定大写"
              : label
      );
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
        label.equals("空格") ? 2.3f : label.equals("↵") ? 1.4f : 1f
      );
      size.setMargins(dp(2), dp(2), dp(2), dp(2));
      row.addView(key, size);
    }
    addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void press(String label) {
    if (label.equals("123") || label.equals("ABC")) {
      symbols = !symbols;
      render();
      return;
    }
    if (label.startsWith("⇧")) {
      if (caps) caps = false;
      else shifted = !shifted;
      render();
      return;
    }
    if (label.equals("系统")) {
      actions.systemInput();
      return;
    }
    if (label.equals("⌫")) actions.special("BACKSPACE");
    else if (label.equals("↵")) actions.special("ENTER");
    else actions.text(label.equals("空格") ? " " : label);
    if (shifted && !caps) {
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
