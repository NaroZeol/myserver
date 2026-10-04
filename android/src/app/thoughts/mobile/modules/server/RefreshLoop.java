package app.thoughts.mobile.modules.server;

import java.util.function.LongSupplier;

/** Schedule from the last completion, never overlap or reset the interval on navigation. */
final class RefreshLoop {

  interface Scheduler {
    void post(Runnable task, long delay);
    void remove(Runnable task);
  }

  private final Scheduler scheduler;
  private final Runnable request;
  private final Runnable tick;
  private final LongSupplier clock;
  private boolean visible, running, sampled;
  private long interval, completedAt;

  RefreshLoop(Scheduler scheduler, Runnable request) {
    this(scheduler, request, android.os.SystemClock::elapsedRealtime);
  }

  RefreshLoop(Scheduler scheduler, Runnable request, LongSupplier clock) {
    this.scheduler = scheduler;
    this.request = request;
    this.clock = clock;
    tick = () -> {
      if (visible && !running) this.request.run();
    };
  }

  void cached(long age) {
    if (age < 0) return;
    sampled = true;
    completedAt = clock.getAsLong() - age;
  }

  private void schedule() {
    scheduler.remove(tick);
    if (!visible || running || (sampled && interval == 0)) return;
    long elapsed = clock.getAsLong() - completedAt;
    long delay = sampled && elapsed >= 0 ? Math.max(0, interval - elapsed) : 0;
    scheduler.post(tick, delay);
  }

  void resume(long milliseconds) {
    visible = true;
    interval = milliseconds;
    schedule();
  }

  void pause() {
    visible = false;
    scheduler.remove(tick);
  }

  void interval(long milliseconds) {
    interval = milliseconds;
    schedule();
  }

  boolean begin() {
    if (running) return false;
    scheduler.remove(tick);
    running = true;
    return true;
  }

  void finished() {
    running = false;
    sampled = true;
    completedAt = clock.getAsLong();
    schedule();
  }
}
