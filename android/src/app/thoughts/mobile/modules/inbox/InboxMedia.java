package app.thoughts.mobile.modules.inbox;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Explicit export from the private queue into the user's shared photo library. */
public final class InboxMedia {

  private InboxMedia() {}

  public static String mime(JSONObject file) {
    String stored = file.optString("mime", "").toLowerCase(Locale.ROOT);
    String name = file.optString("name", "").toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String guessed =
      dot < 0
        ? null
        : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substring(dot + 1)
          );
    if (
      stored.isEmpty() ||
      stored.equals("application/octet-stream") ||
      stored.equals("binary/octet-stream")
    ) return guessed == null ? "application/octet-stream" : guessed;
    return stored.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
      ? stored
      : "application/octet-stream";
  }

  public static boolean media(JSONObject file) {
    String type = mime(file);
    return type.startsWith("image/") || type.startsWith("video/");
  }

  public static boolean preview(JSONObject file) {
    String type = mime(file),
      name = file.optString("name").toLowerCase(Locale.ROOT);
    return (
      media(file) ||
      type.startsWith("text/") ||
      type.equals("application/json") ||
      name.endsWith(".log") ||
      name.endsWith(".md")
    );
  }

  public static Bitmap bitmap(File file, int maximum) throws Exception {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), options);
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException(
      "无法预览此图片"
    );
    options.inSampleSize = 1;
    while (
        options.outWidth / options.inSampleSize > maximum ||
        options.outHeight / options.inSampleSize > maximum
      )
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    Bitmap result = BitmapFactory.decodeFile(file.getPath(), options);
    if (result == null) throw new IOException("图片解码失败");
    try {
      ExifInterface exif = new ExifInterface(file.getPath());
      int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
      Matrix matrix = new Matrix();
      if (orientation == 2) matrix.setScale(-1, 1);
      else if (orientation == 3) matrix.setRotate(180);
      else if (orientation == 4) matrix.setScale(1, -1);
      else if (orientation == 5) {
        matrix.setRotate(90);
        matrix.postScale(-1, 1);
      } else if (orientation == 6) matrix.setRotate(90);
      else if (orientation == 7) {
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
      } else if (orientation == 8) matrix.setRotate(-90);
      if (!matrix.isIdentity()) {
        Bitmap rotated = Bitmap.createBitmap(
          result,
          0,
          0,
          result.getWidth(),
          result.getHeight(),
          matrix,
          true
        );
        if (rotated != result) result.recycle();
        result = rotated;
      }
    } catch (IOException ignored) {}
    return result;
  }

  public static synchronized Uri save(Context context, InboxStore.Task task)
    throws Exception {
    if (
      Build.VERSION.SDK_INT < 29 &&
      context.checkSelfPermission(
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
      ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) throw new SecurityException("请允许保存到相册");
    SharedPreferences saved = context.getSharedPreferences("inbox_gallery", 0);
    String existing = saved.getString(task.id, null);
    if (existing != null) try (
      Cursor cursor = context
        .getContentResolver()
        .query(Uri.parse(existing), new String[] { "_id" }, null, null, null)
    ) {
      if (cursor != null && cursor.moveToFirst()) return Uri.parse(existing);
    } catch (RuntimeException ignored) {}
    File source;
    try (InboxStore store = new InboxStore(context)) {
      source = store.localFile(task);
    }
    JSONObject file = task.document.getJSONObject("file");
    String type = mime(file);
    boolean video = type.startsWith("video/");
    if (video) {
      MediaMetadataRetriever metadata = new MediaMetadataRetriever();
      try {
        metadata.setDataSource(source.getPath());
        String actual = metadata.extractMetadata(
          MediaMetadataRetriever.METADATA_KEY_MIMETYPE
        );
        if (
          actual == null || !actual.startsWith("video/")
        ) throw new IOException("不是可保存的视频文件");
        type = actual;
      } finally {
        metadata.release();
      }
    } else {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(source.getPath(), bounds);
      if (
        bounds.outWidth <= 0 ||
        bounds.outHeight <= 0 ||
        bounds.outMimeType == null
      ) throw new IOException("不是可保存的图片文件");
      type = bounds.outMimeType;
    }
    String name = InboxStore.cleanName(file.optString("name", "附件"));
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, type);
    File legacy = null;
    if (Build.VERSION.SDK_INT >= 29) {
      values.put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        (video
          ? Environment.DIRECTORY_MOVIES
          : Environment.DIRECTORY_PICTURES) + "/myserver"
      );
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    } else {
      File dir = new File(
        Environment.getExternalStoragePublicDirectory(
          video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES
        ),
        "myserver"
      );
      if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(
        "无法创建相册目录"
      );
      legacy = new File(dir, UUID.randomUUID() + "-" + name);
      values.put(MediaStore.MediaColumns.DATA, legacy.getPath());
    }
    Uri collection = video
      ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    Uri uri = context.getContentResolver().insert(collection, values);
    if (uri == null) throw new IOException("无法创建相册文件");
    try {
      try (
        InputStream input = new FileInputStream(source);
        OutputStream output = context
          .getContentResolver()
          .openOutputStream(uri, "w")
      ) {
        if (output == null) throw new IOException("无法写入相册");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (
            Thread.currentThread().isInterrupted()
          ) throw new InterruptedIOException("保存已取消");
          output.write(buffer, 0, count);
        }
        output.flush();
      }
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (
          context.getContentResolver().update(uri, complete, null, null) != 1
        ) throw new IOException("相册保存未完成");
      } else MediaScannerConnection.scanFile(
        context,
        new String[] { legacy.getPath() },
        new String[] { type },
        null
      );
      if (
        !saved.edit().putString(task.id, uri.toString()).commit()
      ) throw new IOException("无法记录相册保存状态");
      return uri;
    } catch (Exception error) {
      context.getContentResolver().delete(uri, null, null);
      if (legacy != null) legacy.delete();
      throw error;
    }
  }
}
