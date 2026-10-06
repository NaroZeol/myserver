package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** Compares the actual renderer, SSH PTY and tmux client/window after native layout changes. */
final class TmuxResizeChecks {

  static void run(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    TerminalSession connection
  ) throws Exception {
    java.io.File trace = new java.io.File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots/terminal-resize.txt"
    );
    trace.getParentFile().mkdirs();
    try (java.io.OutputStream out = new java.io.FileOutputStream(trace)) {
      out.write(
        "Renderer / native bridge / remote tmux sizes\n".getBytes(
          StandardCharsets.UTF_8
        )
      );
    }
    test.runOnMainSync(() -> screen.useKeyboard(true, false));
    rotate(test, screen, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.resetModifiers(); TerminalUI.key('\\u0003')"
    );
    assertPty(test, surface, connection, "before tmux");

    String socket = "myserver-resize-" + System.nanoTime();
    String tmux = "tmux -L " + socket;
    String command =
      tmux +
      " -f /dev/null new-session -d -s resize 'exec bash --noprofile --norc'; " +
      tmux +
      " set-option -g status on; " +
      tmux +
      " set-option -g status-position bottom; " +
      tmux +
      " set-option -g status-left 'C#{client_width}x#{client_height} W#{window_width}x#{window_height}|'; " +
      tmux +
      " set-option -g status-left-length 80; " +
      tmux +
      " set-option -g status-right ''; " +
      tmux +
      " set-option -g status-interval 1; " +
      tmux +
      " set-hook -g client-detached kill-server; " +
      tmux +
      " attach-session -t resize; " +
      tmux +
      " kill-server 2>/dev/null; printf '\\nTMUX_RESIZE_DONE\\n'\r";
    TerminalChecks.check(
      connection.send(command.getBytes(StandardCharsets.UTF_8)),
      "Start isolated tmux resize fixture"
    );
    try {
      awaitTmux(test, screen, surface, connection, null, "initial portrait");
      int[] before = dimensions(test, surface);
      test.runOnMainSync(() -> screen.useKeyboard(true, true));
      awaitTmux(
        test,
        screen,
        surface,
        connection,
        before,
        "portrait built-in keyboard"
      );
      capture(test, "terminal-tmux-portrait.png");

      before = dimensions(test, surface);
      rotate(test, screen, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      awaitTmux(
        test,
        screen,
        surface,
        connection,
        before,
        "landscape built-in keyboard"
      );
      capture(test, "terminal-tmux-landscape.png");

      before = dimensions(test, surface);
      test.runOnMainSync(() -> screen.useKeyboard(true, false));
      awaitTmux(
        test,
        screen,
        surface,
        connection,
        before,
        "landscape keyboard hidden"
      );

      before = dimensions(test, surface);
      rotate(test, screen, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      awaitTmux(test, screen, surface, connection, before, "portrait restored");

      before = dimensions(test, surface);
      test.runOnMainSync(() -> screen.useKeyboard(false, true));
      awaitTmux(
        test,
        screen,
        surface,
        connection,
        before,
        "portrait system input method"
      );

      before = dimensions(test, surface);
      test.runOnMainSync(() -> screen.useKeyboard(true, false));
      awaitTmux(
        test,
        screen,
        surface,
        connection,
        before,
        "input method dismissed"
      );
      TerminalChecks.js(test, surface, "TerminalUI.key('\\u0002d')");
      awaitJs(
        test,
        surface,
        "terminal.buffer.active.type==='normal' && Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).some(v=>v.trim()==='TMUX_RESIZE_DONE')",
        "tmux resize fixture must detach to the original shell"
      );
      assertPty(
        test,
        surface,
        connection,
        "after rotations and input mode changes"
      );
    } finally {
      // The disposable tmux server exits on detach, including an assertion failure.
      try {
        if (
          "\"alternate\"".equals(
            TerminalChecks.js(test, surface, "terminal.buffer.active.type")
          )
        ) connection.send(new byte[] { 2, 'd' });
      } catch (Exception ignored) {
      } catch (AssertionError ignored) {
      }
      test.runOnMainSync(() -> {
        screen.useKeyboard(true, false);
        screen.setRequestedOrientation(
          ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        );
      });
    }
  }

  private static void rotate(
    Instrumentation test,
    TerminalActivity screen,
    int orientation
  ) throws Exception {
    test.runOnMainSync(() -> screen.setRequestedOrientation(orientation));
    int expected =
      orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ? Configuration.ORIENTATION_LANDSCAPE
        : Configuration.ORIENTATION_PORTRAIT;
    TerminalChecks.await(
      () -> screen.getResources().getConfiguration().orientation == expected,
      "Terminal configuration must finish rotating"
    );
  }

  private static int[] dimensions(Instrumentation test, TerminalSurface surface)
    throws Exception {
    JSONArray size = new JSONArray(
      TerminalChecks.js(test, surface, "[terminal.cols,terminal.rows]")
    );
    return new int[] { size.getInt(0), size.getInt(1) };
  }

  private static void awaitTmux(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    TerminalSession connection,
    int[] before,
    String stage
  ) throws Exception {
    if (before != null) awaitJs(
      test,
      surface,
      "terminal.cols!==" + before[0] + " || terminal.rows!==" + before[1],
      stage + ": changing the native layout must change the terminal grid"
    );
    // These sizes are rendered by the real remote tmux process, not a JS fake or local resize call.
    // The fixture places tmux's status bar on the bottom row.
    awaitJs(
      test,
      surface,
      "terminal.buffer.active.type==='alternate' && terminal.buffer.active.getLine(terminal.rows-1).translateToString(true).includes('C'+terminal.cols+'x'+terminal.rows+' W'+terminal.cols+'x'+(terminal.rows-1)+'|')",
      stage +
        ": remote tmux client and window must follow the visible xterm grid"
    );
    trace(test, surface, stage);
    TerminalChecks.check(
      connection.isConnected() &&
        !screen.isDestroyed() &&
        TerminalRuntime.current().connection == connection &&
        TerminalRuntime.current().surface == surface,
      stage + ": resizing must retain the same SSH session and renderer"
    );
  }

  private static void assertPty(
    Instrumentation test,
    TerminalSurface surface,
    TerminalSession connection,
    String stage
  ) throws Exception {
    // Wait for the asynchronous JS bridge before enqueuing the shell command after window-change.
    TerminalChecks.await(() -> {
      try {
        int[] size = dimensions(test, surface);
        return surface.columns == size[0] && surface.rows == size[1];
      } catch (Exception e) {
        return false;
      }
    }, stage + ": the native bridge must receive the fitted dimensions");
    String marker = "R" + Long.toHexString(System.nanoTime() & 0xffff) + ":";
    TerminalChecks.check(
      connection.send(
        ("printf '\\n" + marker + "'; stty size\r").getBytes(
          StandardCharsets.UTF_8
        )
      ),
      stage + ": PTY size probe must be accepted"
    );
    awaitJs(
      test,
      surface,
      "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).some(v=>v.trim()===" +
        org.json.JSONObject.quote(marker) +
        "+terminal.rows+' '+terminal.cols)",
      stage + ": stty size on the real SSH PTY must equal xterm rows/columns"
    );
    trace(test, surface, stage + " (stty matched)");
  }

  private static void trace(
    Instrumentation test,
    TerminalSurface surface,
    String stage
  ) throws Exception {
    String state = TerminalChecks.js(
      test,
      surface,
      "JSON.stringify({cols:terminal.cols,rows:terminal.rows,tmux:terminal.buffer.active.type==='alternate'?terminal.buffer.active.getLine(terminal.rows-1).translateToString(true).split('|')[0]:null})"
    );
    String line =
      stage +
      " native=" +
      surface.columns +
      "x" +
      surface.rows +
      " renderer=" +
      state +
      "\n";
    java.io.File file = new java.io.File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots/terminal-resize.txt"
    );
    try (java.io.OutputStream out = new java.io.FileOutputStream(file, true)) {
      out.write(line.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void awaitJs(
    Instrumentation test,
    TerminalSurface surface,
    String script,
    String reason
  ) throws Exception {
    try {
      TerminalChecks.await(() -> {
        try {
          return "true".equals(TerminalChecks.js(test, surface, script));
        } catch (Exception e) {
          return false;
        }
      }, reason);
    } catch (AssertionError failure) {
      throw new AssertionError(
        reason +
          "; native=" +
          surface.columns +
          "x" +
          surface.rows +
          "; renderer=" +
          TerminalChecks.js(
            test,
            surface,
            "JSON.stringify({cols:terminal.cols,rows:terminal.rows,mode:terminal.buffer.active.type,last:Array.from({length:Math.min(4,terminal.rows)},(_,i)=>terminal.buffer.active.getLine(terminal.rows-1-i).translateToString(true))})"
          ),
        failure
      );
    }
  }

  private static void capture(Instrumentation test, String name)
    throws Exception {
    android.graphics.Bitmap bitmap = test.getUiAutomation().takeScreenshot();
    if (bitmap == null) throw new AssertionError(
      "Screenshot unavailable: " + name
    );
    java.io.File dir = new java.io.File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    dir.mkdirs();
    try (
      java.io.OutputStream out = new java.io.FileOutputStream(
        new java.io.File(dir, name)
      )
    ) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }
}
