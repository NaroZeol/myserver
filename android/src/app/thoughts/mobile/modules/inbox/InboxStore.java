package app.thoughts.mobile.modules.inbox;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.thoughts.mobile.core.connection.ServerProfile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Private, durable inbox queue. A task always retains the originally confirmed server identity. */
public final class InboxStore extends SQLiteOpenHelper {

  private static final Object LOCK = new Object();
  private static final Set<String> CLEANED = new HashSet<>();
  public static final String ACTION_CHANGED =
    "app.thoughts.mobile.inbox.CHANGED";
  private final Context context;
  private final File blobs;

  public interface ImportProgress {
    void progress(long bytes);
  }

  public static final class Task {

    public final String id, kind, state, error;
    public final JSONObject document;
    public final ServerProfile profile;
    public final long transferred, total;

    Task(Cursor c) throws Exception {
      id = c.getString(0);
      kind = c.getString(1);
      state = c.getString(2);
      error = c.getString(3);
      document = new JSONObject(c.getString(4));
      profile = decodeProfile(new JSONObject(c.getString(5)));
      transferred = c.getLong(6);
      total = c.getLong(7);
    }
  }

  public InboxStore(Context context) {
    super(context.getApplicationContext(), "inbox.db", null, 1);
    this.context = context.getApplicationContext();
    blobs = new File(this.context.getFilesDir(), "inbox/blobs");
    if (
      !blobs.isDirectory() && !blobs.mkdirs() && !blobs.isDirectory()
    ) throw new IllegalStateException("无法创建收件箱存储");
    setWriteAheadLoggingEnabled(true);
    synchronized (LOCK) {
      if (!CLEANED.contains(blobs.getAbsolutePath())) {
        try {
          cleanOrphans();
          CLEANED.add(blobs.getAbsolutePath());
        } catch (Exception ignored) {
          /* Keep all bytes when database recovery is uncertain. */
        }
      }
    }
  }

  private void cleanOrphans() throws Exception {
    Set<String> retained = new HashSet<>();
    JSONArray drafts = drafts();
    for (int i = 0; i < drafts.length(); i++) retainFiles(
      retained,
      drafts.getJSONObject(i).getJSONArray("files")
    );
    for (Task task : tasks()) {
      if ("download".equals(task.kind) && !"canceled".equals(task.state)) {
        retained.add(task.id);
        retained.add(task.id + ".part");
      } else if (
        "upload".equals(task.kind) &&
        !"done".equals(task.state) &&
        !"canceled".equals(task.state)
      ) retainFiles(retained, task.document.getJSONArray("files"));
    }
    File[] files = blobs.listFiles();
    if (files != null) for (File file : files)
      if (file.isFile() && !retained.contains(file.getName())) file.delete();
  }

  private static void retainFiles(Set<String> retained, JSONArray files)
    throws Exception {
    for (int i = 0; i < files.length(); i++) retained.add(
      files.getJSONObject(i).getString("_blob")
    );
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
      "CREATE TABLE tasks(id TEXT PRIMARY KEY,kind TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',document TEXT NOT NULL,profile TEXT NOT NULL,identity TEXT NOT NULL,transferred INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)"
    );
    db.execSQL(
      "CREATE TABLE cache(identity TEXT PRIMARY KEY,document TEXT NOT NULL)"
    );
    db.execSQL(
      "CREATE TABLE drafts(id TEXT PRIMARY KEY,document TEXT NOT NULL,created INTEGER NOT NULL)"
    );
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unsupported inbox migration");
  }

  private static JSONObject encodeProfile(ServerProfile p) throws Exception {
    return new JSONObject()
      .put("id", p.id)
      .put("name", p.name)
      .put("host", p.host)
      .put("port", p.port)
      .put("user", p.user)
      .put("key", p.knownHost);
  }

  private static ServerProfile decodeProfile(JSONObject p) throws Exception {
    return new ServerProfile(
      p.getString("id"),
      p.getString("name"),
      p.getString("host"),
      p.getInt("port"),
      p.getString("user"),
      p.getString("key")
    );
  }

  public static boolean sameIdentity(ServerProfile a, ServerProfile b) {
    return (
      a != null &&
      b != null &&
      a.sameEndpoint(b) &&
      a.knownHost.equals(b.knownHost)
    );
  }

  private static String identity(ServerProfile p) throws Exception {
    return hex(
      MessageDigest.getInstance("SHA-256").digest(
        (
          p.host.toLowerCase(Locale.ROOT) +
          "\n" +
          p.port +
          "\n" +
          p.user +
          "\n" +
          p.knownHost
        ).getBytes("UTF-8")
      )
    );
  }

  static String hex(byte[] bytes) {
    StringBuilder b = new StringBuilder();
    for (byte v : bytes) b.append(String.format(Locale.ROOT, "%02x", v & 255));
    return b.toString();
  }

  public void changed(String id) {
    context.sendBroadcast(
      new android.content.Intent(ACTION_CHANGED)
        .setPackage(context.getPackageName())
        .putExtra("task_id", id)
    );
  }

  public List<Task> tasks() throws Exception {
    return readTasks(null);
  }

  public List<Task> tasks(ServerProfile p) throws Exception {
    return p == null ? Collections.emptyList() : readTasks(identity(p));
  }

  private List<Task> readTasks(String identity) throws Exception {
    synchronized (LOCK) {
      List<Task> out = new ArrayList<>();
      try (
        Cursor c = getReadableDatabase().rawQuery(
          "SELECT id,kind,state,error,document,profile,transferred,total FROM tasks" +
            (identity == null ? "" : " WHERE identity=?") +
            " ORDER BY created DESC",
          identity == null ? null : new String[] { identity }
        )
      ) {
        while (c.moveToNext()) out.add(new Task(c));
      }
      return out;
    }
  }

  public Task task(String id) throws Exception {
    synchronized (LOCK) {
      try (
        Cursor c = getReadableDatabase().rawQuery(
          "SELECT id,kind,state,error,document,profile,transferred,total FROM tasks WHERE id=?",
          new String[] { id }
        )
      ) {
        return c.moveToFirst() ? new Task(c) : null;
      }
    }
  }

  public JSONObject cachedResponse(ServerProfile p) throws Exception {
    if (p == null) return new JSONObject().put("items", new JSONArray());
    synchronized (LOCK) {
      try (
        Cursor c = getReadableDatabase().rawQuery(
          "SELECT document FROM cache WHERE identity=?",
          new String[] { identity(p) }
        )
      ) {
        return c.moveToFirst()
          ? new JSONObject(c.getString(0))
          : new JSONObject().put("items", new JSONArray());
      }
    }
  }

  public JSONArray entries(ServerProfile p) throws Exception {
    return cachedResponse(p).optJSONArray("items");
  }

  public void cache(ServerProfile p, JSONObject response) throws Exception {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("identity", identity(p));
      v.put("document", response.toString());
      checkedInsert("cache", v);
    }
    changed("");
  }

  private void checkedInsert(String table, ContentValues v) {
    if (
      getWritableDatabase().insertWithOnConflict(
        table,
        null,
        v,
        SQLiteDatabase.CONFLICT_REPLACE
      ) < 0
    ) throw new IllegalStateException("手机存储写入失败");
  }

  private static void validateText(String text, String note)
    throws IOException {
    if (
      text != null && text.codePointCount(0, text.length()) > 20000
    ) throw new IOException("文字最多 20000 字");
    if (
      note != null && note.codePointCount(0, note.length()) > 2000
    ) throw new IOException("备注最多 2000 字");
  }

  public String createDraft(String text, String note) throws Exception {
    validateText(text, note);
    String id = UUID.randomUUID().toString();
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("id", id);
      v.put("created", System.currentTimeMillis());
      v.put(
        "document",
        new JSONObject()
          .put("id", id)
          .put("text", text == null ? "" : text)
          .put("note", note == null ? "" : note)
          .put("files", new JSONArray())
          .toString()
      );
      checkedInsert("drafts", v);
    }
    return id;
  }

  public JSONObject draft(String id) throws Exception {
    synchronized (LOCK) {
      try (
        Cursor c = getReadableDatabase().rawQuery(
          "SELECT document FROM drafts WHERE id=?",
          new String[] { id }
        )
      ) {
        if (!c.moveToFirst()) throw new IOException("草稿不存在");
        return new JSONObject(c.getString(0));
      }
    }
  }

  public JSONArray drafts() throws Exception {
    synchronized (LOCK) {
      JSONArray out = new JSONArray();
      try (
        Cursor c = getReadableDatabase().rawQuery(
          "SELECT document FROM drafts ORDER BY created DESC",
          null
        )
      ) {
        while (c.moveToNext()) out.put(new JSONObject(c.getString(0)));
      }
      return out;
    }
  }

  private void writeDraft(String id, JSONObject document) {
    ContentValues v = new ContentValues();
    v.put("document", document.toString());
    if (
      getWritableDatabase().update("drafts", v, "id=?", new String[] { id }) !=
      1
    ) throw new IllegalStateException("草稿不存在");
  }

  public void updateDraft(String id, String text, String note)
    throws Exception {
    validateText(text, note);
    synchronized (LOCK) {
      JSONObject d = draft(id);
      d.put("text", text).put("note", note);
      writeDraft(id, d);
    }
  }

  public JSONObject importFile(String draftId, Uri uri, ImportProgress progress)
    throws Exception {
    if (
      uri == null || !"content".equals(uri.getScheme())
    ) throw new IOException("请选择本地文件");
    synchronized (LOCK) {
      if (
        draft(draftId).getJSONArray("files").length() >= 32
      ) throw new IOException("每次投递最多包含 32 个附件");
    }
    String name = "附件";
    long declared = -1;
    try (
      Cursor c = context
        .getContentResolver()
        .query(
          uri,
          new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
          null,
          null,
          null
        )
    ) {
      if (c != null && c.moveToFirst()) {
        int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME),
          s = c.getColumnIndex(OpenableColumns.SIZE);
        if (n >= 0 && !c.isNull(n)) name = c.getString(n);
        if (s >= 0 && !c.isNull(s)) declared = c.getLong(s);
      }
    } catch (RuntimeException ignored) {}
    name = cleanName(name);
    String mime = context.getContentResolver().getType(uri);
    if (mime == null) mime = "application/octet-stream";
    String blob = UUID.randomUUID().toString();
    File target = blob(blob),
      partial = new File(blobs, blob + ".import");
    long bytes = 0;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try {
      if (
        declared > 0 && declared > blobs.getUsableSpace() - 16 * 1024 * 1024L
      ) throw new IOException("手机空间不足，无法导入附件");
      try (
        InputStream in = context.getContentResolver().openInputStream(uri);
        FileOutputStream out = new FileOutputStream(partial)
      ) {
        if (in == null) throw new IOException("无法读取附件");
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (
            bytes % 1048576 < 65536 &&
            blobs.getUsableSpace() < 16 * 1024 * 1024L
          ) throw new IOException("手机空间不足，无法导入附件");
          if (
            Thread.currentThread().isInterrupted()
          ) throw new InterruptedIOException("导入已取消");
          if (n == 0) continue;
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
          bytes += n;
          if (progress != null) progress.progress(bytes);
        }
        out.getFD().sync();
      }
      if (!partial.renameTo(target)) throw new IOException("附件保存失败");
      JSONObject f = new JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("name", name)
        .put("mime", mime)
        .put("size", bytes)
        .put("sha256", hex(digest.digest()))
        .put("_blob", blob);
      synchronized (LOCK) {
        JSONObject d = draft(draftId);
        d.getJSONArray("files").put(f);
        writeDraft(draftId, d);
      }
      return f;
    } catch (Exception e) {
      target.delete();
      throw e;
    } finally {
      partial.delete();
    }
  }

  static String cleanName(String name) {
    if (name == null || name.trim().isEmpty()) return "附件";
    name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
    if (name.equals(".") || name.equals("..")) name = "附件";
    return name.codePointCount(0, name.length()) > 200
      ? name.substring(0, name.offsetByCodePoints(0, 200))
      : name;
  }

  public void removeDraftFile(String id, String fileId) throws Exception {
    synchronized (LOCK) {
      JSONObject d = draft(id);
      JSONArray old = d.getJSONArray("files"),
        files = new JSONArray();
      String remove = null;
      for (int i = 0; i < old.length(); i++) {
        JSONObject f = old.getJSONObject(i);
        if (f.getString("id").equals(fileId)) remove = f.getString("_blob");
        else files.put(f);
      }
      d.put("files", files);
      writeDraft(id, d);
      if (remove != null) blob(remove).delete();
    }
  }

  public void deleteDraft(String id) throws Exception {
    synchronized (LOCK) {
      JSONObject d = draft(id);
      getWritableDatabase().delete("drafts", "id=?", new String[] { id });
      JSONArray files = d.getJSONArray("files");
      for (int i = 0; i < files.length(); i++) blob(
        files.getJSONObject(i).getString("_blob")
      ).delete();
    }
  }

  public Task enqueueUpload(
    ServerProfile p,
    String text,
    String note,
    List<Uri> uris
  ) throws Exception {
    String id = createDraft(text, note);
    for (Uri uri : uris) importFile(id, uri, null);
    return enqueueDraft(p, id);
  }

  public Task enqueueDraft(ServerProfile p, String draftId) throws Exception {
    if (p == null || p.knownHost.isEmpty()) throw new IOException(
      "请先连接服务器"
    );
    synchronized (LOCK) {
      JSONObject d = draft(draftId);
      if (
        d.optString("text").trim().isEmpty() &&
        d.getJSONArray("files").length() == 0
      ) throw new IOException("请输入文字或选择附件");
      d.put("source", "Android");
      long total = 0;
      JSONArray f = d.getJSONArray("files");
      for (int i = 0; i < f.length(); i++) total += f
        .getJSONObject(i)
        .getLong("size");
      SQLiteDatabase db = getWritableDatabase();
      db.beginTransaction();
      try {
        insertTask(draftId, "upload", p, d, total);
        db.delete("drafts", "id=?", new String[] { draftId });
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
      changed(draftId);
      return task(draftId);
    }
  }

  private void insertTask(
    String id,
    String kind,
    ServerProfile p,
    JSONObject document,
    long total
  ) throws Exception {
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("kind", kind);
    v.put("state", "queued");
    v.put("error", "");
    v.put("document", document.toString());
    v.put("profile", encodeProfile(p).toString());
    v.put("identity", identity(p));
    v.put("total", total);
    v.put("created", System.currentTimeMillis());
    checkedInsert("tasks", v);
  }

  public Task enqueueDownload(ServerProfile p, JSONObject item, JSONObject file)
    throws Exception {
    synchronized (LOCK) {
      Task existing = findDownload(
        p,
        item.getString("id"),
        file.getString("id")
      );
      if (existing != null && !"canceled".equals(existing.state)) {
        if (
          "done".equals(existing.state) && !blob(existing.id).isFile()
        ) setState(existing.id, "queued", "");
        if (
          "failed".equals(existing.state) || "paused".equals(existing.state)
        ) setState(existing.id, "queued", "");
        return task(existing.id);
      }
      String id = UUID.randomUUID().toString();
      insertTask(
        id,
        "download",
        p,
        new JSONObject().put("item", item).put("file", file),
        file.getLong("size")
      );
      changed(id);
      return task(id);
    }
  }

  public Task findDownload(ServerProfile p, String itemId, String fileId)
    throws Exception {
    for (Task task : tasks(p))
      if (
        "download".equals(task.kind) &&
        task.document.getJSONObject("item").optString("id").equals(itemId) &&
        task.document.getJSONObject("file").optString("id").equals(fileId) &&
        !"canceled".equals(task.state)
      ) return task;
    return null;
  }

  public boolean claim(String id) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("state", "running");
      v.put("error", "");
      boolean claimed =
        getWritableDatabase().update(
          "tasks",
          v,
          "id=? AND state='queued'",
          new String[] { id }
        ) == 1;
      if (claimed) changed(id);
      return claimed;
    }
  }

  public void setState(String id, String state, String error) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("state", state);
      v.put("error", error == null ? "" : error);
      getWritableDatabase().update("tasks", v, "id=?", new String[] { id });
    }
    changed(id);
  }

  public void updateDocument(String id, JSONObject document) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("document", document.toString());
      getWritableDatabase().update("tasks", v, "id=?", new String[] { id });
    }
    changed(id);
  }

  public boolean transition(
    String id,
    String expected,
    String state,
    String error
  ) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("state", state);
      v.put("error", error == null ? "" : error);
      boolean changed =
        getWritableDatabase().update(
          "tasks",
          v,
          "id=? AND state=?",
          new String[] { id, expected }
        ) == 1;
      if (changed) changed(id);
      return changed;
    }
  }

  public void progress(String id, long transferred) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues();
      v.put("transferred", transferred);
      getWritableDatabase().update("tasks", v, "id=?", new String[] { id });
    }
    changed(id);
  }

  public void recover() {
    synchronized (LOCK) {
      getWritableDatabase().execSQL(
        "UPDATE tasks SET state='paused',error='传输已中断，点击继续' WHERE state='running'"
      );
    }
  }

  File blob(String id) throws IOException {
    if (!id.matches("[0-9a-f-]{36}")) throw new IOException("附件标识无效");
    return new File(blobs, id);
  }

  File uploadFile(JSONObject file) throws Exception {
    return blob(file.getString("_blob"));
  }

  File downloadPart(Task task) throws IOException {
    File part = new File(blobs, task.id + ".part"),
      finalized = blob(task.id);
    // A crash between rename and the database update must keep the verified bytes resumable.
    if (!part.exists() && finalized.isFile() && !"done".equals(task.state)) {
      if (!finalized.renameTo(part)) throw new IOException(
        "无法恢复已下载的附件"
      );
    }
    return part;
  }

  public File localFile(Task task) throws Exception {
    Task latest = task(task.id);
    if (
      latest == null ||
      !"download".equals(latest.kind) ||
      !"done".equals(latest.state)
    ) throw new IOException("请先完成下载");
    File file = blob(latest.id);
    if (!file.isFile()) throw new IOException("下载文件已移除，请重新下载");
    return file;
  }

  void finishDownload(Task task) throws Exception {
    File part = downloadPart(task),
      target = blob(task.id);
    if (!part.renameTo(target)) throw new IOException("下载文件保存失败");
  }

  void discardBytes(Task task) throws Exception {
    if ("download".equals(task.kind)) {
      downloadPart(task).delete();
      blob(task.id).delete();
    } else {
      JSONArray files = task.document.getJSONArray("files");
      for (int i = 0; i < files.length(); i++) uploadFile(
        files.getJSONObject(i)
      ).delete();
    }
  }

  public void removeTask(String id) throws Exception {
    synchronized (LOCK) {
      Task t = task(id);
      if (t == null) return;
      if (
        "running".equals(t.state) || "queued".equals(t.state)
      ) throw new IOException("请先取消传输");
      discardBytes(t);
      getWritableDatabase().delete("tasks", "id=?", new String[] { id });
    }
    changed(id);
  }
}
