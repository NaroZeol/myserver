package app.thoughts.mobile.modules.inbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import java.io.*;
import org.json.JSONObject;

/** File access is granted per completed download, never to a directory or a filesystem path. */
public final class InboxFiles {

  private InboxFiles() {}

  public static Uri uri(Context context, InboxStore.Task task)
    throws Exception {
    try (InboxStore store = new InboxStore(context)) {
      store.localFile(task);
    }
    return new Uri.Builder()
      .scheme("content")
      .authority(context.getPackageName() + ".inbox.files")
      .appendPath(task.id)
      .build();
  }

  private static boolean apk(JSONObject file) {
    return (
      "application/vnd.android.package-archive".equals(
        file.optString("mime")
      ) ||
      file.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
    );
  }

  private static String mime(JSONObject file) {
    String type = file.optString("mime", "application/octet-stream");
    return type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
      ? type
      : "application/octet-stream";
  }

  private static Intent withFile(
    Context context,
    Intent intent,
    InboxStore.Task task,
    String mime
  ) throws Exception {
    Uri uri = uri(context, task);
    intent.setDataAndType(uri, mime);
    intent.setClipData(ClipData.newRawUri("附件", uri));
    return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  public static void open(Activity activity, InboxStore.Task task) {
    try {
      JSONObject file = task.document.getJSONObject("file");
      if (apk(file)) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
          new AlertDialog.Builder(activity)
            .setTitle("允许安装应用")
            .setMessage(
              "需要在系统设置中允许 myserver 安装应用，之后由系统确认安装。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("打开设置", (d, w) -> {
              activity
                .getSharedPreferences("inbox_install", 0)
                .edit()
                .putString("pending", task.id)
                .apply();
              try {
                activity.startActivity(
                  new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
                  )
                );
              } catch (ActivityNotFoundException e) {
                activity
                  .getSharedPreferences("inbox_install", 0)
                  .edit()
                  .remove("pending")
                  .apply();
                Toast.makeText(
                  activity,
                  "系统未提供安装权限设置",
                  Toast.LENGTH_LONG
                ).show();
              }
            })
            .show();
          return;
        }
        activity.startActivity(
          withFile(
            activity,
            new Intent(Intent.ACTION_VIEW),
            task,
            "application/vnd.android.package-archive"
          )
        );
      } else activity.startActivity(
        withFile(activity, new Intent(Intent.ACTION_VIEW), task, mime(file))
      );
    } catch (ActivityNotFoundException e) {
      Toast.makeText(
        activity,
        "没有可以打开此文件的应用，可尝试另存为或分享",
        Toast.LENGTH_LONG
      ).show();
    } catch (Exception e) {
      Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  public static void resumeInstall(Activity activity) {
    android.content.SharedPreferences prefs = activity.getSharedPreferences(
      "inbox_install",
      0
    );
    String id = prefs.getString("pending", null);
    if (id == null) return;
    prefs.edit().remove("pending").apply();
    if (!activity.getPackageManager().canRequestPackageInstalls()) return;
    try (InboxStore store = new InboxStore(activity)) {
      InboxStore.Task task = store.task(id);
      if (task != null) open(activity, task);
    } catch (Exception e) {
      Toast.makeText(
        activity,
        "安装文件已移除，请重新下载",
        Toast.LENGTH_LONG
      ).show();
    }
  }

  public static void share(Activity activity, InboxStore.Task task) {
    try {
      JSONObject file = task.document.getJSONObject("file");
      Uri uri = uri(activity, task);
      Intent send = new Intent(Intent.ACTION_SEND)
        .setType(mime(file))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      send.setClipData(ClipData.newRawUri("附件", uri));
      activity.startActivity(Intent.createChooser(send, "分享附件"));
    } catch (Exception e) {
      Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  /** Called on a worker after ACTION_CREATE_DOCUMENT returns a writable URI. */
  public static void saveTo(Context context, InboxStore.Task task, Uri target)
    throws Exception {
    if (
      target == null || !"content".equals(target.getScheme())
    ) throw new IOException("请选择保存位置");
    try (
      InboxStore store = new InboxStore(context);
      InputStream in = new FileInputStream(store.localFile(task));
      OutputStream out = context
        .getContentResolver()
        .openOutputStream(target, "wt")
    ) {
      if (out == null) throw new IOException("无法写入所选位置");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (
          Thread.currentThread().isInterrupted()
        ) throw new InterruptedIOException("导出已取消");
        out.write(buffer, 0, n);
      }
      out.flush();
    }
  }
}
