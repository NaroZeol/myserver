package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.Context;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import app.thoughts.mobile.InteractionChecks;
import app.thoughts.mobile.MainActivity;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.connection.*;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Download badges are local availability, never selection or permission to mutate the server. */
final class InboxAvailabilityChecks {

  static void run(Instrumentation test, MainActivity main) throws Exception {
    Context context = test.getTargetContext();
    ServerProfile profile = new ServerProfile(
      "availability",
      "Local fixture",
      "downloads.invalid",
      22,
      "fixture",
      ""
    );
    ServerProfile foreign = new ServerProfile(
      "foreign",
      "Other fixture",
      "other-downloads.invalid",
      22,
      "fixture",
      ""
    );
    ServerProfile[] current = { profile };
    String previous = main.activeFeature();
    AtomicInteger requests = new AtomicInteger();
    List<String> created = new ArrayList<>();
    InboxFeature[] feature = { null };
    LinearLayout[] surface = { null },
      footer = { null };
    JSONObject file = file("downloaded.txt"),
      missing = file("missing.txt"),
      waiting = file("remote.txt");
    JSONObject group = item(
      "Partially downloaded",
      new JSONArray().put(file).put(missing).put(waiting)
    );
    JSONObject orphanFile = file("offline-only.txt"),
      orphan = item("Only on this phone", new JSONArray().put(orphanFile));
    JSONObject foreignFile = file("other-server.txt"),
      foreignItem = item("Foreign download", new JSONArray().put(foreignFile));
    try (InboxStore store = new InboxStore(context)) {
      InboxStore.Task ready = complete(store, profile, group, file);
      created.add(ready.id);
      InboxStore.Task lost = complete(store, profile, group, missing);
      created.add(lost.id);
      store.localFile(lost).delete();
      InboxStore.Task offline = complete(store, profile, orphan, orphanFile);
      created.add(offline.id);
      InboxStore.Task elsewhere = complete(
        store,
        foreign,
        foreignItem,
        foreignFile
      );
      created.add(elsewhere.id);
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

        public void renderFeatureSettings(LinearLayout v) {}

        public void configure(ServerProfile p) {}

        public ExecutorService executor() {
          return main.executor();
        }

        public String activeFeature() {
          return "inbox";
        }

        public void navigate(String id) {}

        public void redraw() {
          surface[0].removeAllViews();
          footer[0].removeAllViews();
          feature[0].render(surface[0]);
          feature[0].renderFooter(footer[0]);
        }

        public void status(String message) {}
      };
      test.runOnMainSync(() -> {
        main.navigate("settings");
        LinearLayout root = new LinearLayout(main);
        root.setOrientation(1);
        root.setBackgroundColor(app.thoughts.mobile.core.Ui.PAPER);
        int padding = Math.round(
          20 * main.getResources().getDisplayMetrics().density
        );
        root.setOnApplyWindowInsetsListener((v, inset) -> {
          v.setPadding(
            padding,
            inset.getSystemWindowInsetTop() + padding,
            padding,
            inset.getSystemWindowInsetBottom()
          );
          return inset;
        });
        surface[0] = new LinearLayout(main);
        surface[0].setOrientation(1);
        footer[0] = new LinearLayout(main);
        footer[0].setOrientation(1);
        ScrollView scroll = new ScrollView(main);
        scroll.addView(surface[0]);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(footer[0]);
        main.setContentView(root);
        feature[0] = new InboxFeature(host, (p, path) -> {
          requests.incrementAndGet();
          return new JSONObject()
            .put("items", new JSONArray().put(group))
            .put("has_more", false);
        });
        host.redraw();
        feature[0].enter();
      });
      await(test, surface[0], "Partially downloaded");
      capture(test, main, "inbox-downloads-partial.png");
      test.runOnMainSync(() -> {
        View badge = InteractionChecks.find(surface[0], "已下载 1/3 个文件");
        TerminalChecks.check(
          badge != null &&
            !badge.isClickable() &&
            "已下载 1/3 个文件".contentEquals(badge.getTooltipText()),
          "Partial availability must be a non-interactive accessible download badge with count"
        );
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "本机文件") == null,
          "Downloads must not retain a separate prominent local-files button"
        );
        View refresh = InteractionChecks.find(surface[0], "刷新");
        int target = Math.round(
          48 * context.getResources().getDisplayMetrics().density
        );
        TerminalChecks.check(
          refresh.getMinimumWidth() >= target &&
            refresh.getMinimumHeight() >= target,
          "Refresh icon must retain a 48dp touch target"
        );
        View row = (View) InteractionChecks.find(
          surface[0],
          "Partially downloaded"
        ).getParent();
        row.performLongClick();
        View chosen = (View) InteractionChecks.find(
          surface[0],
          "Partially downloaded"
        ).getParent();
        AccessibilityNodeInfo info = chosen.createAccessibilityNodeInfo();
        TerminalChecks.check(
          info.isCheckable() && info.isChecked(),
          "Selection must expose checkbox semantics independently of downloaded status"
        );
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "已下载 1/3 个文件") != null,
          "Selection must not replace the download badge"
        );
      });
      capture(test, main, "inbox-downloads-selection.png");
      test.runOnMainSync(() -> {
        feature[0].back();
        InteractionChecks.find(surface[0], "筛选").performClick();
      });
      clickDialog(test, "已下载");
      await(test, surface[0], "Only on this phone");
      capture(test, main, "inbox-downloads-local.png");
      compactFilters(test, surface[0]);
      int before = requests.get();
      test.runOnMainSync(() -> {
        feature[0].enter();
        feature[0].pause();
        feature[0].resume();
        feature[0].refresh();
      });
      Thread.sleep(400);
      TerminalChecks.check(
        requests.get() == before,
        "Downloaded view must not contact the server even on explicit refresh"
      );
      test.runOnMainSync(() ->
        ((EditText) InteractionChecks.find(surface[0], "搜索收件箱")).setText(
          "remote.txt"
        )
      );
      Thread.sleep(400);
      test.runOnMainSync(() -> {
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "Partially downloaded") == null,
          "Downloaded search must not match filenames that are only on the server"
        );
        ((EditText) InteractionChecks.find(surface[0], "搜索收件箱")).setText(
          ""
        );
      });
      await(test, surface[0], "Partially downloaded");
      Instrumentation.ActivityMonitor previewRoute = test.addMonitor(
        InboxPreviewActivity.class.getName(),
        new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
        true
      );
      try {
        test.runOnMainSync(() -> {
          TerminalChecks.check(
            InteractionChecks.find(surface[0], "Foreign download") == null,
            "Downloaded view must be isolated to the current server"
          );
          (
            (View) InteractionChecks.find(
              surface[0],
              "Partially downloaded"
            ).getParent()
          ).performClick();
          TerminalChecks.check(
            InteractionChecks.find(surface[0], "missing.txt") == null &&
              InteractionChecks.find(surface[0], "remote.txt") == null,
            "Local detail must not offer missing or not-yet-downloaded files"
          );
          (
            (View) InteractionChecks.find(
              surface[0],
              "downloaded.txt"
            ).getParent()
          ).performClick();
        });
        TerminalChecks.check(
          previewRoute.getHits() == 1,
          "Tapping a downloaded previewable file must open preview directly"
        );
      } finally {
        test.removeMonitor(previewRoute);
      }
      test.runOnMainSync(() ->
        InteractionChecks.find(
          surface[0],
          "文件操作：downloaded.txt"
        ).performClick()
      );
      clickDialog(test, "移除手机副本");
      clickDialog(test, "移除");
      TerminalChecks.await(() -> {
        try {
          return store.task(ready.id) == null;
        } catch (Exception e) {
          return false;
        }
      }, "Removing the phone copy must remove its local record");
      test.runOnMainSync(() -> {
        InteractionChecks.find(surface[0], "全部").performClick();
      });
      await(test, surface[0], "Partially downloaded");
      test.runOnMainSync(() ->
        TerminalChecks.check(
          InteractionChecks.find(surface[0], "Partially downloaded") != null &&
            InteractionChecks.find(surface[0], "已下载 1/3 个文件") == null,
          "Removing a copy must restore its undownloaded status in the server list"
        )
      );
      Thread.sleep(400);
      TerminalChecks.check(
        requests.get() == before,
        "Removing a phone copy must not mutate or refetch the server"
      );
      test.runOnMainSync(() ->
        InteractionChecks.find(surface[0], "筛选").performClick()
      );
      clickDialog(test, "其他服务器的下载");
      awaitDialog(test, "other-server.txt");
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      test.runOnMainSync(() -> {
        (
          (View) InteractionChecks.find(
            surface[0],
            "Partially downloaded"
          ).getParent()
        ).performLongClick();
        TerminalChecks.check(
          feature[0].hasBack(),
          "Selection fixture must enter selection mode"
        );
        current[0] = foreign;
        host.redraw();
        TerminalChecks.check(
          !feature[0].hasBack() &&
            InteractionChecks.find(footer[0], "已选 1 项") == null,
          "Changing server must discard the previous server's selected IDs"
        );
        current[0] = profile;
        host.redraw();
        (
          (View) InteractionChecks.find(
            surface[0],
            "Partially downloaded"
          ).getParent()
        ).performClick();
        TerminalChecks.check(
          feature[0].hasBack(),
          "Detail fixture must open an item"
        );
        current[0] = foreign;
        host.redraw();
        TerminalChecks.check(
          !feature[0].hasBack() && feature[0].headerAction().equals("添加"),
          "Changing server must discard the previous server's detail target"
        );
      });
      TerminalChecks.check(
        requests.get() == before,
        "Changing local view identity must not mutate either server"
      );
    } finally {
      test.runOnMainSync(() -> {
        if (feature[0] != null) {
          feature[0].pause();
          feature[0].leave();
        }
        main.redraw();
        main.navigate(previous);
      });
      try (InboxStore store = new InboxStore(context)) {
        for (String id : created) store.removeTask(id);
        store.invalidateLists(profile);
        store.invalidateLists(foreign);
      }
    }
  }

  /** Measure the real filter buttons at a 320dp viewport with 24dp margins and 1.3x text. */
  private static void compactFilters(Instrumentation test, View root)
    throws Exception {
    android.graphics.Bitmap[] picture = { null };
    test.runOnMainSync(() -> {
      Button downloaded = (Button) InteractionChecks.find(root, "已下载");
      TerminalChecks.check(
        downloaded != null,
        "Downloaded filter must be visible before compact measurement"
      );
      LinearLayout filters = (LinearLayout) downloaded.getParent();
      float density = root.getResources().getDisplayMetrics().density;
      int width = Math.round((320 - 48) * density),
        target = Math.round(48 * density);
      int left = filters.getLeft(),
        top = filters.getTop(),
        right = filters.getRight(),
        bottom = filters.getBottom();
      float[] sizes = new float[filters.getChildCount()];
      try {
        for (int i = 0; i < filters.getChildCount(); i++) {
          Button button = (Button) filters.getChildAt(i);
          sizes[i] = button.getTextSize();
          button.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            sizes[i] * 1.3f
          );
        }
        filters.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        filters.layout(0, 0, width, filters.getMeasuredHeight());
        for (int i = 0; i < filters.getChildCount(); i++) {
          Button button = (Button) filters.getChildAt(i);
          android.text.Layout text = button.getLayout();
          TerminalChecks.check(
            button.getWidth() >= target && button.getHeight() >= target,
            "Compact filter touch targets must remain at least 48dp: " +
              button.getText()
          );
          TerminalChecks.check(
            button.getLeft() >= 0 &&
              button.getRight() <= width &&
              text != null &&
              text.getLineCount() > 0,
            "Compact filter must remain within the content width: " +
              button.getText()
          );
          int last = text.getLineCount() - 1;
          TerminalChecks.check(
            text.getLineBottom(last) <=
              button.getHeight() -
                button.getCompoundPaddingTop() -
                button.getCompoundPaddingBottom() &&
              text.getLineEnd(last) == button.getText().length() &&
              text.getEllipsisCount(last) == 0,
            "Every line of compact filter text must remain vertically visible: " +
              button.getText()
          );
        }
        picture[0] = android.graphics.Bitmap.createBitmap(
          width,
          filters.getHeight(),
          android.graphics.Bitmap.Config.ARGB_8888
        );
        android.graphics.Canvas canvas = new android.graphics.Canvas(
          picture[0]
        );
        canvas.drawColor(app.thoughts.mobile.core.Ui.PAPER);
        filters.draw(canvas);
      } finally {
        for (int i = 0; i < filters.getChildCount(); i++) (
          (Button) filters.getChildAt(i)
        ).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizes[i]);
        filters.measure(
          View.MeasureSpec.makeMeasureSpec(
            right - left,
            View.MeasureSpec.EXACTLY
          ),
          View.MeasureSpec.makeMeasureSpec(
            bottom - top,
            View.MeasureSpec.EXACTLY
          )
        );
        filters.layout(left, top, right, bottom);
        filters.requestLayout();
      }
    });
    File dir = new File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    dir.mkdirs();
    try (
      OutputStream out = new FileOutputStream(
        new File(dir, "inbox-downloaded-filter-compact.png")
      )
    ) {
      picture[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      if (picture[0] != null) picture[0].recycle();
    }
  }

  private static void capture(
    Instrumentation test,
    MainActivity main,
    String name
  ) throws Exception {
    test.runOnMainSync(() ->
      main.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    );
    InboxUiChecks.capture(test, name);
  }

  private static InboxStore.Task complete(
    InboxStore store,
    ServerProfile profile,
    JSONObject item,
    JSONObject file
  ) throws Exception {
    InboxStore.Task task = store.enqueueDownload(profile, item, file);
    try (OutputStream out = new FileOutputStream(store.downloadPart(task))) {
      out.write(new byte[] { 1, 2, 3, 4 });
    }
    store.finishDownload(task);
    store.setState(task.id, "done", "");
    return store.task(task.id);
  }

  private static JSONObject file(String name) throws Exception {
    return new JSONObject()
      .put("id", UUID.randomUUID().toString())
      .put("name", name)
      .put("mime", "text/plain")
      .put("size", 4)
      .put("sha256", String.join("", Collections.nCopies(64, "a")));
  }

  private static JSONObject item(String title, JSONArray files)
    throws Exception {
    return new JSONObject()
      .put("id", UUID.randomUUID().toString())
      .put("title", title)
      .put("text", "")
      .put("note", "")
      .put("files", files)
      .put("created_at", "2026-10-05T00:00:00Z");
  }

  private static void await(Instrumentation test, View root, String text)
    throws Exception {
    TerminalChecks.await(() -> {
      boolean[] found = { false };
      test.runOnMainSync(
        () -> found[0] = InteractionChecks.find(root, text) != null
      );
      return found[0];
    }, "Missing local UI: " + text);
  }

  private static AccessibilityNodeInfo awaitDialog(
    Instrumentation test,
    String text
  ) throws Exception {
    AccessibilityNodeInfo[] result = { null };
    TerminalChecks.await(() -> {
      AccessibilityNodeInfo root = test
        .getUiAutomation()
        .getRootInActiveWindow();
      if (root != null) {
        List<AccessibilityNodeInfo> nodes =
          root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes)
          if (text.contentEquals(node.getText())) {
            result[0] = node;
            return true;
          }
        if (!nodes.isEmpty()) {
          result[0] = nodes.get(0);
          return true;
        }
      }
      return false;
    }, "Missing dialog action: " + text);
    return result[0];
  }

  private static void clickDialog(Instrumentation test, String text)
    throws Exception {
    AccessibilityNodeInfo node = awaitDialog(test, text);
    while (node != null && !node.isClickable()) node = node.getParent();
    TerminalChecks.check(
      node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
      "Dialog action did not click: " + text
    );
    test.waitForIdleSync();
    Thread.sleep(150);
  }
}
