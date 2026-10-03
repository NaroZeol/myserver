package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import app.thoughts.mobile.InteractionChecks;
import org.json.JSONArray;

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
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.special=originalSpecial; terminal.write('\\x1b[2J\\x1b[Hcopy_me 测试\\r\\nsecond line');"
    );
    Thread.sleep(250);
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
    test.runOnMainSync(() ->
      InteractionChecks.find(root, "复制").performClick()
    );
    test.waitForIdleSync();
    java.util.concurrent.atomic.AtomicReference<String> copied =
      new java.util.concurrent.atomic.AtomicReference<>("");
    TerminalChecks.await(() -> {
      test.runOnMainSync(() -> {
        ClipboardManager clipboard = (ClipboardManager) screen.getSystemService(
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
  }
}
