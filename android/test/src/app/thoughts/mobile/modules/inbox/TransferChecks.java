package app.thoughts.mobile.modules.inbox;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import app.thoughts.mobile.core.connection.DeviceAccess;
import app.thoughts.mobile.core.connection.ServerProfile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Real fixture SSH transfers, persistent queues, private URI import and read-only sharing. */
public final class TransferChecks {

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void run(Instrumentation test, ServerProfile profile)
    throws Exception {
    Context context = test.getTargetContext();
    DeviceAccess.enableInbox(profile, null);
    String uploadId = null,
      downloadId = null,
      emptyDownloadId = null,
      changedId = null;
    List<Uri> sources = new ArrayList<>();
    try {
      // These emulators are API 29/35, so scoped MediaStore provides genuine content grants.
      if (Build.VERSION.SDK_INT < 29) return;
      int length = InboxTransfer.CHUNK + 131071;
      Uri source = source(context, "inbox-fixture.bin", length);
      sources.add(source);
      Uri empty = source(context, "inbox-empty.bin", 0);
      sources.add(empty);
      InboxStore.Task upload;
      JSONObject big, zero;
      try (InboxStore store = new InboxStore(context)) {
        String draft = store.createDraft("fixture text", "fixture note");
        try {
          store.importFile(
            draft,
            Uri.fromFile(new File(context.getFilesDir(), "private-file")),
            null
          );
          throw new AssertionError(
            "External file URIs must not read private app paths"
          );
        } catch (IOException expected) {}
        big = store.importFile(draft, source, null);
        zero = store.importFile(draft, empty, null);
        check(
          big.getLong("size") == length && zero.getLong("size") == 0,
          "Import must record actual lengths, including empty files"
        );
        check(
          big.getString("sha256").equals(hash(store.uploadFile(big))),
          "Import must hash privately staged bytes"
        );
        upload = store.enqueueDraft(profile, draft);
        uploadId = upload.id;
        try {
          store.draft(draft);
          throw new AssertionError(
            "Sending a draft must atomically move it into the queue"
          );
        } catch (IOException expected) {}
      }
      try (
        InboxStore store = new InboxStore(context);
        InboxTransfer transfer = new InboxTransfer(profile)
      ) {
        upload = store.task(uploadId);
        check(
          "queued".equals(upload.state),
          "Queue and pinned destination must survive reopening storage"
        );
        JSONObject manifest = new JSONObject(upload.document.toString());
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) files
          .getJSONObject(i)
          .remove("_blob");
        JSONObject state = transfer.request("/inbox/uploads", "POST", manifest);
        check(
          "uploading".equals(state.getString("state")),
          "Uploading item must remain unpublished"
        );
        int first = 163841;
        transfer.upload(
          upload.id,
          big.getString("id"),
          store.uploadFile(big),
          0,
          first,
          null
        );
        JSONObject status = transfer.request(
          "/inbox/uploads/" + upload.id,
          "GET",
          null
        );
        check(
          offset(status, big.getString("id")) == first,
          "SSH upload must persist a partial-file offset"
        );
      }
      // A new SSH session resumes the partially uploaded file through the actual FGS queue.
      test.runOnMainSync(() -> TransferService.start(context));
      await(context, uploadId, "done");
      JSONObject item;
      try (
        InboxStore store = new InboxStore(context);
        InboxTransfer transfer = new InboxTransfer(profile)
      ) {
        InboxStore.Task uploaded = store.task(uploadId);
        check(
          !store
            .uploadFile(
              uploaded.document.getJSONArray("files").getJSONObject(0)
            )
            .exists(),
          "Completed upload must free its private staging copy"
        );
        item = transfer
          .request("/inbox/items/" + uploadId, "GET", null)
          .getJSONObject("item");
        check(
          item.getJSONArray("files").length() == 2 &&
            item.getString("text").equals("fixture text"),
          "Text and attachments must commit as one item"
        );
        JSONObject remoteBig = find(
          item.getJSONArray("files"),
          big.getString("id")
        );
        InboxStore.Task download = store.enqueueDownload(
          profile,
          item,
          remoteBig
        );
        downloadId = download.id;
        File partial = store.downloadPart(download);
        transfer.download(uploadId, remoteBig, partial, 0, 123457, null);
        check(
          partial.length() == 123457,
          "Interrupted download must leave resumable private bytes"
        );
      }
      test.runOnMainSync(() -> TransferService.start(context));
      await(context, downloadId, "done");
      try (InboxStore store = new InboxStore(context)) {
        InboxStore.Task downloaded = store.task(downloadId);
        File complete = store.localFile(downloaded);
        check(
          complete.length() == length &&
            hash(complete).equals(big.getString("sha256")),
          "Resumed download must match the SHA-256 of the original input"
        );
        Uri granted = InboxFiles.uri(context, downloaded);
        try (
          InputStream in = context.getContentResolver().openInputStream(granted)
        ) {
          check(
            in != null && in.read() == 0,
            "Completed download provider must expose bytes through a content URI"
          );
        }
        try {
          context.getContentResolver().openFileDescriptor(granted, "w");
          throw new AssertionError(
            "Shared inbox downloads must never be writable"
          );
        } catch (FileNotFoundException expected) {}
        try {
          context
            .getContentResolver()
            .openInputStream(
              Uri.parse(
                "content://" +
                  context.getPackageName() +
                  ".inbox.files/..%2F..%2Fdatabases%2Finbox.db"
              )
            );
          throw new AssertionError("Provider must reject path traversal");
        } catch (FileNotFoundException expected) {}
        JSONObject remoteEmpty = find(
          item.getJSONArray("files"),
          zero.getString("id")
        );
        emptyDownloadId = store.enqueueDownload(profile, item, remoteEmpty).id;
      }
      test.runOnMainSync(() -> TransferService.start(context));
      await(context, emptyDownloadId, "done");
      try (InboxStore store = new InboxStore(context)) {
        check(
          store.localFile(store.task(emptyDownloadId)).length() == 0,
          "Zero-byte downloads must become valid completed files"
        );
      }
      // Editing the active server must never redirect a previously queued task.
      try (InboxStore store = new InboxStore(context)) {
        changedId = store
          .enqueueDraft(profile, store.createDraft("old destination", ""))
          .id;
      }
      new ServerProfile(
        profile.id,
        profile.name,
        profile.host,
        profile.port == 65535 ? 65534 : profile.port + 1,
        profile.user,
        profile.knownHost
      ).save(context);
      test.runOnMainSync(() -> TransferService.start(context));
      await(context, changedId, "paused");
      profile.save(context);
      final String retained = changedId;
      test.runOnMainSync(() -> TransferService.cancel(context, retained));
      // Profile snapshots and explicit pause survive process-style store recovery.
      try (InboxStore store = new InboxStore(context)) {
        String crash = store
          .enqueueDraft(profile, store.createDraft("recover", ""))
          .id;
        check(store.claim(crash), "Queued task must be claimable exactly once");
        check(
          !store.claim(crash),
          "Concurrent worker must not claim a running task twice"
        );
        store.recover();
        check(
          "paused".equals(store.task(crash).state),
          "Interrupted transfer must wait for user continuation"
        );
        store.setState(crash, "canceled", "");
        store.removeTask(crash);
      }
    } finally {
      profile.save(context);
      test.runOnMainSync(() ->
        context.stopService(
          new android.content.Intent(context, TransferService.class)
        )
      );
      Thread.sleep(300);
      for (Uri uri : sources)
        context.getContentResolver().delete(uri, null, null);
      if (uploadId != null) {
        try (InboxTransfer transfer = new InboxTransfer(profile)) {
          transfer.request("/inbox/items/" + uploadId, "DELETE", null);
        } catch (Exception ignored) {}
      }
      try (InboxStore store = new InboxStore(context)) {
        for (String id : new String[] {
          uploadId,
          downloadId,
          emptyDownloadId,
          changedId,
        })
          if (id != null) {
            InboxStore.Task task = store.task(id);
            if (task != null) {
              store.setState(id, "canceled", "");
              store.removeTask(id);
            }
          }
      }
    }
  }

  private static long offset(JSONObject status, String id) throws Exception {
    JSONArray files = status.getJSONArray("files");
    for (int i = 0; i < files.length(); i++) if (
      id.equals(files.getJSONObject(i).getString("id"))
    ) return files.getJSONObject(i).getLong("offset");
    throw new AssertionError("Missing upload file");
  }

  private static JSONObject find(JSONArray files, String id) throws Exception {
    for (int i = 0; i < files.length(); i++) if (
      id.equals(files.getJSONObject(i).getString("id"))
    ) return files.getJSONObject(i);
    throw new AssertionError("Missing committed file");
  }

  private static Uri source(Context context, String name, int length)
    throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/myserver-ci");
    Uri uri = context
      .getContentResolver()
      .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new IOException(
      "Fixture could not create shared content"
    );
    try (
      OutputStream out = context.getContentResolver().openOutputStream(uri)
    ) {
      if (out == null) throw new IOException("Fixture source unavailable");
      byte[] bytes = new byte[65536];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
      for (int written = 0; written < length; ) {
        int n = Math.min(bytes.length, length - written);
        out.write(bytes, 0, n);
        written += n;
      }
    }
    return uri;
  }

  private static String hash(File file) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] bytes = new byte[65536];
      int n;
      while ((n = in.read(bytes)) != -1) sha.update(bytes, 0, n);
    }
    return InboxStore.hex(sha.digest());
  }

  private static void await(Context context, String id, String expected)
    throws Exception {
    long until = System.nanoTime() + 90_000_000_000L;
    String state = "missing";
    while (System.nanoTime() < until) {
      try (InboxStore store = new InboxStore(context)) {
        InboxStore.Task task = store.task(id);
        if (task != null) {
          state = task.state;
          if (expected.equals(state)) return;
          if ("failed".equals(state)) throw new AssertionError(
            "Transfer failed: " + task.error
          );
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
      "Expected " + expected + " transfer, got " + state
    );
  }
}
