package app.thoughts.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class Store extends SQLiteOpenHelper {

  static final class Entry {

    JSONObject note;
    String pending, error;
    int revision;

    Entry(Cursor c) throws Exception {
      note = new JSONObject(c.getString(0));
      pending = c.getString(1);
      error = c.getString(2);
      revision = c.getInt(3);
    }
  }

  Store(Context context) {
    super(context.getApplicationContext(), "thoughts.db", null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
      "CREATE TABLE notes(id TEXT PRIMARY KEY, document TEXT NOT NULL, pending TEXT, error TEXT, revision INTEGER NOT NULL DEFAULT 1)"
    );
  }

  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unsupported database migration");
  }

  synchronized List<Entry> entries() throws Exception {
    List<Entry> result = new ArrayList<>();
    try (
      Cursor c = getReadableDatabase().rawQuery(
        "SELECT document,pending,error,revision FROM notes",
        null
      )
    ) {
      while (c.moveToNext()) result.add(new Entry(c));
    }
    java.util.Collections.sort(result, (a, b) ->
      b.note.optString("created_at").compareTo(a.note.optString("created_at"))
    );
    return result;
  }

  private Entry find(String id) throws Exception {
    try (
      Cursor c = getReadableDatabase().rawQuery(
        "SELECT document,pending,error,revision FROM notes WHERE id=?",
        new String[] { id }
      )
    ) {
      return c.moveToFirst() ? new Entry(c) : null;
    }
  }

  private void write(
    JSONObject note,
    String pending,
    String error,
    int revision
  ) throws Exception {
    ContentValues values = new ContentValues();
    values.put("id", note.getString("id"));
    values.put("document", note.toString());
    values.put("pending", pending);
    values.put("error", error);
    values.put("revision", revision);
    if (
      getWritableDatabase().insertWithOnConflict(
        "notes",
        null,
        values,
        SQLiteDatabase.CONFLICT_REPLACE
      ) == -1
    ) throw new Exception("手机存储写入失败，请复制草稿备份");
  }

  synchronized void save(
    String id,
    String content,
    JSONArray tags,
    int expectedVersion
  ) throws Exception {
    Entry old = id == null ? null : find(id);
    if (id != null && old == null) throw new Exception(
      "这条想法已更新，请重新打开编辑"
    );
    if (
      old != null && old.note.optInt("version") != expectedVersion
    ) throw new Exception("同步带来了新版本；草稿已保留，请刷新后再编辑");
    if (old != null && old.error != null) throw new Exception(
      "请先处理这条想法的同步冲突"
    );
    JSONObject note =
      old == null ? new JSONObject() : new JSONObject(old.note.toString());
    if (old == null) {
      note.put("id", UUID.randomUUID().toString());
      note.put("created_at", Instant.now().toString());
      note.put("version", 0);
    }
    note.put("content", content);
    note.put("tags", tags);
    note.remove("visibility");
    note.put("updated_at", Instant.now().toString());
    note.put("deleted_at", JSONObject.NULL);
    write(
      note,
      old == null || "create".equals(old.pending) ? "create" : "update",
      null,
      old == null ? 1 : old.revision + 1
    );
  }

  synchronized void removeOrRestore(Entry selected, boolean restore)
    throws Exception {
    Entry old = find(selected.note.getString("id"));
    if (old == null || old.revision != selected.revision) throw new Exception(
      "这条想法已变更，请重新打开"
    );
    if (old.pending != null) throw new Exception(
      "请先同步这条想法，再移入或移出回收站"
    );
    old.note.put(
      "deleted_at",
      restore ? JSONObject.NULL : Instant.now().toString()
    );
    write(old.note, restore ? "restore" : "delete", null, old.revision + 1);
  }

  synchronized void acknowledge(Entry sent, JSONObject response)
    throws Exception {
    Entry current = find(sent.note.getString("id"));
    if (current == null) return;
    if (current.revision == sent.revision) {
      if ("delete".equals(sent.pending)) {
        current.note.put("version", current.note.optInt("version") + 1);
        write(current.note, null, null, current.revision + 1);
      } else write(response, null, null, current.revision + 1);
    } else {
      current.note.put("version", response.getInt("version"));
      write(
        current.note,
        "create".equals(current.pending) ? "update" : current.pending,
        null,
        current.revision + 1
      );
    }
  }

  synchronized void merge(JSONArray notes) throws Exception {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      for (int i = 0; i < notes.length(); i++) {
        JSONObject note = notes.getJSONObject(i);
        Entry old = find(note.getString("id"));
        if (old == null || old.pending == null) write(
          note,
          null,
          null,
          old == null ? 1 : old.revision + 1
        );
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  synchronized void conflict(Entry sent, String message) throws Exception {
    Entry current = find(sent.note.getString("id"));
    if (current != null) write(
      current.note,
      current.pending,
      message,
      current.revision
    );
  }

  synchronized void keepConflictAsCopy(Entry selected) throws Exception {
    Entry current = find(selected.note.getString("id"));
    if (current == null || current.error == null) throw new Exception(
      "冲突状态已经变化，请刷新"
    );
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      save(
        null,
        current.note.getString("content"),
        current.note.getJSONArray("tags"),
        0
      );
      db.delete("notes", "id=?", new String[] { current.note.getString("id") });
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  synchronized void clear() {
    getWritableDatabase().delete("notes", null, null);
  }
}
