package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.nio.charset.StandardCharsets;

/** Real finger gestures through WebView, SSH PTY and a disposable tmux server. */
final class TmuxGestureChecks {

  static void run(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    TerminalSession connection
  ) throws Exception {
    test.runOnMainSync(() -> screen.useKeyboard(true, false));
    TerminalChecks.await(
      () ->
        screen.getResources().getConfiguration().orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT,
      "Restore portrait before tmux gestures"
    );
    String socket = "myserver-touch-" + System.nanoTime();
    String tmux = "tmux -L " + socket;
    String command =
      tmux +
      " -f /dev/null new-session -d -s touch 'seq 1 250; echo TMUX_READY; exec bash --noprofile --norc'; " +
      tmux +
      " set-option -g mouse on; " +
      tmux +
      " set-hook -g client-detached kill-server; " +
      tmux +
      " attach-session -t touch; " +
      tmux +
      " kill-server 2>/dev/null; printf '\\nTMUX_DONE\\n'\r";
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.resetModifiers(); TerminalUI.key('\\u0003')"
    );
    connection.send(command.getBytes(StandardCharsets.UTF_8));
    waitJs(
      test,
      surface,
      "terminal.buffer.active.type==='alternate' && terminal.modes.mouseTrackingMode!=='none' && " +
        "Array.from({length:terminal.rows},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).some(v=>v.trim()==='TMUX_READY')",
      "tmux must start with mouse mode on the real SSH session"
    );
    swipe(test, surface, 0.20f);
    waitJs(
      test,
      surface,
      "/\\[([1-9][0-9]*)\\/[0-9]+\\]/.test(terminal.buffer.active.getLine(0).translateToString(true))",
      "Finger swipe must enter tmux copy mode and scroll its remote history"
    );
    int before = offset(test, surface);
    swipe(test, surface, -0.12f);
    waitJs(
      test,
      surface,
      "(()=>{const m=/\\[([0-9]+)\\/[0-9]+\\]/.exec(terminal.buffer.active.getLine(0).translateToString(true));return !m || Number(m[1])<" +
        before +
        "})()",
      "Reverse swipe must scroll tmux history down"
    );
    // Wait for tmux to process q before sending prefix+d; otherwise it can consume
    // the detach keys in copy mode while its cancel command is still queued.
    TerminalChecks.js(test, surface, "TerminalUI.key('q')");
    waitJs(
      test,
      surface,
      "!/\\[([0-9]+)\\/[0-9]+\\]/.test(terminal.buffer.active.getLine(0).translateToString(true))",
      "tmux must exit copy mode before detaching"
    );
    TerminalChecks.js(test, surface, "TerminalUI.key('\\u0002d')");
    waitJs(
      test,
      surface,
      "terminal.buffer.active.type==='normal' && " +
        "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).some(v=>v.trim()==='TMUX_DONE')",
      "Detaching tmux must restore normal shell input"
    );
  }

  private static int offset(Instrumentation test, TerminalSurface surface)
    throws Exception {
    return Integer.parseInt(
      TerminalChecks.js(
        test,
        surface,
        "Number(/\\[([0-9]+)\\/[0-9]+\\]/.exec(terminal.buffer.active.getLine(0).translateToString(true))[1])"
      )
    );
  }

  private static void waitJs(
    Instrumentation test,
    TerminalSurface surface,
    String script,
    String reason
  ) throws Exception {
    TerminalChecks.await(() -> {
      try {
        return "true".equals(TerminalChecks.js(test, surface, script));
      } catch (Exception e) {
        return false;
      }
    }, reason);
  }

  private static void swipe(
    Instrumentation test,
    TerminalSurface surface,
    float distance
  ) throws Exception {
    int[] location = new int[2];
    test.runOnMainSync(() -> surface.getLocationOnScreen(location));
    float x = location[0] + surface.getWidth() * 0.5f;
    float y = location[1] + surface.getHeight() * 0.45f;
    long down = SystemClock.uptimeMillis();
    for (int step = 0; step <= 9; step++) {
      int action =
        step == 0
          ? MotionEvent.ACTION_DOWN
          : step == 9
            ? MotionEvent.ACTION_UP
            : MotionEvent.ACTION_MOVE;
      MotionEvent event = MotionEvent.obtain(
        down,
        SystemClock.uptimeMillis(),
        action,
        x,
        y + (surface.getHeight() * distance * Math.min(step, 8)) / 8,
        0
      );
      event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
      test.getUiAutomation().injectInputEvent(event, true);
      event.recycle();
      Thread.sleep(25);
    }
  }
}
