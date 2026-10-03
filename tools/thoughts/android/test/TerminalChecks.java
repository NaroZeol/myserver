package app.thoughts.mobile;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.WindowManager;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class TerminalChecks {

  static void check(boolean pass, String reason) {
    if (!pass) throw new AssertionError(reason);
  }

  static void await(java.util.function.BooleanSupplier condition, String reason)
    throws Exception {
    long until = System.nanoTime() + 30_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < until)
      Thread.sleep(50);
    check(condition.getAsBoolean(), reason);
  }

  static String js(Instrumentation test, TerminalSurface view, String script)
    throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> result = new AtomicReference<>();
    test.runOnMainSync(() ->
      view.evaluateJavascript(script, value -> {
        result.set(value);
        done.countDown();
      })
    );
    check(done.await(15, TimeUnit.SECONDS), "Terminal JavaScript timed out");
    return result.get();
  }

  static void run(Instrumentation test, ServerProfile profile, byte[] password)
    throws Exception {
    check(
      !java.util.Arrays.equals(
        new DeviceKey().getPublicKeyBlob(),
        new DeviceKey(TerminalAuth.keyId(profile)).getPublicKeyBlob()
      ),
      "Terminal must not reuse restricted RPC identity"
    );
    StringBuffer received = new StringBuffer();
    CountDownLatch connected = new CountDownLatch(1),
      ended = new CountDownLatch(1);
    TerminalSession session = new TerminalSession(
      new TerminalSession.Listener() {
        public void connected(boolean registered) {
          connected.countDown();
        }

        public void output(byte[] bytes) {
          received.append(new String(bytes, StandardCharsets.UTF_8));
        }

        public void ended(String reason) {
          received.append(reason);
          ended.countDown();
        }
      }
    );
    try {
      session.start(profile, password, true);
      check(
        connected.await(35, TimeUnit.SECONDS),
        "Terminal enrollment/PTY failed: " + received
      );
      for (byte b : password)
        check(b == 0, "Terminal login password must be cleared");
      session.resize(91, 31);
      check(
        session.send(
          "stty size; printf '\\033[31mPTY_READY\\033[0m\\n'\r".getBytes(
            StandardCharsets.UTF_8
          )
        ),
        "PTY input not accepted"
      );
      await(
        () ->
          received.indexOf("31 91") >= 0 &&
          received.indexOf("\u001b[31mPTY_READY\u001b[0m") >= 0,
        "PTY resize or ANSI output missing: " + received
      );
      session.send("sleep 30\r".getBytes(StandardCharsets.UTF_8));
      Thread.sleep(350);
      session.send(new byte[] { 3 });
      session.send(
        "printf '\\nINTERRUPTED_OK\\n'\r".getBytes(StandardCharsets.UTF_8)
      );
      await(
        () -> received.indexOf("\r\nINTERRUPTED_OK\r\n") >= 0,
        "Ctrl+C must interrupt foreground command"
      );
      session.send("exit\r".getBytes(StandardCharsets.UTF_8));
      check(ended.await(10, TimeUnit.SECONDS), "Exit must end PTY");
      check(
        !session.isConnected(),
        "Exited terminal must not remain connected"
      );
    } finally {
      session.close();
      java.util.Arrays.fill(password, (byte) 0);
    }
    check(
      new SshTransport(profile)
        .request("/session", "GET", null)
        .has("capabilities"),
      "Terminal enrollment must preserve restricted RPC key"
    );

    TerminalActivity screen = (TerminalActivity) test.startActivitySync(
      new Intent(test.getTargetContext(), TerminalActivity.class).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
      )
    );
    try {
      java.lang.reflect.Field field = TerminalActivity.class.getDeclaredField(
        "terminal"
      );
      field.setAccessible(true);
      TerminalSurface surface = (TerminalSurface) field.get(screen);
      await(() -> surface.loaded, "Bundled terminal renderer did not load");
      test.runOnMainSync(() -> {
        check(
          !surface.getSettings().getAllowFileAccess(),
          "Terminal WebView must not read local files"
        );
        check(
          !surface.getSettings().getAllowContentAccess(),
          "Terminal WebView must not access content providers"
        );
      });
      test.runOnMainSync(() -> screen.begin(null, false));
      await(() -> {
        try {
          return (
            js(test, surface, "terminal.buffer.active.length").length() > 0
          );
        } catch (Exception e) {
          return false;
        }
      }, "Terminal screen not ready");
      // Wait for native connection; a rendered page is not proof of authentication.
      java.lang.reflect.Field connection =
        TerminalActivity.class.getDeclaredField("connection");
      connection.setAccessible(true);
      TerminalSession active = (TerminalSession) connection.get(screen);
      await(active::isConnected, "Independent terminal key must reconnect");
      js(
        test,
        surface,
        "TerminalUI.key(\"printf '\\\\nUI_READY_测试\\\\n'\\r\")"
      );
      await(() -> {
        try {
          return js(
            test,
            surface,
            "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString()).join('\\n')"
          ).contains("UI_READY_测试");
        } catch (Exception e) {
          return false;
        }
      }, "Real PTY output must render including Unicode");
      check(
        js(test, surface, "typeof Phone.readFile").equals("\"undefined\""),
        "No filesystem bridge"
      );
      // Emulator survives a resize/rotation and handles alternate screen mode.
      js(
        test,
        surface,
        "terminal.resize(50,12); terminal.write('\\x1b[?1049hALT_SCREEN\\x1b[?1049l')"
      );
      test.runOnMainSync(() ->
        screen.onConfigurationChanged(screen.getResources().getConfiguration())
      );
      check(
        active.isConnected(),
        "Configuration change must preserve terminal session"
      );
      // Capture only synthetic CI terminal content, never production output.
      test.runOnMainSync(() ->
        screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      );
      Thread.sleep(300);
      java.io.File dir = new java.io.File(
        test.getTargetContext().getExternalFilesDir(null),
        "screenshots"
      );
      dir.mkdirs();
      android.graphics.Bitmap picture = test.getUiAutomation().takeScreenshot();
      try (
        java.io.OutputStream out = new java.io.FileOutputStream(
          new java.io.File(dir, "terminal-connected.png")
        )
      ) {
        picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
      }
      picture.recycle();
      test.runOnMainSync(() -> screen.disconnect());
      await(() -> !active.isConnected(), "Disconnect must close PTY");
      check(
        !active.send(new byte[] { 'x' }),
        "Disconnected input must not be queued for a future session"
      );
    } finally {
      test.runOnMainSync(screen::finish);
    }
  }
}
