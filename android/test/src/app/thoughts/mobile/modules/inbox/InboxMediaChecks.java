package app.thoughts.mobile.modules.inbox;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import java.io.*;
import java.util.UUID;
import org.json.*;

/** Verifies the private-download/gallery distinction with an actual image and MediaStore row. */
public final class InboxMediaChecks {

  public static void run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    ServerProfile profile = ServerProfile.load(context);
    if (profile == null) throw new AssertionError(
      "Media fixture needs a profile"
    );
    String name = "myserver-gallery-" + UUID.randomUUID() + ".png";
    Uri exported = null;
    InboxStore.Task task = null;
    try (InboxStore store = new InboxStore(context)) {
      JSONObject file = new JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("name", name)
        .put("mime", "application/octet-stream")
        .put("size", 0)
        .put("sha256", "");
      JSONObject item = new JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("files", new JSONArray().put(file));
      task = store.enqueueDownload(profile, item, file);
      Bitmap original = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
      original.eraseColor(0xffeee9df);
      Canvas canvas = new Canvas(original);
      Paint paint = new Paint(3);
      paint.setColor(0xff986544);
      canvas.drawCircle(230, 225, 130, paint);
      paint.setColor(0xff6b806c);
      canvas.drawRect(330, 180, 520, 370, paint);
      try (OutputStream out = new FileOutputStream(store.downloadPart(task))) {
        original.compress(Bitmap.CompressFormat.PNG, 100, out);
      } finally {
        original.recycle();
      }
      store.finishDownload(task);
      store.setState(task.id, "done", "");
      task = store.task(task.id);
      try (
        Cursor rows = context
          .getContentResolver()
          .query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[] { "_id" },
            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            new String[] { name },
            null
          )
      ) {
        TerminalChecks.check(
          rows != null && rows.getCount() == 0,
          "A private download must not silently become a public gallery image"
        );
      }
      TerminalChecks.check(
        InboxMedia.mime(file).equals("image/png"),
        "Generic MIME must fall back to the PNG filename"
      );
      exported = InboxMedia.save(context, task);
      try (
        Cursor row = context
          .getContentResolver()
          .query(
            exported,
            new String[] {
              MediaStore.MediaColumns.MIME_TYPE,
              MediaStore.MediaColumns.IS_PENDING,
              MediaStore.MediaColumns.SIZE,
            },
            null,
            null,
            null
          )
      ) {
        TerminalChecks.check(
          row != null &&
            row.moveToFirst() &&
            row.getString(0).equals("image/png") &&
            row.getInt(1) == 0 &&
            row.getLong(2) > 0,
          "Explicit gallery save must publish a complete image row"
        );
      }
      try (
        InputStream in = context.getContentResolver().openInputStream(exported)
      ) {
        Bitmap image = BitmapFactory.decodeStream(in);
        TerminalChecks.check(
          image != null && image.getWidth() == 640 && image.getHeight() == 480,
          "Gallery image must contain the original pixels"
        );
        image.recycle();
      }
      TerminalChecks.check(
        exported.equals(InboxMedia.save(context, task)),
        "Repeated save must not duplicate a gallery image"
      );
      verifyExif(context);
      verifyThumbnail(test, profile, store.localFile(task), file);
      Bitmap preview = InboxMedia.bitmap(store.localFile(task), 8);
      TerminalChecks.check(
        preview.getWidth() <= 8 && preview.getHeight() <= 8,
        "Preview decoder must bound bitmap dimensions"
      );
      preview.recycle();
      InboxPreviewActivity screen =
        (InboxPreviewActivity) test.startActivitySync(
          new Intent(context, InboxPreviewActivity.class)
            .putExtra("task_id", task.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
      try {
        TerminalChecks.await(() -> {
          boolean[] ready = { false };
          test.runOnMainSync(
            () -> ready[0] = photo(screen.getWindow().getDecorView()) != null
          );
          return ready[0];
        }, "Private image did not render");
        test.runOnMainSync(() -> {
          View root = screen.getWindow().getDecorView();
          int target = Math.round(
            48 * context.getResources().getDisplayMetrics().density
          );
          for (String label : new String[] { "返回", "更多" }) {
            View action = find(root, label);
            TerminalChecks.check(
              action instanceof android.widget.ImageButton &&
                label.contentEquals(action.getContentDescription()) &&
                label.contentEquals(action.getTooltipText()) &&
                action.getWidth() >= target &&
                action.getHeight() >= target,
              "Preview navigation must expose labelled 48dp icon actions"
            );
          }
          TextView title = (TextView) find(root, name);
          TerminalChecks.check(
            title != null &&
              title.getMaxLines() == 1 &&
              name.contentEquals(title.getTooltipText()),
            "Long filenames must keep a single-line title and complete tooltip"
          );
          InboxPreviewActivity.Photo view = photo(
            screen.getWindow().getDecorView()
          );
          long t = SystemClock.uptimeMillis();
          for (int i = 0; i < 4; i++) {
            MotionEvent e = MotionEvent.obtain(
              t,
              t + i * 40,
              i % 2 == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
              view.getWidth() / 2f,
              view.getHeight() / 2f,
              0
            );
            view.dispatchTouchEvent(e);
            e.recycle();
          }
          TerminalChecks.check(
            view.zoom > 1,
            "Double-tap must zoom the actual image view"
          );
          screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
          find(screen.getWindow().getDecorView(), "更多").performClick();
        });
        final boolean[] galleryClicked = { false };
        TerminalChecks.await(() -> {
          if (galleryClicked[0]) return true;
          AccessibilityNodeInfo root = test
            .getUiAutomation()
            .getRootInActiveWindow();
          if (root == null) return false;
          for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(
            "保存到相册"
          )) {
            while (node != null && !node.isClickable()) node = node.getParent();
            if (node != null) {
              galleryClicked[0] = node.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
              );
              return galleryClicked[0];
            }
          }
          return false;
        }, "Gallery action missing from image menu");
        TerminalChecks.await(() -> {
          boolean[] ready = { false };
          test.runOnMainSync(
            () ->
              ready[0] =
                find(screen.getWindow().getDecorView(), "已保存到相册") != null
          );
          return ready[0];
        }, "Gallery action did not finish");
        Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        if (screenshot != null) {
          File dir = new File(context.getExternalFilesDir(null), "screenshots");
          dir.mkdirs();
          try (
            OutputStream output = new FileOutputStream(
              new File(dir, "inbox-image-preview.png")
            )
          ) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
          } finally {
            screenshot.recycle();
          }
        }
      } finally {
        test.runOnMainSync(screen::finish);
      }
    } finally {
      if (exported != null) context
        .getContentResolver()
        .delete(exported, null, null);
      if (task != null) try (InboxStore store = new InboxStore(context)) {
        store.removeTask(task.id);
      }
    }
  }

  private static void verifyExif(Context context) throws Exception {
    File file = File.createTempFile(
      "inbox-orientation-",
      ".jpg",
      context.getCacheDir()
    );
    Bitmap original = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888);
    try {
      original.eraseColor(0xff986544);
      try (OutputStream output = new FileOutputStream(file)) {
        original.compress(Bitmap.CompressFormat.JPEG, 100, output);
      }
      android.media.ExifInterface exif = new android.media.ExifInterface(
        file.getPath()
      );
      exif.setAttribute(
        android.media.ExifInterface.TAG_ORIENTATION,
        String.valueOf(android.media.ExifInterface.ORIENTATION_ROTATE_90)
      );
      exif.saveAttributes();
      Bitmap rotated = InboxMedia.bitmap(file, 64);
      try {
        TerminalChecks.check(
          rotated.getWidth() == 8 && rotated.getHeight() == 12,
          "Portrait camera EXIF must rotate the sampled preview"
        );
      } finally {
        rotated.recycle();
      }
    } finally {
      original.recycle();
      file.delete();
    }
  }

  private static void verifyThumbnail(
    Instrumentation test,
    ServerProfile profile,
    File source,
    JSONObject original
  ) throws Exception {
    Context context = test.getTargetContext();
    String id = UUID.randomUUID().toString();
    InboxThumbnails[] loader = { null };
    InboxStore.Task[] promoted = { null };
    boolean[] remoteDeleted = { false };
    java.security.MessageDigest hash = java.security.MessageDigest.getInstance(
      "SHA-256"
    );
    try (InputStream input = new FileInputStream(source)) {
      byte[] bytes = new byte[8192];
      int n;
      while ((n = input.read(bytes)) != -1) hash.update(bytes, 0, n);
    }
    JSONObject file = new JSONObject(original.toString())
      .put("size", source.length())
      .put("sha256", InboxStore.hex(hash.digest()));
    JSONObject manifest = new JSONObject()
      .put("id", id)
      .put("text", "")
      .put("note", "")
      .put("source", "android")
      .put("files", new JSONArray().put(file));
    try (
      InboxTransfer transfer = new InboxTransfer(profile);
      InboxStore store = new InboxStore(context)
    ) {
      transfer.request("/inbox/uploads", "POST", manifest);
      transfer.upload(
        id,
        file.getString("id"),
        source,
        0,
        (int) source.length(),
        null
      );
      transfer.request(
        "/inbox/uploads/" + id + "/commit",
        "POST",
        new JSONObject()
      );
      JSONObject item = transfer
        .request("/inbox/items/" + id, "GET", null)
        .getJSONObject("item");
      JSONObject remote = item.getJSONArray("files").getJSONObject(0);
      int tasks = store.tasks().size();
      java.util.concurrent.CountDownLatch complete =
        new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicReference<Bitmap> result =
        new java.util.concurrent.atomic.AtomicReference<>();
      test.runOnMainSync(() -> {
        loader[0] = new InboxThumbnails(context);
        TerminalChecks.check(
          loader[0].request(profile, item, remote, bitmap -> {
            result.set(bitmap);
            complete.countDown();
          }),
          "Small remote PNG must be eligible for private thumbnail fetch"
        );
      });
      TerminalChecks.check(
        complete.await(30, java.util.concurrent.TimeUnit.SECONDS) &&
          result.get() != null &&
          result.get().getWidth() <= 320,
        "Actual SSH thumbnail must render a sampled image"
      );
      TerminalChecks.check(
        store.tasks().size() == tasks,
        "Automatic thumbnails must not pollute the download queue"
      );
      promoted[0] = store.enqueueDownload(profile, item, remote);
      TerminalChecks.check(
        promoted[0].state.equals("done") &&
          store.localFile(promoted[0]).length() == source.length(),
        "Opening or downloading a verified thumbnail must reuse its original bytes without queueing network transfer"
      );
      TerminalChecks.check(
        store.enqueueDownload(profile, item, remote).id.equals(promoted[0].id),
        "Repeated preview/download must retain the same verified local task"
      );
      test.runOnMainSync(() -> {
        loader[0].cancel();
        loader[0] = new InboxThumbnails(context);
        boolean[] reused = { false };
        loader[0].request(
          profile,
          item,
          remote,
          bitmap -> reused[0] = bitmap == result.get()
        );
        TerminalChecks.check(
          reused[0],
          "A revisited thumbnail must reuse its private decoded cache"
        );
      });
      transfer.request("/inbox/items/" + id, "DELETE", null);
      remoteDeleted[0] = true;
      java.lang.reflect.Method cacheKey =
        InboxThumbnails.class.getDeclaredMethod(
          "key",
          ServerProfile.class,
          JSONObject.class,
          JSONObject.class
        );
      cacheKey.setAccessible(true);
      File previewFile = new File(
        new File(context.getCacheDir(), "inbox-previews"),
        (String) cacheKey.invoke(null, profile, item, remote)
      );
      TerminalChecks.check(
        previewFile.delete(),
        "Thumbnail cache fixture must be removable"
      );
      java.lang.reflect.Field bitmapCache =
        InboxThumbnails.class.getDeclaredField("BITMAPS");
      bitmapCache.setAccessible(true);
      ((android.util.LruCache<?, ?>) bitmapCache.get(null)).evictAll();
      java.util.concurrent.CountDownLatch localReady =
        new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicReference<Bitmap> localBitmap =
        new java.util.concurrent.atomic.AtomicReference<>();
      test.runOnMainSync(() -> {
        loader[0].cancel();
        loader[0] = new InboxThumbnails(context);
        loader[0].request(profile, item, remote, bitmap -> {
          localBitmap.set(bitmap);
          localReady.countDown();
        });
      });
      TerminalChecks.check(
        localReady.await(10, java.util.concurrent.TimeUnit.SECONDS) &&
          localBitmap.get() != null,
        "An already-downloaded image must supply thumbnails without revisiting a deleted remote file"
      );
    } finally {
      if (loader[0] != null) test.runOnMainSync(() -> loader[0].cancel());
      if (promoted[0] != null) try (
        InboxStore store = new InboxStore(context)
      ) {
        store.removeTask(promoted[0].id);
      }
      if (!remoteDeleted[0]) try (
        InboxTransfer transfer = new InboxTransfer(profile)
      ) {
        transfer.request("/inbox/items/" + id, "DELETE", null);
      }
    }
  }

  private static InboxPreviewActivity.Photo photo(View view) {
    if (
      view instanceof InboxPreviewActivity.Photo
    ) return (InboxPreviewActivity.Photo) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        InboxPreviewActivity.Photo found = photo(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static View find(View view, String label) {
    if (
      label.equals(view.getContentDescription()) ||
      (view instanceof TextView &&
        label.contentEquals(((TextView) view).getText()))
    ) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }
}
