package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import app.thoughts.mobile.InteractionChecks;
import org.json.JSONArray;
import org.json.JSONObject;

/** Touch/key deck checks against an actual Android WebView and IME. */
final class TerminalInteractionChecks {

  private static void touch(
    Instrumentation test,
    long down,
    int action,
    float x,
    float y
  ) {
    MotionEvent event = MotionEvent.obtain(
      down,
      SystemClock.uptimeMillis(),
      action,
      x,
      y,
      0
    );
    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    test.getUiAutomation().injectInputEvent(event, true);
    event.recycle();
  }

  static void run(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    int originalRows
  ) throws Exception {
    View root = screen.getWindow().getDecorView();
    test.runOnMainSync(() -> {
      int target = Math.round(
        48 * screen.getResources().getDisplayMetrics().density
      );
      for (String label : new String[] { "返回", "更多" }) {
        View action = InteractionChecks.find(root, label);
        TerminalChecks.check(
          action instanceof android.widget.ImageButton &&
            label.contentEquals(action.getContentDescription()) &&
            label.contentEquals(action.getTooltipText()) &&
            action.getWidth() >= target &&
            action.getHeight() >= target,
          "Terminal navigation icons need accessible names and 48dp touch targets"
        );
      }
      String modeName = screen
        .getSharedPreferences("terminal_ui", 0)
        .getBoolean("internal_keyboard", false)
        ? "内置键盘"
        : "系统输入法";
      View mode = InteractionChecks.find(
        root,
        "切换输入方式，当前：" + modeName
      );
      TerminalChecks.check(
        mode != null &&
          mode.getHeight() >= target &&
          ("切换输入方式，当前：" + modeName).contentEquals(
            mode.getTooltipText()
          ),
        "The input-mode selector must name its current mode and have a 48dp touch target"
      );
    });
    test.runOnMainSync(() ->
      InteractionChecks.find(root, "收起").performClick()
    );
    TerminalChecks.await(() -> {
      try {
        return (
          Integer.parseInt(TerminalChecks.js(test, surface, "terminal.rows")) >=
          originalRows
        );
      } catch (Exception e) {
        return false;
      }
    }, "Keyboard action must also hide IME and restore terminal rows");

    test.runOnMainSync(() ->
      InteractionChecks.find(root, "CTRL").performClick()
    );
    TerminalChecks.js(test, surface, "TerminalUI.key('u')");
    test.waitForIdleSync();
    test.runOnMainSync(() ->
      TerminalChecks.check(
        !InteractionChecks.find(root, "CTRL").isSelected(),
        "Ctrl must release after one key"
      )
    );
    test.runOnMainSync(() ->
      InteractionChecks.find(root, "CTRL").performLongClick()
    );
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.key('e'); TerminalUI.key('a')"
    );
    test.waitForIdleSync();
    test.runOnMainSync(() -> {
      TerminalChecks.check(
        InteractionChecks.find(root, "CTRL").isSelected(),
        "Long press must keep Ctrl locked across keys"
      );
      InteractionChecks.find(root, "CTRL").performClick();
    });
    TerminalChecks.js(
      test,
      surface,
      "window.extraKeyCalls=0; window.originalSpecial=TerminalUI.special; TerminalUI.special=function(name){extraKeyCalls++;originalSpecial(name)}"
    );
    View right = InteractionChecks.find(root, "右方向键");
    int[] pos = new int[2];
    test.runOnMainSync(() -> right.getLocationOnScreen(pos));
    long down = SystemClock.uptimeMillis();
    touch(
      test,
      down,
      MotionEvent.ACTION_DOWN,
      pos[0] + right.getWidth() / 2,
      pos[1] + right.getHeight() / 2
    );
    Thread.sleep(850);
    touch(
      test,
      down,
      MotionEvent.ACTION_CANCEL,
      pos[0] + right.getWidth() / 2,
      pos[1] + right.getHeight() / 2
    );
    int count = Integer.parseInt(
      TerminalChecks.js(test, surface, "extraKeyCalls")
    );
    TerminalChecks.check(count >= 2, "Holding an arrow must repeat");
    Thread.sleep(250);
    TerminalChecks.check(
      count ==
        Integer.parseInt(TerminalChecks.js(test, surface, "extraKeyCalls")),
      "Canceled touch must stop key repeat"
    );
    // Set the sentinel before selection: Android's clipboard preview can cover
    // the toolbar briefly and intercept an immediately following injected tap.
    test.runOnMainSync(() ->
      ((ClipboardManager) screen.getSystemService(Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("fixture", "before_terminal_copy"))
    );
    // Render the fixture through the live PTY, behind all earlier key echo. A
    // direct terminal.write races SSH output and can change the selected cells
    // between the selection assertion and the asynchronous copy callback.
    String fixture =
      "\u0015printf '\\033[2J\\033[Hcopy_me 测试\\r\\nsecond line\\r\\n'\r";
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.special=originalSpecial; TerminalUI.key(" +
        JSONObject.quote(fixture) +
        ")"
    );
    TerminalChecks.await(() -> {
      try {
        return "true".equals(
          TerminalChecks.js(
            test,
            surface,
            "(()=>{const b=terminal.buffer.active;return b.getLine(b.viewportY).translateToString(true)==='copy_me 测试' && b.getLine(b.viewportY+1).translateToString(true)==='second line'})()"
          )
        );
      } catch (Exception e) {
        return false;
      }
    }, "Copy fixture must arrive in order through SSH, not local renderer writes");
    JSONArray point = new JSONArray(
      TerminalChecks.js(
        test,
        surface,
        "(()=>{const r=document.querySelector('.xterm-screen').getBoundingClientRect();return [(r.left+20)/innerWidth,(r.top+8)/innerHeight]})()"
      )
    );
    test.runOnMainSync(() -> surface.getLocationOnScreen(pos));
    float x = pos[0] + (float) point.getDouble(0) * surface.getWidth();
    float y = pos[1] + (float) point.getDouble(1) * surface.getHeight();
    down = SystemClock.uptimeMillis();
    touch(test, down, MotionEvent.ACTION_DOWN, x, y);
    Thread.sleep(750);
    touch(test, down, MotionEvent.ACTION_UP, x, y);
    TerminalChecks.check(
      "\"copy_me\"".equals(
        TerminalChecks.js(test, surface, "TerminalUI.selection()")
      ),
      "Long press on Android must select terminal word"
    );
    TerminalChecks.await(() -> {
      boolean[] shown = { false };
      test.runOnMainSync(() -> {
        View copy = InteractionChecks.find(root, "复制");
        shown[0] = copy != null && copy.isShown() && copy.getWidth() > 0;
      });
      return shown[0];
    }, "The native selection toolbar must be visible before tapping copy");
    TerminalChecks.js(
      test,
      surface,
      "window.copyProbe={reads:[]}; window.copyOriginalSelection=TerminalUI.selection; TerminalUI.selection=function(){const value=copyOriginalSelection();copyProbe.reads.push(value);return value}"
    );
    java.util.concurrent.atomic.AtomicInteger copyTouches =
      new java.util.concurrent.atomic.AtomicInteger();
    test.runOnMainSync(() -> {
      View copy = InteractionChecks.find(root, "复制");
      TerminalChecks.check(
        copy instanceof android.widget.ImageButton &&
          "复制".contentEquals(copy.getTooltipText()) &&
          screen.hasWindowFocus(),
        "Selection copy icon must preserve its accessible action label"
      );
      copy.setOnTouchListener((view, event) -> {
        if (event.getAction() == MotionEvent.ACTION_DOWN) copyTouches.incrementAndGet();
        return false;
      });
      copy.getLocationOnScreen(pos);
      pos[0] += copy.getWidth() / 2;
      pos[1] += copy.getHeight() / 2;
    });
    down = SystemClock.uptimeMillis();
    touch(test, down, MotionEvent.ACTION_DOWN, pos[0], pos[1]);
    touch(test, down, MotionEvent.ACTION_UP, pos[0], pos[1]);
    test.waitForIdleSync();
    java.util.concurrent.atomic.AtomicReference<String> copied =
      new java.util.concurrent.atomic.AtomicReference<>("");
    try {
      TerminalChecks.await(() -> {
        test.runOnMainSync(() -> {
          ClipboardManager clipboard =
            (ClipboardManager) screen.getSystemService(
              Context.CLIPBOARD_SERVICE
            );
          ClipData data = clipboard.getPrimaryClip();
          copied.set(
            data == null || data.getItemCount() == 0
              ? ""
              : String.valueOf(data.getItemAt(0).getText())
          );
        });
        return "copy_me".equals(copied.get());
      }, "Copy callback must publish the selected text to the clipboard");
    } catch (AssertionError failure) {
      String screenshot = "";
      try {
        java.io.File directory = new java.io.File(
          screen.getExternalFilesDir(null), "screenshots");
        directory.mkdirs();
        java.io.File image = new java.io.File(directory, "terminal-copy-failure.png");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(image)) {
          test.getUiAutomation().takeScreenshot().compress(
            android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        screenshot = ", screenshot=" + image.getName();
      } catch (Exception ignored) {}
      throw new AssertionError(
        failure.getMessage() +
          "; " +
          copyDiagnostics(test, screen, surface, copied.get()) +
          ", touchDowns=" + copyTouches.get() + screenshot,
        failure
      );
    } finally {
      TerminalChecks.js(
        test,
        surface,
        "TerminalUI.selection=copyOriginalSelection;delete window.copyOriginalSelection;delete window.copyProbe"
      );
    }
    TerminalChecks.js(test, surface, "TerminalUI.selectVisible()");
    test.runOnMainSync(screen::onBackPressed);
    Thread.sleep(100);
    TerminalChecks.check(
      "\"\"".equals(TerminalChecks.js(test, surface, "TerminalUI.selection()")),
      "Back must dismiss selection without closing session"
    );
    TerminalChecks.check(
      !screen.isFinishing(),
      "Back from selection must stay in terminal"
    );
    chooseMode(test, screen, "内置模拟键盘", "内置键盘");
    chooseMode(test, screen, "系统输入法", "系统输入法");
    // Let the chooser's delayed IME opening finish before restoring the test's idle layout.
    Thread.sleep(300);
    test.runOnMainSync(() -> screen.useKeyboard(false, false));
  }

  private static String copyDiagnostics(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    String clipboard
  ) throws Exception {
    // Only synthetic CI content is used by this fixture, never production data.
    java.util.concurrent.atomic.AtomicReference<String> nativeState =
      new java.util.concurrent.atomic.AtomicReference<>("");
    java.lang.reflect.Field feedback = TerminalActivity.class.getDeclaredField(
      "feedback"
    );
    feedback.setAccessible(true);
    test.runOnMainSync(() -> {
      View copy = InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "复制"
      );
      String message;
      try {
        message = String.valueOf(
          ((android.widget.TextView) feedback.get(screen)).getText()
        );
      } catch (IllegalAccessException e) {
        message = e.getClass().getSimpleName();
      }
      nativeState.set(
        "windowFocus=" +
          screen.hasWindowFocus() +
          ", surfaceFocus=" +
          surface.hasFocus() +
          ", copyShown=" +
          (copy != null && copy.isShown()) +
          ", copyBounds=" +
          (copy == null ? "missing" : bounds(copy)) +
          ", feedback=" +
          JSONObject.quote(message)
      );
    });
    return (
      "clipboard=" +
      JSONObject.quote(clipboard) +
      ", " +
      nativeState.get() +
      ", renderer=" +
      TerminalChecks.js(
        test,
        surface,
        "({copyReads:copyProbe.reads,selection:copyOriginalSelection(),rows:terminal.rows,cols:terminal.cols,focus:document.hasFocus()})"
      )
    );
  }

  private static String bounds(View view) {
    int[] position = new int[2];
    view.getLocationOnScreen(position);
    return position[0] + "," + position[1] + "+" + view.getWidth() + "x" + view.getHeight();
  }

  private static void chooseMode(
    Instrumentation test,
    TerminalActivity screen,
    String choice,
    String expected
  ) throws Exception {
    test.runOnMainSync(() -> {
      String current = screen
        .getSharedPreferences("terminal_ui", 0)
        .getBoolean("internal_keyboard", false)
        ? "内置键盘"
        : "系统输入法";
      View selector = InteractionChecks.find(
        screen.getWindow().getDecorView(),
        "切换输入方式，当前：" + current
      );
      TerminalChecks.check(
        selector != null && selector.performClick(),
        "Input mode must be selectable from its accessible action"
      );
    });
    test.waitForIdleSync();
    boolean[] clicked = { false };
    TerminalChecks.await(() -> {
      if (clicked[0]) return true;
      AccessibilityNodeInfo root = test
        .getUiAutomation()
        .getRootInActiveWindow();
      if (root == null) return false;
      for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(
        choice
      )) {
        if (
          node.getText() == null || !choice.contentEquals(node.getText())
        ) continue;
        while (node != null && !node.isClickable()) node = node.getParent();
        if (
          node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
          clicked[0] = true;
          return true;
        }
      }
      return false;
    }, "Input-mode dialog must expose its real choices");
    test.waitForIdleSync();
    test.runOnMainSync(() -> {
      String label = "切换输入方式，当前：" + expected;
      View selector = InteractionChecks.find(
        screen.getWindow().getDecorView(),
        label
      );
      TerminalChecks.check(
        selector != null &&
          label.contentEquals(selector.getTooltipText()) &&
          label.contentEquals(
            selector.createAccessibilityNodeInfo().getContentDescription()
          ),
        "After changing input mode, accessibility and tooltip must describe the new current mode"
      );
    });
  }
}
