package app.thoughts.mobile.modules.terminal;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Actual emulator sleep, background and Activity recreation, retaining the same remote shell. */
final class TerminalLifecycleChecks {

  private static String keyguardDismissResult = "";
  private static String notificationTouch =
    "No visible notification title found";

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
      keyguardDismissResult = shellOutput(test, "wm dismiss-keyguard");
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
    android.accessibilityservice.AccessibilityServiceInfo accessibility = test
      .getUiAutomation()
      .getServiceInfo();
    int originalAccessibilityFlags = accessibility.flags;
    accessibility.flags |=
      android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
      android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
      android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
    test.getUiAutomation().setServiceInfo(accessibility);
    try {
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
    } catch (Exception | AssertionError failure) {
      diagnoseNotification(test, screen);
      throw failure;
    } finally {
      accessibility.flags = originalAccessibilityFlags;
      test.getUiAutomation().setServiceInfo(accessibility);
    }
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

  private static void diagnoseNotification(
    Instrumentation test,
    TerminalActivity screen
  ) {
    java.io.File directory = new java.io.File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    directory.mkdirs();
    StringBuilder detail = new StringBuilder();
    try {
      NotificationManager manager = test
        .getTargetContext()
        .getSystemService(NotificationManager.class);
      android.app.KeyguardManager keyguard = test
        .getTargetContext()
        .getSystemService(android.app.KeyguardManager.class);
      detail
        .append("Notification return diagnostic\n")
        .append("touch=")
        .append(notificationTouch)
        .append('\n')
        .append("dismiss-keyguard output: ")
        .append(keyguardDismissResult)
        .append("\n")
        .append("interactive=")
        .append(
          test
            .getTargetContext()
            .getSystemService(PowerManager.class)
            .isInteractive()
        )
        .append(" keyguardLocked=")
        .append(keyguard.isKeyguardLocked())
        .append(" deviceLocked=")
        .append(keyguard.isDeviceLocked())
        .append(" activityFocus=")
        .append(screen.hasWindowFocus())
        .append(" notificationsEnabled=")
        .append(manager.areNotificationsEnabled())
        .append('\n');
      android.app.NotificationChannel channel = manager.getNotificationChannel(
        "terminal"
      );
      detail
        .append("channelImportance=")
        .append(channel == null ? "missing" : channel.getImportance())
        .append('\n');
      StatusBarNotification active = notification(test);
      detail
        .append("notification=")
        .append(
          active == null
            ? "missing"
            : active
                .getNotification()
                .extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        )
        .append(" visibility=")
        .append(
          active == null ? "missing" : active.getNotification().visibility
        )
        .append('\n');
      android.app.UiAutomation automation = test.getUiAutomation();
      detail.append("Active root:\n");
      describeNode(automation.getRootInActiveWindow(), detail, 0, new int[] {
        0,
      });
      android.accessibilityservice.AccessibilityServiceInfo original =
        automation.getServiceInfo();
      int savedFlags = original.flags;
      try {
        original.flags |=
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(original);
        for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
          detail.append("Window ").append(window).append('\n');
          describeNode(window.getRoot(), detail, 0, new int[] { 0 });
        }
      } finally {
        original.flags = savedFlags;
        automation.setServiceInfo(original);
      }
      detail
        .append("Activity tasks:\n")
        .append(shellOutput(test, "dumpsys activity activities"));
      detail
        .append("Window state:\n")
        .append(shellOutput(test, "dumpsys window windows"));
      android.graphics.Bitmap screenshot = automation.takeScreenshot();
      if (screenshot != null) {
        try (
          java.io.FileOutputStream out = new java.io.FileOutputStream(
            new java.io.File(directory, "terminal-lifecycle-failure.png")
          )
        ) {
          screenshot.compress(
            android.graphics.Bitmap.CompressFormat.PNG,
            100,
            out
          );
        } finally {
          screenshot.recycle();
        }
      }
    } catch (Exception error) {
      detail.append("diagnosticError=").append(error);
    }
    try (
      java.io.FileOutputStream out = new java.io.FileOutputStream(
        new java.io.File(directory, "terminal-lifecycle-failure.txt")
      )
    ) {
      out.write(detail.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {}
  }

  private static void describeNode(
    android.view.accessibility.AccessibilityNodeInfo node,
    StringBuilder out,
    int depth,
    int[] count
  ) {
    if (node == null || depth > 40 || count[0]++ > 3000) return;
    android.graphics.Rect bounds = new android.graphics.Rect();
    node.getBoundsInScreen(bounds);
    for (int i = 0; i < depth; i++) out.append(' ');
    out
      .append(node.getClassName())
      .append(" id=")
      .append(node.getViewIdResourceName())
      .append(" text=")
      .append(node.getText())
      .append(" description=")
      .append(node.getContentDescription())
      .append(" clickable=")
      .append(node.isClickable())
      .append(" visible=")
      .append(node.isVisibleToUser())
      .append(" bounds=")
      .append(bounds)
      .append(" actions=")
      .append(node.getActionList())
      .append('\n');
    for (int i = 0; i < node.getChildCount(); i++) describeNode(
      node.getChild(i),
      out,
      depth + 1,
      count
    );
  }

  private static String shellOutput(Instrumentation test, String command)
    throws Exception {
    try (
      android.os.ParcelFileDescriptor fd = test
        .getUiAutomation()
        .executeShellCommand(command);
      FileInputStream in = new FileInputStream(fd.getFileDescriptor());
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
    ) {
      byte[] buffer = new byte[4096];
      int size;
      while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static boolean tapNotification(Instrumentation test) {
    // The active root can remain the launcher behind the shade. Inspect visible system windows.
    for (android.view.accessibility.AccessibilityWindowInfo window : test
      .getUiAutomation()
      .getWindows()) {
      if (
        window.getType() !=
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
      ) continue;
      android.graphics.Rect title = notificationTitle(window.getRoot(), 0);
      if (title == null || title.isEmpty()) continue;
      android.graphics.Rect bounds = new android.graphics.Rect();
      window.getBoundsInScreen(bounds);
      if (!bounds.contains(title.centerX(), title.centerY())) continue;
      long time = android.os.SystemClock.uptimeMillis();
      boolean down = touch(
        test,
        time,
        time,
        android.view.MotionEvent.ACTION_DOWN,
        title.centerX(),
        title.centerY()
      );
      android.os.SystemClock.sleep(60);
      boolean up = touch(
        test,
        time,
        android.os.SystemClock.uptimeMillis(),
        android.view.MotionEvent.ACTION_UP,
        title.centerX(),
        title.centerY()
      );
      notificationTouch = "title=" + title + " down=" + down + " up=" + up;
      return down && up;
    }
    return false;
  }

  private static android.graphics.Rect notificationTitle(
    android.view.accessibility.AccessibilityNodeInfo node,
    int depth
  ) {
    if (node == null || depth > 40) return null;
    if (
      node.isVisibleToUser() &&
      "终端会话运行中".contentEquals(
        node.getText() == null ? "" : node.getText()
      )
    ) {
      android.graphics.Rect bounds = new android.graphics.Rect();
      node.getBoundsInScreen(bounds);
      return bounds;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      android.graphics.Rect found = notificationTitle(
        node.getChild(i),
        depth + 1
      );
      if (found != null) return found;
    }
    return null;
  }

  private static boolean touch(
    Instrumentation test,
    long downTime,
    long eventTime,
    int action,
    int x,
    int y
  ) {
    android.view.MotionEvent event = android.view.MotionEvent.obtain(
      downTime,
      eventTime,
      action,
      x,
      y,
      0
    );
    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    try {
      return test.getUiAutomation().injectInputEvent(event, true);
    } finally {
      event.recycle();
    }
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
