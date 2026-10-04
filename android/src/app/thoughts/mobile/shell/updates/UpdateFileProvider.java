package app.thoughts.mobile.shell.updates;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read grants are limited to the one verified installation package. */
public final class UpdateFileProvider extends ContentProvider {

  public boolean onCreate() {
    return true;
  }

  private File checked(Uri uri) throws FileNotFoundException {
    try {
      UpdateCatalog.Release release = UpdateManager.pending(getContext());
      if (
        release == null ||
        !uri
          .getAuthority()
          .equals(getContext().getPackageName() + ".updates") ||
        uri.getPathSegments().size() != 1 ||
        !uri.getLastPathSegment().equals(release.sha256) ||
        uri.getQuery() != null ||
        !UpdateManager.preferences(getContext())
          .getString("download_state", "")
          .equals("ready")
      ) throw new IOException();
      File file = UpdateManager.file(getContext(), release);
      UpdateManager.verify(getContext(), file, release);
      return file;
    } catch (Exception e) {
      throw new FileNotFoundException("安装包尚未通过校验");
    }
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode)
    throws FileNotFoundException {
    if (!mode.equals("r")) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(
      checked(uri),
      ParcelFileDescriptor.MODE_READ_ONLY
    );
  }

  public String getType(Uri uri) {
    return "application/vnd.android.package-archive";
  }

  public Cursor query(
    Uri uri,
    String[] projection,
    String selection,
    String[] arguments,
    String sort
  ) {
    try {
      File file = checked(uri);
      String[] columns =
        projection == null
          ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
          : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) row[i] = columns[i].equals(
        OpenableColumns.DISPLAY_NAME
      )
        ? "myserver.apk"
        : columns[i].equals(OpenableColumns.SIZE)
          ? file.length()
          : null;
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  public Uri insert(Uri uri, ContentValues value) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  public int update(
    Uri uri,
    ContentValues values,
    String selection,
    String[] args
  ) {
    throw new UnsupportedOperationException();
  }
}
