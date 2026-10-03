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
      long delay;

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
        task.run();
      }
    }
    Clock clock = new Clock();
    int[] calls = { 0 };
    RefreshLoop[] loop = { null };
    loop[0] = new RefreshLoop(clock, () -> {
      check(loop[0].begin(), "Refresh must begin once");
      calls[0]++;
    });
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
    loop[0].resume(0);
    clock.fire();
    loop[0].finished();
    check(clock.pending == null, "Manual mode must not poll in the background");
    check(calls[0] == 3, "Lifecycle must not duplicate samples");
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
  }
}
