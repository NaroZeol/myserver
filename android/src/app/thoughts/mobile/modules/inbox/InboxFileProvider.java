package app.thoughts.mobile.modules.inbox;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import org.json.JSONObject;

/** Read-only grants for completed inbox downloads. No arbitrary path resolution. */
public final class InboxFileProvider extends ContentProvider {

  @Override
  public boolean onCreate() {
    return true;
  }

  private InboxStore.Task task(InboxStore store, Uri uri) throws Exception {
    if (
      !"content".equals(uri.getScheme()) ||
      !(getContext().getPackageName() + ".inbox.files").equals(
        uri.getAuthority()
      ) ||
      uri.getPathSegments().size() != 1 ||
      uri.getQuery() != null ||
      uri.getFragment() != null
    ) throw new FileNotFoundException("无效附件地址");
    String id = uri.getPathSegments().get(0);
    if (!id.matches("[0-9a-f-]{36}")) throw new FileNotFoundException(
      "无效附件标识"
    );
    InboxStore.Task task = store.task(id);
    if (task == null) throw new FileNotFoundException("附件不存在");
    store.localFile(task);
    return task;
  }

  @Override
  public String getType(Uri uri) {
    try (InboxStore store = new InboxStore(getContext())) {
      JSONObject f = task(store, uri).document.getJSONObject("file");
      return InboxMedia.mime(f);
    } catch (Exception e) {
      return "application/octet-stream";
    }
  }

  @Override
  public Cursor query(
    Uri uri,
    String[] projection,
    String selection,
    String[] args,
    String order
  ) {
    try (InboxStore store = new InboxStore(getContext())) {
      InboxStore.Task task = task(store, uri);
      JSONObject file = task.document.getJSONObject("file");
      String[] columns =
        projection == null
          ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
          : projection;
      MatrixCursor result = new MatrixCursor(columns);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] =
          InboxStore.cleanName(file.optString("name"));
        else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = store
          .localFile(task)
          .length();
      }
      result.addRow(row);
      return result;
    } catch (Exception e) {
      throw new IllegalArgumentException("附件不可用", e);
    }
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode)
    throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("附件只允许读取");
    try (InboxStore store = new InboxStore(getContext())) {
      return ParcelFileDescriptor.open(
        store.localFile(task(store, uri)),
        ParcelFileDescriptor.MODE_READ_ONLY
      );
    } catch (Exception e) {
      throw new FileNotFoundException("附件不可用");
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("Read only");
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException("Read only");
  }

  @Override
  public int update(
    Uri uri,
    ContentValues values,
    String where,
    String[] args
  ) {
    throw new UnsupportedOperationException("Read only");
  }
}
