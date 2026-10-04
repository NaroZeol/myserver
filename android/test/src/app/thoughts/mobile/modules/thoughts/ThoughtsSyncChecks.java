package app.thoughts.mobile.modules.thoughts;

import android.app.Instrumentation;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real SQLite checks for the queue fingerprint used by the automatic sync policy. */
public final class ThoughtsSyncChecks {

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void run(Instrumentation test) throws Exception {
    SyncPolicyChecks.run();
    try (
      Store first = new Store(test.getTargetContext());
      Store second = new Store(test.getTargetContext())
    ) {
      first.clear();
      try {
        android.content.SharedPreferences prefs = test
          .getTargetContext()
          .getSharedPreferences(SyncPolicy.PREFERENCES, 0);
        SyncPolicy policy = new SyncPolicy(
          new SyncPolicy.Storage() {
            public String read(String server) {
              return prefs.getString(server, "");
            }

            public void write(String server, String state) {
              check(
                prefs.edit().putString(server, state).commit(),
                "Fixture sync state must persist"
              );
            }
          },
          () -> 1_000_000
        );
        SyncPolicy.Attempt cached = policy.begin("fixture-server", "", false);
        policy.finish(cached, true, "");
        check(
          policy.begin("fixture-server", "", false) == null,
          "Successful empty-list sync must be cached"
        );
        second.clear();
        check(
          policy.begin("fixture-server", "", false) != null,
          "Clearing through another Store must invalidate old successful freshness"
        );
        check(
          first.pendingState().fingerprint.isEmpty(),
          "Empty database must have no upload fingerprint"
        );
        first.save(null, "first", new JSONArray(), 0);
        Store.Entry sent = first.entries().get(0);
        Store.Pending before = first.pendingState();
        second.save(
          sent.note.getString("id"),
          "edited while syncing",
          new JSONArray(),
          0
        );
        Store.Pending edited = first.pendingState();
        check(
          edited.localChanges > before.localChanges,
          "Local edits through another Store instance must be visible"
        );
        check(
          !edited.fingerprint.equals(before.fingerprint),
          "Local edits must bypass the previous queue cooldown"
        );
        first.acknowledge(
          sent,
          new JSONObject(sent.note.toString()).put("version", 1)
        );
        check(
          first.localChanges() == edited.localChanges,
          "Server acknowledgements must not pretend to be new local edits"
        );
        check(
          !first.pendingState().fingerprint.isEmpty(),
          "In-flight local changes must stay eligible for upload"
        );
        first.conflict(first.entries().get(0), "fixture conflict");
        check(
          first.pendingState().fingerprint.isEmpty(),
          "Unresolved conflicts must not repeatedly trigger full synchronization"
        );
        first.keepConflictAsCopy(first.entries().get(0));
        check(
          !first.pendingState().fingerprint.isEmpty(),
          "Resolving a conflict as a copy must create fresh upload work"
        );
        first.removeOrRestore(first.entries().get(0), false);
        check(
          first.pendingState().fingerprint.isEmpty(),
          "Local trash must not trigger upload requests"
        );
        first.removeOrRestore(first.entries().get(0), true);
        check(
          !first.pendingState().fingerprint.isEmpty(),
          "Restored local content must become eligible immediately"
        );
        Store.Entry restored = first.entries().get(0);
        long local = first.localChanges();
        first.acknowledge(
          restored,
          new JSONObject(restored.note.toString()).put("version", 1)
        );
        check(
          first.pendingState().fingerprint.isEmpty() &&
            first.localChanges() == local,
          "A successful upload must empty its queue without another local-trigger loop"
        );
      } finally {
        first.clear();
      }
    }
  }
}
