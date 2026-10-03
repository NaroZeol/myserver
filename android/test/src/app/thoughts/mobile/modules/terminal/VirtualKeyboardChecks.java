package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import app.thoughts.mobile.InteractionChecks;

final class VirtualKeyboardChecks {

  static void run(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    TerminalSession connection
  ) throws Exception {
    test.runOnMainSync(() -> checkLayers(screen));
    checkBackspaceRepeat(test, screen);
    test.runOnMainSync(() -> screen.useKeyboard(true, true));
    TerminalChecks.await(
      () ->
        InteractionChecks.find(
          screen.getWindow().getDecorView(),
          "内置终端键盘"
        ).getVisibility() == View.VISIBLE,
      "Built-in keyboard must open"
    );
    test.runOnMainSync(() ->
      TerminalChecks.check(
        surface.onCreateInputConnection(new EditorInfo()) == null,
        "Built-in keyboard must not invoke the system input method"
      )
    );
    TerminalChecks.check(
      TerminalChecks.js(test, surface, "terminal.textarea.inputMode").equals(
        "\"none\""
      ),
      "WebView must suppress automatic IME popups"
    );
    // Actual native buttons must send a command through the existing SSH session.
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.resetModifiers(); TerminalUI.key('\\u0003')"
    );
    for (char value : "echo keyboard".toCharArray()) {
      String label = value == ' ' ? "空格" : String.valueOf(value);
      test.runOnMainSync(() ->
        InteractionChecks.find(
          screen.getWindow().getDecorView(),
          label
        ).performClick()
      );
    }
    test.runOnMainSync(() ->
      InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "回车"
      ).performClick()
    );
    TerminalChecks.await(() -> {
      try {
        return "true".equals(
          TerminalChecks.js(
            test,
            surface,
            "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).includes('keyboard')"
          )
        );
      } catch (Exception e) {
        return false;
      }
    }, "Virtual keyboard must execute the typed command");
    test.runOnMainSync(() -> {
      View keyboard = InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "内置终端键盘"
      );
      View q = InteractionChecks.find(keyboard, "q"),
        a = InteractionChecks.find(keyboard, "a");
      int[] qPosition = new int[2],
        aPosition = new int[2];
      q.getLocationOnScreen(qPosition);
      a.getLocationOnScreen(aPosition);
      TerminalChecks.check(
        aPosition[0] > qPosition[0],
        "QWERTY home row must be staggered"
      );
      TerminalChecks.check(
        InteractionChecks.find(keyboard, "空格").getWidth() >= q.getWidth() * 3,
        "Space must be easy to hit on narrow screens"
      );
    });
    capture(test, "terminal-builtin");
    test.runOnMainSync(() ->
      InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "Fn"
      ).performClick()
    );
    capture(test, "terminal-function");
    test.runOnMainSync(() ->
      screen.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    );
    TerminalChecks.await(
      () ->
        screen.getResources().getConfiguration().orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE,
      "Landscape keyboard must rotate"
    );
    test.waitForIdleSync();
    test.runOnMainSync(() -> {
      View keyboard = InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "内置终端键盘"
      );
      int[] a = new int[2],
        b = new int[2];
      surface.getLocationOnScreen(a);
      keyboard.getLocationOnScreen(b);
      TerminalChecks.check(
        a[0] + surface.getWidth() <= b[0],
        "Landscape keyboard must sit beside the terminal"
      );
      TerminalChecks.check(
        surface.getHeight() > 0 &&
          keyboard.getHeight() > 0 &&
          connection.isConnected(),
        "Switching layout must preserve visible terminal and connection"
      );
    });
    test.runOnMainSync(() ->
      TerminalChecks.check(
        InteractionChecks.find(
          screen.getWindow().getDecorView(),
          "Fn"
        ).isSelected(),
        "Rotation must preserve the selected function layer"
      )
    );
    capture(test, "terminal-function-landscape");
    test.runOnMainSync(() ->
      InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "Fn"
      ).performClick()
    );
    capture(test, "terminal-builtin-landscape");
    test.runOnMainSync(() -> screen.useKeyboard(false, false));
    test.runOnMainSync(() -> {
      TerminalChecks.check(
        !screen
          .getSharedPreferences("terminal_ui", 0)
          .getBoolean("internal_keyboard", true),
        "Keyboard choice must persist"
      );
      TerminalChecks.check(
        InteractionChecks.find(
          screen.getWindow().getDecorView(),
          "内置终端键盘"
        ).getVisibility() == View.GONE,
        "System input mode must hide the built-in keyboard"
      );
      screen.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    });
  }

  private static void checkLayers(TerminalActivity screen) {
    java.util.List<String> text = new java.util.ArrayList<>();
    java.util.List<String> special = new java.util.ArrayList<>();
    VirtualKeyboard keyboard = new VirtualKeyboard(
      screen,
      new VirtualKeyboard.Actions() {
        public void text(String value) {
          text.add(value);
        }

        public void special(String value) {
          special.add(value);
        }
      }
    );
    tap(keyboard, "Fn");
    for (int i = 1; i <= 12; i++) tap(keyboard, "F" + i);
    for (String name : new String[] {
      "Ins",
      "Del",
      "Home",
      "End",
      "PgUp",
      "PgDn",
      "Shift+Tab",
      "退格",
      "回车",
    })
      tap(keyboard, name);
    TerminalChecks.check(
      special.equals(
        java.util.Arrays.asList(
          "F1",
          "F2",
          "F3",
          "F4",
          "F5",
          "F6",
          "F7",
          "F8",
          "F9",
          "F10",
          "F11",
          "F12",
          "INSERT",
          "DELETE",
          "HOME",
          "END",
          "PGUP",
          "PGDN",
          "BACKTAB",
          "BACKSPACE",
          "ENTER"
        )
      ),
      "Function and editing keys must use the special-key encoder"
    );
    TerminalChecks.check(
      text.isEmpty(),
      "Function keys must never type their labels"
    );
    tap(keyboard, "Fn");
    tap(keyboard, "Shift，长按锁定大写");
    tap(keyboard, "A");
    tap(keyboard, "a");
    TerminalChecks.check(
      text.equals(java.util.Arrays.asList("A", "a")),
      "Shift must apply to one letter"
    );
    text.clear();
    special.clear();
    tap(keyboard, "Shift，长按锁定大写");
    tap(keyboard, "退格");
    tap(keyboard, "A");
    tap(keyboard, "a");
    TerminalChecks.check(
      special.equals(java.util.Arrays.asList("BACKSPACE")) &&
        text.equals(java.util.Arrays.asList("A", "a")),
      "Backspace must preserve one-shot Shift until a character is typed"
    );
    special.clear();
    tap(keyboard, "Shift，长按锁定大写");
    tap(keyboard, "Fn");
    TerminalChecks.check(
      ((android.widget.Button) InteractionChecks.find(keyboard, "Fn"))
        .getText()
        .toString()
        .equals("Fn ⇧"),
      "Function layer must show pending Shift"
    );
    tap(keyboard, "F1");
    TerminalChecks.check(
      ((android.widget.Button) InteractionChecks.find(keyboard, "Fn"))
        .getText()
        .toString()
        .equals("Fn"),
      "Function layer must clear the consumed Shift indicator"
    );
    tap(keyboard, "F1");
    tap(keyboard, "Fn");
    tap(keyboard, "Shift，长按锁定大写");
    keyboard.sendSpecial("UP");
    keyboard.sendSpecial("UP");
    TerminalChecks.check(
      special.equals(
        java.util.Arrays.asList("SHIFT+F1", "F1", "SHIFT+UP", "UP")
      ),
      "One-shot Shift must modify function keys and the external arrow deck"
    );
    text.clear();
    tap(keyboard, "Shift，长按锁定大写");
    keyboard.sendSpecial("/");
    keyboard.sendSpecial("/");
    tap(keyboard, "Shift，长按锁定大写");
    keyboard.sendSpecial("-");
    TerminalChecks.check(
      text.equals(java.util.Arrays.asList("?", "/", "_")),
      "External punctuation must produce shifted characters, never key-name prefixes"
    );
    text.clear();
    special.clear();
    InteractionChecks.find(keyboard, "Shift，长按锁定大写").performLongClick();
    tap(keyboard, "A");
    tap(keyboard, "A");
    tap(keyboard, "Fn");
    tap(keyboard, "F1");
    tap(keyboard, "Fn");
    tap(keyboard, "A");
    tap(keyboard, "Shift，长按锁定大写");
    tap(keyboard, "a");
    TerminalChecks.check(
      text.equals(java.util.Arrays.asList("A", "A", "A", "a")) &&
        special.equals(java.util.Arrays.asList("F1")),
      "Caps Lock must persist across layers and must not modify function keys"
    );
    text.clear();
    tap(keyboard, "123");
    collectCharacters(keyboard, text);
    tap(keyboard, "#+=");
    collectCharacters(keyboard, text);
    for (char c = 33; c < 127; c++) {
      if (!Character.isLetter(c)) TerminalChecks.check(
        text.contains(String.valueOf(c)),
        "Missing ASCII symbol: " + c
      );
    }
    tap(keyboard, "Fn");
    tap(keyboard, "Fn");
    TerminalChecks.check(
      InteractionChecks.find(keyboard, "~") != null,
      "Fn must return to the previous symbol layer"
    );
    tap(keyboard, "ABC");
    tap(keyboard, "q");
    TerminalChecks.check(
      text.get(text.size() - 1).equals("q"),
      "ABC must return to letters"
    );
  }

  private static void checkBackspaceRepeat(
    Instrumentation test,
    TerminalActivity screen
  ) throws Exception {
    java.util.concurrent.atomic.AtomicInteger backspaces =
      new java.util.concurrent.atomic.AtomicInteger();
    java.util.List<String> text = new java.util.ArrayList<>();
    VirtualKeyboard[] keyboard = new VirtualKeyboard[1];
    View[] key = new View[1];
    long down = android.os.SystemClock.uptimeMillis();
    test.runOnMainSync(() -> {
      keyboard[0] = new VirtualKeyboard(
        screen,
        new VirtualKeyboard.Actions() {
          public void text(String value) {
            text.add(value);
          }

          public void special(String value) {
            TerminalChecks.check(
              value.equals("BACKSPACE"),
              "Repeat must send backspace"
            );
            backspaces.incrementAndGet();
          }
        }
      );
      tap(keyboard[0], "Shift，长按锁定大写");
      keyboard[0].measure(
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
      );
      keyboard[0].layout(0, 0, 480, 240);
      key[0] = InteractionChecks.find(keyboard[0], "退格");
      touch(key[0], android.view.MotionEvent.ACTION_DOWN, down);
    });
    try {
      TerminalChecks.await(
        () -> backspaces.get() >= 2,
        "Holding backspace must repeat"
      );
      test.runOnMainSync(() ->
        touch(key[0], android.view.MotionEvent.ACTION_CANCEL, down)
      );
      int count = backspaces.get();
      Thread.sleep(200);
      TerminalChecks.check(
        backspaces.get() == count,
        "Canceled backspace must stop repeating"
      );
      test.runOnMainSync(() -> {
        tap(keyboard[0], "A");
        tap(keyboard[0], "a");
        TerminalChecks.check(
          text.equals(java.util.Arrays.asList("A", "a")),
          "Repeated backspace must preserve one-shot Shift just like a tap"
        );
      });
    } finally {
      test.runOnMainSync(() -> keyboard[0].stopRepeating());
    }
  }

  private static void touch(View key, int action, long down) {
    android.view.MotionEvent event = android.view.MotionEvent.obtain(
      down,
      android.os.SystemClock.uptimeMillis(),
      action,
      key.getWidth() / 2f,
      key.getHeight() / 2f,
      0
    );
    key.dispatchTouchEvent(event);
    event.recycle();
  }

  private static void tap(View keyboard, String label) {
    View key = InteractionChecks.find(keyboard, label);
    TerminalChecks.check(key != null, "Missing key: " + label);
    key.performClick();
  }

  private static void collectCharacters(
    android.view.ViewGroup group,
    java.util.List<String> text
  ) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof android.widget.Button) {
        String label = ((android.widget.Button) child).getText().toString();
        if (label.length() == 1 && label.charAt(0) < 127) child.performClick();
      } else if (child instanceof android.view.ViewGroup) collectCharacters(
        (android.view.ViewGroup) child,
        text
      );
    }
  }

  private static void capture(Instrumentation test, String name)
    throws Exception {
    test.waitForIdleSync();
    Thread.sleep(250);
    android.graphics.Bitmap picture = test.getUiAutomation().takeScreenshot();
    java.io.File directory = new java.io.File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    directory.mkdirs();
    try (
      java.io.OutputStream out = new java.io.FileOutputStream(
        new java.io.File(directory, name + ".png")
      )
    ) {
      picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    }
    picture.recycle();
  }
}
