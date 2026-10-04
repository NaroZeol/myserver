package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.InteractionChecks;
import app.thoughts.mobile.MainActivity;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import org.json.*;

/** Real native share/edit/filter flows, using only disposable local fixtures. */
public final class InboxUiChecks {

  public static void seedVisual(Context context) throws Exception {
    ServerProfile profile = ServerProfile.load(context);
    if (profile == null) return;
    try (InboxStore store = new InboxStore(context)) {
      store.cache(profile, sample());
    }
  }

  private static JSONObject sample() throws Exception {
    JSONArray items = new JSONArray();
    items.put(
      new JSONObject()
        .put("id", "d22c84e7-7e74-4e8c-bd09-7f5c6cc9cb71")
        .put("title", "发布前检查")
        .put("text", "把新版本发到手机，检查终端、键盘和锁屏后的连接。")
        .put("note", "桌面 → 手机")
        .put("created_at", "2026-10-05T08:32:00Z")
        .put("files", new JSONArray())
    );
    items.put(
      new JSONObject()
        .put("id", "d22c84e7-7e74-4e8c-bd09-7f5c6cc9cb72")
        .put("title", "本周构建")
        .put("text", "")
        .put("note", "已完成桌面测试")
        .put("created_at", "2026-10-05T07:15:00Z")
        .put(
          "files",
          new JSONArray().put(
            new JSONObject()
              .put("id", "d22c84e7-7e74-4e8c-bd09-7f5c6cc9cc72")
              .put("name", "myserver-preview.apk")
              .put("mime", "application/vnd.android.package-archive")
              .put("size", 481280)
              .put("sha256", String.join("", Collections.nCopies(64, "a")))
          )
        )
    );
    items.put(
      new JSONObject()
        .put("id", "d22c84e7-7e74-4e8c-bd09-7f5c6cc9cb73")
        .put("title", "https://example.com/reading")
        .put("text", "https://example.com/reading")
        .put("note", "晚点在电脑上读")
        .put("created_at", "2026-10-04T14:20:00Z")
        .put("files", new JSONArray())
    );
    return new JSONObject()
      .put("items", items)
      .put("has_more", false)
      .put(
        "storage",
        new JSONObject()
          .put("used_bytes", 481280)
          .put("quota_bytes", 1073741824)
      );
  }

  public static void run(Instrumentation test, MainActivity main)
    throws Exception {
    Context context = test.getTargetContext();
    Uri one = Uri.parse("content://fixture/one"),
      two = Uri.parse("content://fixture/two");
    Intent multiple = new Intent(Intent.ACTION_SEND_MULTIPLE)
      .setType("*/*")
      .putParcelableArrayListExtra(
        Intent.EXTRA_STREAM,
        new ArrayList<>(Arrays.asList(one, two))
      );
    ClipData clip = ClipData.newRawUri("files", one);
    clip.addItem(new ClipData.Item(two));
    multiple.setClipData(clip);
    multiple.putExtra(Intent.EXTRA_TEXT, "一组附件的说明");
    TerminalChecks.check(
      InboxShareActivity.sharedFiles(multiple).equals(Arrays.asList(one, two)),
      "Stream and ClipData duplicates must not duplicate attachments"
    );
    TerminalChecks.check(
      InboxShareActivity.sharedText(multiple).equals("一组附件的说明"),
      "A file group must retain its text"
    );
    String previous = context
      .getSharedPreferences("draft", 0)
      .getString("content", "");
    Instrumentation.ActivityMonitor monitor = test.addMonitor(
      InboxShareActivity.class.getName(),
      null,
      false
    );
    test.runOnMainSync(() ->
      main.startActivity(
        new Intent(context, MainActivity.class)
          .setAction(Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(Intent.EXTRA_TEXT, "private relay draft")
          .addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
          )
      )
    );
    InboxShareActivity share =
      (InboxShareActivity) test.waitForMonitorWithTimeout(monitor, 10000);
    test.removeMonitor(monitor);
    TerminalChecks.check(
      share != null,
      "MainActivity must route ordinary shares to the private inbox"
    );
    final String[] draftId = { null };
    try {
      test.runOnMainSync(() -> {
        EditText body = (EditText) InteractionChecks.find(
          share.getWindow().getDecorView(),
          "收件箱文字"
        );
        TerminalChecks.check(
          body != null &&
            body.getText().toString().equals("private relay draft"),
          "System share must open a private confirmation editor"
        );
        body.setText("private relay draft edited");
        share.onBackPressed();
      });
      try (InboxStore store = new InboxStore(context)) {
        JSONArray drafts = store.drafts();
        for (int i = 0; i < drafts.length(); i++) if (
          drafts
            .getJSONObject(i)
            .optString("text")
            .equals("private relay draft edited")
        ) draftId[0] = drafts.getJSONObject(i).getString("id");
      }
      TerminalChecks.check(
        draftId[0] != null,
        "Leaving the share editor must persist the private draft"
      );
      TerminalChecks.check(
        previous.equals(
          context.getSharedPreferences("draft", 0).getString("content", "")
        ),
        "Private shares must not enter the public thoughts draft"
      );
      InboxShareActivity reopened = (InboxShareActivity) test.startActivitySync(
        new Intent(context, InboxShareActivity.class)
          .putExtra("draft_id", draftId[0])
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      );
      try {
        test.runOnMainSync(() -> {
          EditText body = (EditText) InteractionChecks.find(
            reopened.getWindow().getDecorView(),
            "收件箱文字"
          );
          TerminalChecks.check(
            body.getText().toString().equals("private relay draft edited"),
            "Reopening must recover the edited draft"
          );
          reopened
            .getWindow()
            .clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        capture(test, "inbox-share.png");
        String[] destination = { null };
        Instrumentation.ActivityMonitor route =
          new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(
              Intent intent
            ) {
              destination[0] = intent.getStringExtra("open_feature");
              return new Instrumentation.ActivityResult(
                Activity.RESULT_CANCELED,
                null
              );
            }
          };
        test.addMonitor(route);
        try {
          java.lang.reflect.Method configure =
            InboxShareActivity.class.getDeclaredMethod("configureServer");
          configure.setAccessible(true);
          test.runOnMainSync(() -> {
            try {
              configure.invoke(reopened);
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          });
          TerminalChecks.check(
            "server".equals(destination[0]),
            "Share editor configuration must open the server page while retaining its draft"
          );
          try (InboxStore store = new InboxStore(context)) {
            TerminalChecks.check(
              store
                .draft(draftId[0])
                .optString("text")
                .equals("private relay draft edited"),
              "Opening server configuration must preserve the shared draft"
            );
          }
        } finally {
          test.removeMonitor(route);
        }
      } finally {
        test.runOnMainSync(reopened::finish);
      }
    } finally {
      test.runOnMainSync(share::finish);
      if (draftId[0] != null) try (InboxStore store = new InboxStore(context)) {
        store.deleteDraft(draftId[0]);
      }
    }
    ServerProfile profile = ServerProfile.load(context);
    if (profile == null) return;
    JSONObject previousCache;
    try (InboxStore store = new InboxStore(context)) {
      previousCache = store.cachedResponse(profile);
    }
    try {
      test.runOnMainSync(() -> main.navigate("inbox"));
      TerminalChecks.await(() -> {
        final boolean[] ready = { false };
        test.runOnMainSync(
          () ->
            ready[0] =
              InteractionChecks.find(
                main.getWindow().getDecorView(),
                "正在刷新…"
              ) == null
        );
        return ready[0];
      }, "Inbox refresh did not finish");
      seedVisual(context);
      java.lang.reflect.Field inboxField = MainActivity.class.getDeclaredField(
        "inbox"
      );
      inboxField.setAccessible(true);
      InboxFeature inbox = (InboxFeature) inboxField.get(main);
      test.runOnMainSync(() -> {
        main.redraw();
        View root = main.getWindow().getDecorView();
        EditText search = (EditText) InteractionChecks.find(root, "搜索收件箱");
        search.setText("myserver-preview.apk");
        TerminalChecks.check(
          InteractionChecks.find(root, "本周构建") != null,
          "Filename search must match its containing item"
        );
        TerminalChecks.check(
          InteractionChecks.find(root, "发布前检查") == null,
          "Search must hide non-matching items"
        );
        search.setText("");
        InteractionChecks.find(root, "文件").performClick();
        TerminalChecks.check(
          InteractionChecks.find(root, "本周构建") != null &&
            InteractionChecks.find(root, "发布前检查") == null,
          "File filter must use attachment presence"
        );
        View fileTitle = InteractionChecks.find(root, "本周构建");
        ((View) fileTitle.getParent()).performLongClick();
        InteractionChecks.find(root, "全选").performClick();
        TerminalChecks.check(
          InteractionChecks.find(root, "已选 1 项") != null,
          "Select all must include only visible filtered entries"
        );
        main.onBackPressed();
        InteractionChecks.find(root, "文字").performClick();
        TerminalChecks.check(
          InteractionChecks.find(root, "本周构建") == null &&
            InteractionChecks.find(root, "发布前检查") != null,
          "Text filter must use item text"
        );
        InteractionChecks.find(root, "全部").performClick();
        // Freeze only this cached presentation fixture; InboxPagingChecks exercises live requests.
        inbox.pause();
        main.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
      });
      capture(test, "inbox-list.png");
      test.runOnMainSync(() -> {
        View title = InteractionChecks.find(
          main.getWindow().getDecorView(),
          "本周构建"
        );
        ((View) title.getParent()).performClick();
        TerminalChecks.check(
          InteractionChecks.find(
            main.getWindow().getDecorView(),
            "myserver-preview.apk"
          ) != null,
          "Item detail must show its attachment name"
        );
        main.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
      });
      capture(test, "inbox-detail.png");
      test.runOnMainSync(main::onBackPressed);
    } finally {
      try (InboxStore store = new InboxStore(context)) {
        store.cache(profile, previousCache);
      }
      test.runOnMainSync(() -> main.navigate("server"));
    }
    InboxPagingChecks.run(test, main);
  }

  private static void capture(Instrumentation test, String name)
    throws Exception {
    test.waitForIdleSync();
    Thread.sleep(250);
    Bitmap image = test.getUiAutomation().takeScreenshot();
    if (image == null) return;
    File dir = new File(
      test.getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    dir.mkdirs();
    try (FileOutputStream output = new FileOutputStream(new File(dir, name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      image.recycle();
    }
  }
}
