package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Actual emulator sleep, background and Activity recreation, retaining the same remote shell. */
final class TerminalLifecycleChecks {

  static TerminalActivity run(
    Instrumentation test,
    TerminalActivity screen,
    TerminalSurface surface,
    TerminalSession connection
  ) throws Exception {
    TerminalRuntime runtime = TerminalRuntime.current();
    TerminalChecks.check(
      runtime != null && runtime.connection == connection,
      "Service must own the visible session"
    );
    TerminalChecks.await(
      () -> notification(test) != null,
      "Connected session must expose a foreground notification"
    );
    java.lang.reflect.Field serviceField =
      TerminalRuntime.class.getDeclaredField("service");
    serviceField.setAccessible(true);
    TerminalChecks.await(() -> {
      try {
        return serviceField.get(runtime) != null;
      } catch (IllegalAccessException e) {
        return false;
      }
    }, "Foreground service must attach to the runtime");
    TerminalService service = (TerminalService) serviceField.get(runtime);
    TerminalChecks.check(
      service != null && service.holdingWakeLock(),
      "Active session must hold a CPU wake lock"
    );
    TerminalChecks.js(
      test,
      surface,
      "TerminalUI.resetModifiers(); TerminalUI.key('\\u0003')"
    );
    connection.send(
      "MYSERVER_LIFECYCLE_TOKEN=retained; sleep 2; printf '\\nLOCK_OUTPUT_READY\\n'\r".getBytes(
        StandardCharsets.UTF_8
      )
    );
    PowerManager power = test
      .getTargetContext()
      .getSystemService(PowerManager.class);
    try {
      shell(test, "input keyevent KEYCODE_SLEEP");
      TerminalChecks.await(
        () -> !power.isInteractive(),
        "Test must really switch the display off"
      );
      Thread.sleep(3000);
      TerminalChecks.check(
        connection.isConnected(),
        "Locking the screen must not disconnect SSH"
      );
    } finally {
      shell(test, "input keyevent KEYCODE_WAKEUP");
      shell(test, "wm dismiss-keyguard");
    }
    TerminalChecks.await(
      power::isInteractive,
      "Screen must wake after the lock test"
    );
    waitLine(test, surface, "LOCK_OUTPUT_READY");
    shell(test, "input keyevent KEYCODE_HOME");
    TerminalChecks.await(
      () -> !screen.hasWindowFocus(),
      "Terminal must actually enter the background"
    );
    connection.send(
      "sleep 1; printf '\\nBACKGROUND_OUTPUT_READY\\n'\r".getBytes(
        StandardCharsets.UTF_8
      )
    );
    Thread.sleep(1500);
    TerminalChecks.check(
      connection.isConnected(),
      "Background SSH must stay connected"
    );
    TerminalChecks.check(
      test
        .getUiAutomation()
        .performGlobalAction(
          android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        ),
      "Notification shade must open"
    );
    java.util.concurrent.atomic.AtomicBoolean tapped =
      new java.util.concurrent.atomic.AtomicBoolean();
    TerminalChecks.await(() -> {
      if (!tapped.get()) tapped.set(tapNotification(test));
      return tapped.get();
    }, "Foreground notification must return to the terminal when tapped");
    TerminalChecks.await(
      screen::hasWindowFocus,
      "Notification must return to the existing terminal"
    );
    waitLine(test, surface, "BACKGROUND_OUTPUT_READY");

    Instrumentation.ActivityMonitor monitor = test.addMonitor(
      TerminalActivity.class.getName(),
      null,
      false
    );
    TerminalActivity restored;
    try {
      test.runOnMainSync(screen::recreate);
      restored = (TerminalActivity) test.waitForMonitorWithTimeout(
        monitor,
        15000
      );
      TerminalChecks.check(
        restored != null && restored != screen,
        "Activity recreation must create a fresh screen"
      );
    } finally {
      test.removeMonitor(monitor);
    }
    test.waitForIdleSync();
    java.lang.reflect.Field terminalField =
      TerminalActivity.class.getDeclaredField("terminal");
    terminalField.setAccessible(true);
    TerminalChecks.check(
      terminalField.get(restored) == surface,
      "Recreation must preserve the existing ANSI emulator"
    );
    TerminalChecks.check(
      runtime.connection == connection && connection.isConnected(),
      "Recreation must preserve the same PTY"
    );
    TerminalChecks.check(
      runtime.context.getBaseContext() == restored,
      "Renderer must attach to the new Activity context"
    );
    waitLine(test, surface, "LOCK_OUTPUT_READY");
    connection.send(
      "printf '\\nSHELL_%s\\n' \"$MYSERVER_LIFECYCLE_TOKEN\"\r".getBytes(
        StandardCharsets.UTF_8
      )
    );
    waitLine(test, surface, "SHELL_retained");

    // Use the notification's real action, not an internal helper, to disconnect.
    notification(test).getNotification().actions[0].actionIntent.send();
    TerminalChecks.await(
      () -> !connection.isConnected(),
      "Notification disconnect must close the PTY"
    );
    TerminalChecks.await(
      () -> notification(test) == null,
      "Disconnect must remove the foreground notification"
    );
    TerminalChecks.check(
      !service.holdingWakeLock(),
      "Disconnect must release the CPU wake lock"
    );
    TerminalChecks.check(
      !connection.send(new byte[] { 'x' }),
      "Disconnected input must never be replayed"
    );
    return restored;
  }

  private static boolean tapNotification(Instrumentation test) {
    android.view.accessibility.AccessibilityNodeInfo root = test
      .getUiAutomation()
      .getRootInActiveWindow();
    if (root == null) return false;
    for (android.view.accessibility.AccessibilityNodeInfo item : root.findAccessibilityNodeInfosByText(
      "终端会话运行中"
    )) {
      android.view.accessibility.AccessibilityNodeInfo target = item;
      while (target != null && !target.isClickable())
        target = target.getParent();
      if (
        target != null &&
        target.performAction(
          android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
        )
      ) return true;
    }
    return false;
  }

  private static StatusBarNotification notification(Instrumentation test) {
    for (StatusBarNotification item : test
      .getTargetContext()
      .getSystemService(NotificationManager.class)
      .getActiveNotifications()) {
      if (item.getId() == TerminalService.NOTIFICATION_ID) return item;
    }
    return null;
  }

  private static void waitLine(
    Instrumentation test,
    TerminalSurface surface,
    String expected
  ) throws Exception {
    TerminalChecks.await(() -> {
      try {
        return "true".equals(
          TerminalChecks.js(
            test,
            surface,
            "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).includes(" +
              org.json.JSONObject.quote(expected) +
              ")"
          )
        );
      } catch (Exception e) {
        return false;
      }
    }, "Remote shell output missing after lifecycle transition: " + expected);
  }

  private static void shell(Instrumentation test, String command)
    throws Exception {
    try (
      android.os.ParcelFileDescriptor fd = test
        .getUiAutomation()
        .executeShellCommand(command);
      FileInputStream in = new FileInputStream(fd.getFileDescriptor())
    ) {
      byte[] buffer = new byte[1024];
      while (in.read(buffer) != -1) {}
    }
  }
}
