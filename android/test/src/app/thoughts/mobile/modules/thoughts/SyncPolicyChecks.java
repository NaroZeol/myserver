package app.thoughts.mobile.modules.thoughts;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Runs both on the JVM and in instrumentation; counts only permitted sync operations. */
public final class SyncPolicyChecks {

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static final class Clock implements LongSupplier {

    long now = 1_000_000;

    public long getAsLong() {
      return now;
    }
  }

  private static final class Memory implements SyncPolicy.Storage {

    final Map<String, String> values = new HashMap<>();

    public String read(String key) {
      return values.getOrDefault(key, "");
    }

    public void write(String key, String value) {
      values.put(key, value);
    }
  }

  private static final class Runner {

    final Clock clock = new Clock();
    final Memory memory = new Memory();
    SyncPolicy policy = new SyncPolicy(memory, clock);
    String server = SyncPolicy.identity(
      "server.example",
      22,
      "user",
      "host-key"
    );
    int calls;

    boolean sync(
      String pending,
      boolean manual,
      boolean success,
      String remaining
    ) {
      SyncPolicy.Attempt attempt = policy.begin(server, pending, manual);
      if (attempt == null) return false;
      calls++;
      policy.finish(attempt, success, remaining);
      return true;
    }
  }

  public static void run() {
    Runner idle = new Runner();
    check(idle.sync("", false, true, ""), "First automatic sync must run");
    for (int i = 0; i < 10; i++) {
      idle.clock.now += 1000;
      check(
        !idle.sync("", false, true, ""),
        "Resume/network callbacks must reuse fresh thoughts"
      );
    }
    idle.policy = new SyncPolicy(idle.memory, idle.clock);
    check(
      !idle.sync("", false, true, ""),
      "Process recreation must preserve successful freshness"
    );
    check(
      idle.calls == 1,
      "Repeated automatic triggers must make only one request sequence"
    );
    check(
      idle.sync("", true, true, ""),
      "Manual refresh must bypass successful freshness"
    );
    idle.clock.now += SyncPolicy.FRESH_MS - 1;
    check(
      !idle.sync("", false, true, ""),
      "Freshness must last until its boundary"
    );
    idle.clock.now++;
    check(
      idle.sync("", false, true, ""),
      "Expired successful sync must refresh"
    );

    Runner failure = new Runner();
    check(failure.sync("", false, true, ""), "Baseline successful sync");
    check(
      failure.sync("edit-1", false, false, "edit-1"),
      "New local content must bypass read freshness"
    );
    for (int i = 0; i < 10; i++) check(
      !failure.sync("edit-1", false, true, ""),
      "An unchanged failed upload must not retry for every callback"
    );
    failure.clock.now += SyncPolicy.RETRY_MS - 1;
    check(
      !failure.sync("edit-1", false, false, "edit-1"),
      "Failure cooldown must last thirty seconds"
    );
    failure.clock.now++;
    check(
      failure.sync("edit-1", false, false, "edit-1"),
      "Failed content must retry after thirty seconds"
    );
    check(
      failure.sync("edit-2", false, false, "edit-2"),
      "Editing failed content must bypass its old cooldown immediately"
    );
    check(
      failure.sync("edit-2", true, false, "edit-2"),
      "Explicit sync must bypass failed-content cooldown"
    );
    check(
      failure.calls == 5,
      "Only initial, changed, expired or explicit operations may run"
    );

    Runner partial = new Runner();
    partial.sync("first+second", false, false, "second");
    check(
      !partial.sync("second", false, false, "second"),
      "Acknowledging part of a failed batch must not cause an immediate duplicate retry"
    );
    partial.sync("new-local-edit", false, false, "new-local-edit");
    check(
      partial.calls == 2,
      "A new local modification remains immediately eligible after a partial failure"
    );

    Runner offline = new Runner();
    offline.sync("", false, true, "");
    offline.sync("", true, false, "");
    offline.clock.now += SyncPolicy.RETRY_MS;
    check(
      offline.sync("", false, true, ""),
      "A failure must not preserve the previous five-minute success cache"
    );

    Runner interrupted = new Runner();
    check(
      interrupted.policy.begin(interrupted.server, "pending", false) != null,
      "Attempt must start"
    );
    interrupted.policy = new SyncPolicy(interrupted.memory, interrupted.clock);
    check(
      !interrupted.sync("pending", false, false, "pending"),
      "A process exit during I/O must preserve the retry cooldown"
    );
    interrupted.clock.now += SyncPolicy.RETRY_MS;
    check(
      interrupted.sync("pending", false, true, ""),
      "Interrupted work must become retryable"
    );

    Runner slow = new Runner();
    SyncPolicy.Attempt slowAttempt = slow.policy.begin(slow.server, "", false);
    slow.clock.now += 20_000;
    slow.policy.finish(slowAttempt, true, "");
    slow.clock.now += SyncPolicy.FRESH_MS - 1;
    check(
      !slow.sync("", false, true, ""),
      "Success freshness must start at completion, not request start"
    );
    slow.clock.now -= 1_000_000;
    check(
      slow.sync("", false, true, ""),
      "A wall-clock rollback must expire cached timestamps"
    );

    Runner servers = new Runner();
    String original = servers.server;
    servers.sync("", false, true, "");
    check(
      original.equals(
        SyncPolicy.identity("SERVER.EXAMPLE", 22, "user", "host-key")
      ),
      "DNS casing must not duplicate server state"
    );
    String[] identities = {
      SyncPolicy.identity("other.example", 22, "user", "host-key"),
      SyncPolicy.identity("server.example", 2222, "user", "host-key"),
      SyncPolicy.identity("server.example", 22, "other", "host-key"),
      SyncPolicy.identity("server.example", 22, "user", "replacement-key"),
    };
    for (String identity : identities) {
      check(
        !identity.equals(original),
        "Every server identity component must isolate sync state"
      );
      servers.server = identity;
      check(
        servers.sync("", false, true, ""),
        "Changed servers need an independent initial sync"
      );
    }
    servers.server = original;
    check(
      !servers.sync("", false, true, ""),
      "Returning to a known server must restore its own freshness"
    );
    check(
      servers.calls == 5,
      "Server state must be isolated without duplicate refreshes"
    );
  }

  public static void main(String[] args) {
    run();
    System.out.println(
      "PASS: thoughts automatic sync counts, retries, persistence, clock and server isolation"
    );
  }
}
