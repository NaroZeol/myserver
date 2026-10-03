package app.thoughts.mobile.modules.server;

/** Schedule after completion, never overlap requests or poll an invisible screen. */
final class RefreshLoop {

  interface Scheduler {
    void post(Runnable task, long delay);
    void remove(Runnable task);
  }

  private final Scheduler scheduler;
  private final Runnable request;
  private final Runnable tick;
  private boolean visible, running;
  private long interval;

  RefreshLoop(Scheduler scheduler, Runnable request) {
    this.scheduler = scheduler;
    this.request = request;
    tick = () -> {
      if (visible && !running) this.request.run();
    };
  }

  void resume(long milliseconds) {
    visible = true;
    interval = milliseconds;
    scheduler.remove(tick);
    if (!running) scheduler.post(tick, 0);
  }

  void pause() {
    visible = false;
    scheduler.remove(tick);
  }

  void interval(long milliseconds) {
    interval = milliseconds;
    scheduler.remove(tick);
    if (visible && !running && interval > 0) scheduler.post(tick, interval);
  }

  boolean begin() {
    if (running) return false;
    scheduler.remove(tick);
    running = true;
    return true;
  }

  void finished() {
    running = false;
    if (visible && interval > 0) scheduler.post(tick, interval);
  }
}
