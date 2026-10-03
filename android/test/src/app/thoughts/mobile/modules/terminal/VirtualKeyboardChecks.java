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
    capture(test, "terminal-builtin");
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
