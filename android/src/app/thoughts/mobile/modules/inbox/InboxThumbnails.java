package app.thoughts.mobile.modules.inbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import android.util.LruCache;
import app.thoughts.mobile.core.connection.ServerProfile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Bounded, cancellable private previews. Never adds files to the user download queue or gallery. */
final class InboxThumbnails {

  interface Callback {
    void ready(Bitmap bitmap);
  }

  private static final ExecutorService WORK =
    Executors.newSingleThreadExecutor();
  private static final LruCache<String, Bitmap> BITMAPS = new LruCache<
    String,
    Bitmap
  >(8 * 1024 * 1024) {
    protected int sizeOf(String key, Bitmap value) {
      return value.getAllocationByteCount();
    }
  };
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<String> requested = new HashSet<>();
  private final Map<String, List<Callback>> pending = new HashMap<>();
  private volatile boolean canceled;
  private volatile InboxTransfer active;

  InboxThumbnails(Context context) {
    this.context = context.getApplicationContext();
  }

  static boolean eligible(JSONObject file) {
    String type = InboxMedia.mime(file);
    return (
      Arrays.asList(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp"
      ).contains(type) &&
      file.optLong("size") > 0 &&
      file.optLong("size") <= 512 * 1024 &&
      file.optString("sha256").matches("[0-9a-f]{64}")
    );
  }

  boolean request(
    ServerProfile profile,
    JSONObject item,
    JSONObject file,
    Callback callback
  ) {
    if (canceled || profile == null || !eligible(file)) return false;
    final String key;
    try {
      key = InboxStore.hex(
        MessageDigest.getInstance("SHA-256").digest(
          (
            profile.host +
            "\n" +
            profile.user +
            "\n" +
            profile.port +
            "\n" +
            profile.knownHost +
            "\n" +
            item.getString("id") +
            "\n" +
            file.getString("id") +
            "\n" +
            file.getString("sha256")
          ).getBytes("UTF-8")
        )
      );
    } catch (Exception e) {
      return false;
    }
    Bitmap cached = BITMAPS.get(key);
    if (cached != null) {
      callback.ready(cached);
      return true;
    }
    if (pending.containsKey(key)) {
      pending.get(key).add(callback);
      return true;
    }
    if (!requested.contains(key) && requested.size() >= 6) return false;
    requested.add(key);
    List<Callback> listeners = new ArrayList<>();
    listeners.add(callback);
    pending.put(key, listeners);
    WORK.execute(() -> {
      Bitmap decoded = null;
      File partial = null;
      try {
        if (canceled) return;
        File dir = new File(context.getCacheDir(), "inbox-previews");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(
          "Preview cache unavailable"
        );
        File target = new File(dir, key);
        if (!target.isFile()) {
          partial = new File(dir, key + ".part");
          try (InboxTransfer transfer = new InboxTransfer(profile)) {
            active = transfer;
            if (canceled) return;
            transfer.download(
              item.getString("id"),
              file,
              partial,
              0,
              (int) file.getLong("size"),
              null
            );
          } finally {
            active = null;
          }
          MessageDigest hash = MessageDigest.getInstance("SHA-256");
          try (InputStream input = new FileInputStream(partial)) {
            byte[] bytes = new byte[32768];
            int n;
            while ((n = input.read(bytes)) != -1) hash.update(bytes, 0, n);
          }
          if (
            !InboxStore.hex(hash.digest()).equals(file.getString("sha256"))
          ) throw new IOException("Preview hash mismatch");
          if (!partial.renameTo(target)) throw new IOException(
            "Preview cache commit failed"
          );
        }
        if (canceled) return;
        target.setLastModified(System.currentTimeMillis());
        decoded = InboxMedia.bitmap(target, 320);
        BITMAPS.put(key, decoded);
        trim(dir);
      } catch (Exception ignored) {
      } finally {
        if (partial != null) partial.delete();
        Bitmap result = decoded;
        main.post(() -> {
          List<Callback> values = pending.remove(key);
          if (!canceled && values != null) for (Callback cb : values)
            cb.ready(result);
        });
      }
    });
    return true;
  }

  void cancel() {
    canceled = true;
    pending.clear();
    InboxTransfer transfer = active;
    if (transfer != null) new Thread(
      transfer::close,
      "inbox-preview-cancel"
    ).start();
  }

  private static void trim(File dir) {
    File[] files = dir.listFiles((parent, name) ->
      name.matches("[0-9a-f]{64}")
    );
    if (files == null) return;
    long size = 0;
    for (File file : files) size += file.length();
    if (size <= 32L * 1024 * 1024) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    for (File file : files) {
      long length = file.length();
      if (file.delete()) size -= length;
      if (size <= 24L * 1024 * 1024) break;
    }
  }
}
