package app.thoughts.mobile.modules.thoughts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Deduplicates automatic triggers; an explicit sync always starts a fresh request. */
public final class SyncPolicy {

  public static final String PREFERENCES = "thoughts_sync_policy";

  public static final long FRESH_MS = 300_000L,
    RETRY_MS = 30_000L;

  public interface Storage {
    String read(String server);
    void write(String server, String state);
  }

  public static final class Attempt {

    private final String server;

    private Attempt(String server) {
      this.server = server;
    }
  }

  private final Storage storage;
  private final LongSupplier clock;

  public SyncPolicy(Storage storage, LongSupplier clock) {
    this.storage = storage;
    this.clock = clock;
  }

  public static String identity(
    String host,
    int port,
    String user,
    String key
  ) {
    return digest(
      host.toLowerCase(Locale.ROOT) +
        "\n" +
        port +
        "\n" +
        user +
        "\n" +
        key.trim()
    );
  }

  static String digest(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
        value.getBytes(StandardCharsets.UTF_8)
      );
      StringBuilder result = new StringBuilder();
      for (byte b : bytes)
        result.append(String.format(Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean recent(long now, long saved, long duration) {
    return saved > 0 && now >= saved && now - saved < duration;
  }

  public synchronized Attempt begin(
    String server,
    String pending,
    boolean manual
  ) {
    long now = clock.getAsLong(),
      success = 0,
      attempt = 0;
    String fingerprint = "";
    try {
      String[] fields = storage.read(server).split(":", -1);
      if (fields.length == 3) {
        success = Long.parseLong(fields[0]);
        attempt = Long.parseLong(fields[1]);
        fingerprint = fields[2];
      }
    } catch (RuntimeException ignored) {
      // Missing or invalid persisted state is a cache miss.
    }
    if (
      !manual &&
      ((pending.isEmpty() && recent(now, success, FRESH_MS)) ||
        (pending.equals(fingerprint) && recent(now, attempt, RETRY_MS)))
    ) return null;
    // Persist the attempt before I/O so a process restart cannot create a retry storm.
    storage.write(server, "0:" + now + ":" + pending);
    return new Attempt(server);
  }

  public synchronized void finish(
    Attempt attempt,
    boolean success,
    String pending
  ) {
    long now = clock.getAsLong();
    storage.write(
      attempt.server,
      success ? now + ":0:" : "0:" + now + ":" + pending
    );
  }
}
