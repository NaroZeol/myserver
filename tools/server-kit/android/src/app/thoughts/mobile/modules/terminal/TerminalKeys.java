package app.thoughts.mobile.modules.terminal;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;

/** Small native key deck. State/encoding belong to the emulator, never to the SSH transport. */
final class TerminalKeys extends LinearLayout {

  interface Actions {
    void key(String name);
    void modifier(String name, boolean lock);
  }

  private final Handler handler = new Handler();
  private final Actions actions;
  private Button ctrl, alt;
  private Runnable repeating;
  private boolean repeated;
  private int controlState, altState;

  TerminalKeys(Context context, Actions actions) {
    super(context);
    this.actions = actions;
    setOrientation(VERTICAL);
    setBackgroundColor(0xff222326);
    layoutKeys(
      getResources().getConfiguration().orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    );
  }

  void layoutKeys(boolean landscape) {
    stopRepeating();
    removeAllViews();
    if (landscape) {
      row(
        new String[] {
          "ESC",
          "TAB",
          "CTRL",
          "ALT",
          "/",
          "−",
          "HOME",
          "←",
          "↓",
          "↑",
          "→",
          "END",
          "PGUP",
          "PGDN",
        },
        new String[] {
          "ESC",
          "TAB",
          "CTRL",
          "ALT",
          "/",
          "-",
          "HOME",
          "LEFT",
          "DOWN",
          "UP",
          "RIGHT",
          "END",
          "PGUP",
          "PGDN",
        }
      );
    } else {
      // Same spatial arrangement as Termux: arrows form an inverted T and paging stays at the right.
      row(
        new String[] { "ESC", "/", "−", "HOME", "↑", "END", "PGUP" },
        new String[] { "ESC", "/", "-", "HOME", "UP", "END", "PGUP" }
      );
      row(
        new String[] { "TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN" },
        new String[] { "TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN" }
      );
    }
    modifiers(controlState, altState);
  }

  private void row(String[] labels, String[] names) {
    LinearLayout row = new LinearLayout(getContext());
    for (int i = 0; i < names.length; i++) {
      final String name = names[i];
      Button key = new Button(getContext());
      key.setText(labels[i]);
      key.setTextSize(11);
      key.setAllCaps(false);
      key.setTypeface(android.graphics.Typeface.MONOSPACE);
      key.setMinWidth(0);
      key.setMinimumWidth(0);
      key.setMinHeight(0);
      key.setMinimumHeight(0);
      key.setPadding(0, 0, 0, 0);
      key.setTextColor(0xffe8e6df);
      key.setBackground(
        new RippleDrawable(
          ColorStateList.valueOf(0x337f7f7f),
          new ColorDrawable(0x00000000),
          null
        )
      );
      key.setContentDescription(description(name));
      key.setOnClickListener(v -> {
        if (name.equals("CTRL") || name.equals("ALT")) actions.modifier(
          name,
          false
        );
        else actions.key(name);
      });
      if (name.equals("CTRL") || name.equals("ALT")) {
        key.setOnLongClickListener(v -> {
          actions.modifier(name, true);
          return true;
        });
        if (name.equals("CTRL")) ctrl = key;
        else alt = key;
      } else if (name.equals("-")) {
        key.setOnLongClickListener(v -> {
          actions.key("|");
          return true;
        });
        key.setContentDescription("减号，长按输入竖线");
      } else if (
        name.equals("UP") ||
        name.equals("DOWN") ||
        name.equals("LEFT") ||
        name.equals("RIGHT") ||
        name.startsWith("PG")
      ) {
        key.setOnTouchListener((v, event) -> {
          int action = event.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN) {
            stopRepeating();
            repeated = false;
            repeating = new Runnable() {
              public void run() {
                if (repeating != this || !key.isPressed()) return;
                repeated = true;
                actions.key(name);
                handler.postDelayed(this, 80);
              }
            };
            handler.postDelayed(
              repeating,
              ViewConfiguration.getLongPressTimeout()
            );
          } else if (
            action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_UP ||
            (action == MotionEvent.ACTION_MOVE &&
              (event.getX() < 0 ||
                event.getX() > v.getWidth() ||
                event.getY() < 0 ||
                event.getY() > v.getHeight()))
          ) {
            stopRepeating();
            if (action == MotionEvent.ACTION_UP && repeated) {
              key.setPressed(false);
              return true;
            }
          }
          return false;
        });
      }
      row.addView(key, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private String description(String name) {
    switch (name) {
      case "UP":
        return "上方向键";
      case "DOWN":
        return "下方向键";
      case "LEFT":
        return "左方向键";
      case "RIGHT":
        return "右方向键";
      case "CTRL":
        return "Ctrl，点击单次启用，长按锁定";
      case "ALT":
        return "Alt，点击单次启用，长按锁定";
      default:
        return name;
    }
  }

  void modifiers(int control, int alternate) {
    controlState = control;
    altState = alternate;
    modifier(ctrl, "CTRL", control);
    modifier(alt, "ALT", alternate);
  }

  private void modifier(Button key, String label, int state) {
    key.setText(label);
    key.setPaintFlags(
      state == 2
        ? key.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
        : key.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG
    );
    key.setSelected(state != 0);
    key.setTextColor(state == 0 ? 0xffe8e6df : 0xffe6b68c);
    key.setBackground(
      new RippleDrawable(
        ColorStateList.valueOf(0x337f7f7f),
        new ColorDrawable(state == 0 ? 0x00000000 : 0xff39332e),
        null
      )
    );
    key.setContentDescription(
      description(label) +
        (state == 2 ? "，已锁定" : state == 1 ? "，已启用" : "")
    );
  }

  void stopRepeating() {
    if (repeating != null) handler.removeCallbacks(repeating);
    repeating = null;
  }

  protected void onDetachedFromWindow() {
    stopRepeating();
    super.onDetachedFromWindow();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
