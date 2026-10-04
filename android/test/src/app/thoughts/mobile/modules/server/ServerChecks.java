package app.thoughts.mobile.modules.server;

import android.app.Instrumentation;
import android.widget.LinearLayout;
import app.thoughts.mobile.InteractionChecks;
import app.thoughts.mobile.MainActivity;
import org.json.JSONObject;

public final class ServerChecks {

  private static void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
  }

  public static void run(Instrumentation test, MainActivity screen)
    throws Exception {
    class Clock implements RefreshLoop.Scheduler {

      Runnable pending;
      long delay, now;

      public void post(Runnable task, long milliseconds) {
        check(pending == null, "Only one monitor tick may be queued");
        pending = task;
        delay = milliseconds;
      }

      public void remove(Runnable task) {
        if (pending == task) pending = null;
      }

      void fire() {
        Runnable task = pending;
        pending = null;
        check(task != null, "Expected a pending refresh");
        now += delay;
        task.run();
      }
    }
    Clock clock = new Clock();
    int[] calls = { 0 };
    RefreshLoop[] loop = { null };
    loop[0] = new RefreshLoop(
      clock,
      () -> {
        check(loop[0].begin(), "Refresh must begin once");
        calls[0]++;
      },
      () -> clock.now
    );
    loop[0].resume(5000);
    clock.fire();
    check(!loop[0].begin(), "Slow requests must not overlap");
    loop[0].finished();
    check(
      clock.delay == 5000,
      "Use configured interval after a completed sample"
    );
    loop[0].interval(2000);
    check(clock.delay == 2000, "Changing the interval replaces the old timer");
    clock.fire();
    loop[0].pause();
    loop[0].finished();
    check(
      clock.pending == null,
      "Leaving the screen must not reschedule an in-flight request"
    );
    loop[0].resume(2000);
    check(
      clock.delay == 2000,
      "Returning immediately must reuse the last sample"
    );
    loop[0].pause();
    clock.now += 750;
    loop[0].resume(2000);
    check(
      clock.delay == 1250,
      "Navigation must keep the remaining sampling interval"
    );
    loop[0].pause();
    clock.now += 3000;
    loop[0].resume(2000);
    check(
      clock.delay == 0,
      "Expired monitoring samples must refresh immediately"
    );
    clock.fire();
    loop[0].finished();
    loop[0].pause();
    loop[0].resume(0);
    check(
      clock.pending == null,
      "Manual mode must reuse the last snapshot across navigation"
    );
    check(loop[0].begin(), "Explicit refresh must bypass snapshot freshness");
    loop[0].finished();
    check(clock.pending == null, "Manual mode must not poll in the background");
    check(calls[0] == 3, "Lifecycle must not duplicate samples");
    Clock restored = new Clock();
    RefreshLoop cached = new RefreshLoop(
      restored,
      () -> {},
      () -> restored.now
    );
    cached.cached(1000);
    cached.resume(5000);
    check(
      restored.delay == 4000,
      "Restored snapshots must retain their remaining freshness"
    );
    cached.pause();
    cached.resume(0);
    check(
      restored.pending == null,
      "Manual mode must reuse persisted snapshots"
    );
    final JSONObject metrics = new JSONObject(
      "{\"cpu\":{\"cores\":2,\"usage_percent\":37.5,\"load_average\":[0.5,0.3,0.2]},\"memory\":null,\"disk\":null,\"uptime_seconds\":20}"
    );
    test.runOnMainSync(() -> {
      LinearLayout surface = new LinearLayout(screen);
      new SystemOverview(screen).render(surface, metrics, 1, true, false);
      check(
        InteractionChecks.find(surface, "37.5%") != null,
        "CPU values must render without integer truncation"
      );
      check(
        InteractionChecks.find(surface, "—") != null,
        "Missing metrics must not render as zero"
      );
    });
    if (
      screen.api().profile != null && screen.account().can("system.read")
    ) liveMonitoring(test, screen);
  }

  private static void liveMonitoring(Instrumentation test, MainActivity screen)
    throws Exception {
    android.content.SharedPreferences preferences = screen.getSharedPreferences(
      "monitor_settings",
      0
    );
    android.content.SharedPreferences snapshots = screen.getSharedPreferences(
      "server_status",
      0
    );
    int previous = MonitorSettings.seconds(screen);
    try {
      preferences.edit().putInt("seconds", 2).commit();
      long before = snapshots.getLong("checked_at", 0);
      test.runOnMainSync(() -> screen.navigate("server"));
      app.thoughts.mobile.modules.terminal.TerminalChecks.await(
        () -> snapshots.getLong("checked_at", 0) > before,
        "Entering the server workspace must fetch fresh metrics"
      );
      long first = snapshots.getLong("checked_at", 0);
      app.thoughts.mobile.modules.terminal.TerminalChecks.await(
        () -> snapshots.getLong("checked_at", 0) > first,
        "Visible monitoring must refresh at the selected interval"
      );
      test.waitForIdleSync();
      android.graphics.Bitmap picture = test.getUiAutomation().takeScreenshot();
      java.io.File directory = new java.io.File(
        test.getTargetContext().getExternalFilesDir(null),
        "screenshots"
      );
      directory.mkdirs();
      try (
        java.io.OutputStream out = new java.io.FileOutputStream(
          new java.io.File(directory, "server-connected.png")
        )
      ) {
        picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
      }
      picture.recycle();
      test.runOnMainSync(() -> screen.navigate("thoughts"));
      screen
        .executor()
        .submit(() -> {})
        .get(45, java.util.concurrent.TimeUnit.SECONDS);
      test.waitForIdleSync();
      long stopped = snapshots.getLong("checked_at", 0);
      Thread.sleep(2500);
      check(
        snapshots.getLong("checked_at", 0) == stopped,
        "Leaving the server page must stop automatic SSH requests"
      );
      preferences.edit().putInt("seconds", 0).commit();
      test.runOnMainSync(() -> screen.navigate("server"));
      test.waitForIdleSync();
      check(
        snapshots.getLong("checked_at", 0) == stopped,
        "Manual mode must reuse its existing snapshot when returning"
      );
      long manual = snapshots.getLong("checked_at", 0);
      Thread.sleep(2500);
      check(
        snapshots.getLong("checked_at", 0) == manual,
        "Manual mode must not continue automatic refresh"
      );
    } finally {
      test.runOnMainSync(() -> screen.navigate("thoughts"));
      preferences.edit().putInt("seconds", previous).commit();
    }
  }
}
