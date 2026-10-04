package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import app.thoughts.mobile.InteractionChecks;
import app.thoughts.mobile.MainActivity;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.connection.*;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Deterministic delayed transport: pagination, all-library search and stale response rejection. */
final class InboxPagingChecks {

  static void run(Instrumentation test, MainActivity main) throws Exception {
    Context context = test.getTargetContext();
    ServerProfile original = main.serverProfile();
    if (original == null) return;
    String previousFeature = main.activeFeature();
    test.runOnMainSync(() -> main.navigate("settings"));
    JSONObject cache;
    try (InboxStore store = new InboxStore(context)) {
      cache = store.cachedResponse(original);
    }
    ServerProfile[] current = { original };
    String[] destination = { null };
    CountDownLatch oldStarted = new CountDownLatch(1),
      releaseOld = new CountDownLatch(1),
      oldReturned = new CountDownLatch(1);
    CountDownLatch identityStarted = new CountDownLatch(1),
      releaseIdentity = new CountDownLatch(1),
      identityReturned = new CountDownLatch(1);
    AtomicInteger oldCalls = new AtomicInteger();
    Feature.Host host = new Feature.Host() {
      public Activity activity() {
        return main;
      }

      public ServerProfile serverProfile() {
        return current[0];
      }

      public ServerApi api() {
        return new ServerApi(current[0]);
      }

      public DeviceAccount account() {
        return main.account();
      }

      public void authorizationChanged() {}

      public void renderFeatureSettings(LinearLayout surface) {}

      public void configure(ServerProfile p) {
        current[0] = p;
      }

      public ExecutorService executor() {
        return main.executor();
      }

      public String activeFeature() {
        return "inbox";
      }

      public void navigate(String id) {
        destination[0] = id;
      }

      public void redraw() {}

      public void status(String message) {}
    };
    final InboxFeature[] feature = { null };
    final LinearLayout[] surface = { null };
    InboxFeature.PageLoader loader = (profile, path) -> {
      Uri uri = Uri.parse("https://fixture.invalid" + path);
      String q = uri.getQueryParameter("q"),
        type = uri.getQueryParameter("type");
      int offset = Integer.parseInt(uri.getQueryParameter("offset"));
      if ("old".equals(q) && oldCalls.incrementAndGet() == 1) {
        oldStarted.countDown();
        if (!releaseOld.await(20, TimeUnit.SECONDS)) throw new AssertionError(
          "Old response not released"
        );
        oldReturned.countDown();
        return page("old stale", false, 100, false);
      }
      if ("old".equals(q)) return page("old newest", false, 100, false);
      if ("identity".equals(q)) {
        identityStarted.countDown();
        if (
          !releaseIdentity.await(20, TimeUnit.SECONDS)
        ) throw new AssertionError("Identity response not released");
        identityReturned.countDown();
        return page("identity stale", false, 100, false);
      }
      if ("needle".equals(q)) return page(
        offset == 0 ? "needle first" : "needle later",
        offset == 0,
        offset + 100,
        false
      );
      if ("files".equals(type)) return page("remote file", false, 100, true);
      return page("initial", false, 100, false);
    };
    try {
      test.runOnMainSync(() -> {
        feature[0] = new InboxFeature(host, loader);
        surface[0] = new LinearLayout(main);
        surface[0].setOrientation(LinearLayout.VERTICAL);
        feature[0].render(surface[0]);
        main.setContentView(surface[0]);
        feature[0].enter();
      });
      awaitText(test, surface[0], "initial");
      search(test, surface[0], "old");
      TerminalChecks.check(
        oldStarted.await(5, TimeUnit.SECONDS),
        "Debounced search must reach the server"
      );
      search(test, surface[0], "needle");
      awaitText(test, surface[0], "needle first");
      test.runOnMainSync(() ->
        InteractionChecks.find(surface[0], "加载更多").performClick()
      );
      awaitText(test, surface[0], "needle later");
      test.runOnMainSync(() ->
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "needle first") != null,
          "Page two must append, not replace page one"
        )
      );
      search(test, surface[0], "old");
      awaitText(test, surface[0], "old newest");
      releaseOld.countDown();
      TerminalChecks.check(
        oldReturned.await(5, TimeUnit.SECONDS),
        "Delayed response did not return"
      );
      Thread.sleep(150);
      test.waitForIdleSync();
      test.runOnMainSync(() ->
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "old newest") != null &&
            InteractionChecks.find(surface[0], "old stale") == null,
          "An older response with the same query must not replace a newer generation"
        )
      );
      search(test, surface[0], "");
      test.runOnMainSync(() ->
        InteractionChecks.find(surface[0], "文件").performClick()
      );
      awaitText(test, surface[0], "remote file");
      test.runOnMainSync(() ->
        InteractionChecks.find(surface[0], "全部").performClick()
      );
      search(test, surface[0], "identity");
      TerminalChecks.check(
        identityStarted.await(5, TimeUnit.SECONDS),
        "Identity query did not start"
      );
      test.runOnMainSync(
        () ->
          current[0] = new ServerProfile(
            "default",
            "Other fixture",
            "other.invalid",
            22,
            "fixture",
            ""
          )
      );
      releaseIdentity.countDown();
      TerminalChecks.check(
        identityReturned.await(5, TimeUnit.SECONDS),
        "Identity response did not return"
      );
      Thread.sleep(150);
      test.waitForIdleSync();
      test.runOnMainSync(() ->
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "identity stale") == null,
          "Responses from a previous server must not enter the current view"
        )
      );
      test.runOnMainSync(() -> {
        feature[0].pause();
        current[0] = null;
        LinearLayout unconfigured = new LinearLayout(main);
        feature[0].render(unconfigured);
        InteractionChecks.find(unconfigured, "配置服务器").performClick();
        TerminalChecks.check(
          "server".equals(destination[0]),
          "Inbox configuration must open the server page, not app settings"
        );
      });
    } finally {
      releaseOld.countDown();
      releaseIdentity.countDown();
      test.runOnMainSync(() -> {
        if (feature[0] != null) {
          feature[0].pause();
          feature[0].leave();
        }
        main.redraw();
        main.navigate(previousFeature);
      });
      try (InboxStore store = new InboxStore(context)) {
        store.cache(original, cache);
      }
    }
  }

  private static void search(Instrumentation test, View root, String value) {
    test.runOnMainSync(() ->
      ((EditText) InteractionChecks.find(root, "搜索收件箱")).setText(value)
    );
  }

  private static void awaitText(Instrumentation test, View root, String label)
    throws Exception {
    TerminalChecks.await(() -> {
      boolean[] found = { false };
      test.runOnMainSync(
        () -> found[0] = InteractionChecks.find(root, label) != null
      );
      return found[0];
    }, "Missing inbox result: " + label);
  }

  private static JSONObject page(
    String title,
    boolean more,
    int offset,
    boolean file
  ) throws Exception {
    JSONArray files = new JSONArray();
    if (file) files.put(
      new JSONObject()
        .put("id", "file-fixture")
        .put("name", "remote.txt")
        .put("mime", "text/plain")
        .put("size", 4)
    );
    JSONObject item = new JSONObject()
      .put("id", title)
      .put("title", title)
      .put("text", file ? "" : title)
      .put("note", "")
      .put("files", files);
    return new JSONObject()
      .put("items", new JSONArray().put(item))
      .put("has_more", more)
      .put("next_offset", offset);
  }
}
