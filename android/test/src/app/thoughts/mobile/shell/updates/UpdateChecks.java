package app.thoughts.mobile.shell.updates;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import app.thoughts.mobile.InteractionChecks;
import java.io.*;
import java.util.*;
import org.json.*;

/** Offline checks for release selection, origin, APK identity and restricted install grants. */
public final class UpdateChecks {

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private interface Attempt {
    void run() throws Exception;
  }

  private static void rejects(Attempt action, String message) throws Exception {
    try {
      action.run();
    } catch (Exception expected) {
      return;
    }
    throw new AssertionError(message);
  }

  private static void copy(File source, File target) throws IOException {
    target.delete();
    try (
      InputStream input = new FileInputStream(source);
      OutputStream output = new FileOutputStream(target)
    ) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
    }
  }

  private static File transport(
    Context context,
    UpdateCatalog.Release release
  ) {
    File folder = new File(
      context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
      "updates"
    );
    folder.mkdirs();
    return new File(folder, release.sha256 + ".apk");
  }

  private static JSONObject published(JSONObject value) throws Exception {
    String tag =
      "android-" +
      value.getString("channel") +
      "-fixture-" +
      value.getLong("version_code");
    value.put(
      "apk_url",
      "https://github.com/example/myserver/releases/download/" +
        tag +
        "/myserver.apk"
    );
    value.put(
      "release_url",
      "https://github.com/example/myserver/releases/tag/" + tag
    );
    return new JSONObject()
      .put("tag_name", tag)
      .put("draft", false)
      .put("prerelease", value.getString("channel").equals("preview"))
      .put("body", "<!-- myserver-update\n" + value + "\n-->")
      .put(
        "assets",
        new JSONArray().put(
          new JSONObject()
            .put("name", "myserver.apk")
            .put("state", "uploaded")
            .put("size", value.getLong("size"))
            .put("browser_download_url", value.getString("apk_url"))
            .put("digest", "sha256:" + value.getString("sha256"))
        )
      );
  }

  public static void run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    UpdateActivity activity = null;
    SharedPreferences prefs = UpdateManager.preferences(context);
    try {
      PackageInfo app = context
        .getPackageManager()
        .getPackageInfo(
          context.getPackageName(),
          Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES
        );
      check(
        app.packageName.equals(UpdateCatalog.STABLE_PACKAGE),
        "All build branches must use the same installable package"
      );
      JSONObject build;
      try (InputStream input = context.getAssets().open("update-source.json")) {
        build = new JSONObject(UpdateCatalog.read(input, 8192));
      }
      prefs.edit().putString("channel", "preview").remove("branch").commit();
      UpdateCatalog.Source inherited = UpdateCatalog.Source.load(context);
      check(
        inherited.channel.equals("stable") &&
          inherited.branch.equals(build.getString("branch")),
        "Old channel preferences must not override stable identity or the actual build branch"
      );
      prefs.edit().putString("branch", "feature/selected").commit();
      check(
        UpdateCatalog.Source.load(context).branch.equals("feature/selected"),
        "Explicit update branch selection must persist across launches"
      );
      File source = new File(context.getApplicationInfo().sourceDir);
      JSONObject value = new JSONObject()
        .put("schema", 1)
        .put("repository", "example/myserver")
        .put("channel", "stable")
        .put("branch", "feature/inbox")
        .put("commit", String.join("", Collections.nCopies(40, "a")))
        .put(
          "version_code",
          Build.VERSION.SDK_INT >= 28
            ? app.getLongVersionCode()
            : app.versionCode
        )
        .put("version_name", app.versionName)
        .put("min_sdk", 26)
        .put("package_name", app.packageName)
        .put("sha256", UpdateManager.hash(source))
        .put("size", source.length())
        .put(
          "certificate_sha256",
          UpdateManager.certificates(app).iterator().next()
        );
      JSONObject release = published(value);
      UpdateCatalog.Release parsed = UpdateCatalog.parseRelease(
        "example/myserver",
        release
      );
      check(parsed != null, "Published verified metadata must be readable");
      JSONObject foreign = new JSONObject(release.toString());
      foreign
        .getJSONArray("assets")
        .getJSONObject(0)
        .put("browser_download_url", "https://example.com/evil.apk");
      rejects(
        () -> UpdateCatalog.parseRelease("example/myserver", foreign),
        "Foreign asset URLs must be rejected"
      );
      JSONObject badDigest = new JSONObject(release.toString());
      badDigest
        .getJSONArray("assets")
        .getJSONObject(0)
        .put("digest", "sha256:bad");
      rejects(
        () -> UpdateCatalog.parseRelease("example/myserver", badDigest),
        "Mismatched GitHub digests must be rejected"
      );
      rejects(
        () ->
          new UpdateCatalog.Release(
            new JSONObject(value.toString()).put(
              "apk_url",
              parsed.apkUrl.replace("https:", "http:")
            )
          ),
        "Cleartext APK URLs must be rejected"
      );
      rejects(
        () ->
          new UpdateCatalog.Release(
            new JSONObject(value.toString()).put(
              "package_name",
              "malicious.app"
            )
          ),
        "Unexpected package names must be rejected"
      );
      JSONObject newerValue = new JSONObject(value.toString()).put(
        "version_code",
        parsed.code + 1
      );
      published(newerValue);
      UpdateCatalog.Release newer = new UpdateCatalog.Release(newerValue);
      UpdateCatalog.Release other = new UpdateCatalog.Release(
        new JSONObject(newerValue.toString())
          .put("branch", "feature/other")
          .put("version_code", newer.code + 10)
      );
      UpdateCatalog.Release main = new UpdateCatalog.Release(
        new JSONObject(newerValue.toString())
          .put("branch", "main")
          .put("version_code", newer.code + 5)
      );
      List<UpdateCatalog.Release> catalog = Arrays.asList(
        other,
        parsed,
        main,
        newer
      );
      check(
        UpdateCatalog.select(
          catalog,
          new UpdateCatalog.Source(
            "example/myserver",
            "stable",
            "feature/inbox"
          )
        ) == newer,
        "Selected development branch must get its newest build, even if another branch is newer"
      );
      check(
        UpdateCatalog.select(
          catalog,
          new UpdateCatalog.Source("example/myserver", "stable", "main")
        ) == main,
        "Main must match its branch exactly instead of picking the highest global version"
      );
      check(
        UpdateCatalog.select(
          catalog,
          new UpdateCatalog.Source(
            "example/myserver",
            "stable",
            "feature/other"
          )
        ) == other,
        "Changing branches must select the chosen branch within the same package"
      );
      check(
        other.packageName.equals(parsed.packageName) &&
          other.certificate.equals(parsed.certificate),
        "Branch changes must retain package and signing identity"
      );
      check(
        UpdateCatalog.select(
          catalog,
          new UpdateCatalog.Source("example/myserver", "stable", "missing")
        ) == null,
        "A missing branch must never silently select another branch"
      );
      check(
        newer.label().equals(newer.version + " · feature/inbox") &&
          main.label().endsWith(" · main"),
        "Every release label must identify its branch"
      );
      rejects(
        () ->
          new UpdateCatalog.Release(
            new JSONObject(value.toString()).put("channel", "preview")
          ),
        "Legacy preview-channel metadata must be rejected"
      );
      rejects(
        () ->
          new UpdateCatalog.Release(
            new JSONObject(value.toString()).put(
              "package_name",
              UpdateCatalog.STABLE_PACKAGE + ".preview"
            )
          ),
        "Legacy preview packages must not be offered as updates"
      );
      rejects(
        () ->
          UpdateCatalog.parseRelease(
            "example/myserver",
            new JSONObject(release.toString()).put("prerelease", true)
          ),
        "Pre-releases must not enter the unified release catalog"
      );
      File apk = UpdateManager.file(context, parsed);
      check(
        apk
          .getCanonicalPath()
          .startsWith(
            context.getFilesDir().getCanonicalPath() + File.separator
          ),
        "Installer APK must live in private internal storage"
      );
      copy(source, apk);
      UpdateManager.verify(context, apk, parsed);
      UpdateCatalog.Release crossBranch = new UpdateCatalog.Release(
        new JSONObject(value.toString()).put("branch", "main")
      );
      UpdateManager.verify(context, apk, crossBranch);
      UpdateCatalog.Release wrongSigner = new UpdateCatalog.Release(
        new JSONObject(value.toString()).put(
          "certificate_sha256",
          String.join("", Collections.nCopies(64, "b"))
        )
      );
      rejects(
        () -> UpdateManager.verify(context, apk, wrongSigner),
        "Wrong signing identity must not reach installer"
      );
      prefs
        .edit()
        .putString("download", parsed.metadata.toString())
        .putString("download_state", "ready")
        .commit();
      Uri uri = Uri.parse(
        "content://" + context.getPackageName() + ".updates/" + parsed.sha256
      );
      File external = transport(context, parsed);
      try (OutputStream output = new FileOutputStream(external)) {
        output.write("replaced external transport".getBytes("UTF-8"));
      }
      try (
        android.os.ParcelFileDescriptor input = context
          .getContentResolver()
          .openFileDescriptor(uri, "r")
      ) {
        check(
          input != null,
          "Verified installation package must open read-only"
        );
        check(
          input.getStatSize() == source.length(),
          "Installer must serve the private verified copy even if external staging changes"
        );
      }
      rejects(
        () -> context.getContentResolver().openFileDescriptor(uri, "w"),
        "Update provider must deny writes"
      );
      rejects(
        () ->
          context
            .getContentResolver()
            .openFileDescriptor(
              Uri.parse(
                "content://" + context.getPackageName() + ".updates/../private"
              ),
              "r"
            ),
        "Update provider must reject other paths"
      );
      try (RandomAccessFile corrupt = new RandomAccessFile(apk, "rw")) {
        corrupt.seek(20);
        int b = corrupt.read();
        corrupt.seek(20);
        corrupt.write(b ^ 1);
      }
      rejects(
        () -> UpdateManager.verify(context, apk, parsed),
        "Changed bytes must fail checksum"
      );
      rejects(
        () -> context.getContentResolver().openFileDescriptor(uri, "r"),
        "Provider must recheck tampering after initial verification"
      );
      UpdateManager.cancel(context);
      check(
        !apk.exists() && !external.exists(),
        "Cancel must remove private and external APK copies"
      );

      // Recover a process exit after private verification but before the ready state was saved.
      copy(source, apk);
      prefs
        .edit()
        .putString("download", parsed.metadata.toString())
        .putString("download_state", "downloading")
        .remove("download_id")
        .commit();
      UpdateManager.verifyCompleted(context);
      check(
        prefs.getString("download_state", "").equals("ready"),
        "Verified private APK must recover without a surviving DownloadManager record"
      );
      UpdateManager.verify(context, apk, parsed);

      // A different signer or a genuinely newer target must not be discarded as installed.
      prefs
        .edit()
        .putString("download", wrongSigner.metadata.toString())
        .commit();
      UpdateManager.pruneInstalled(context);
      check(
        UpdateManager.pending(context) != null && apk.exists(),
        "Installed package with a different signer must not clear a pending update"
      );
      prefs.edit().putString("download", newer.metadata.toString()).commit();
      UpdateManager.pruneInstalled(context);
      check(
        UpdateManager.pending(context) != null && apk.exists(),
        "A pending newer version must survive installed-version cleanup"
      );
      prefs
        .edit()
        .putString("download", parsed.metadata.toString())
        .putBoolean("install_pending", true)
        .commit();
      UpdateManager.pruneInstalled(context);
      check(
        UpdateManager.pending(context) == null &&
          !prefs.contains("download_state") &&
          !prefs.contains("install_pending") &&
          !apk.exists(),
        "Successful installation must remove stale pending state and its private package"
      );

      prefs
        .edit()
        .putString("download", parsed.metadata.toString())
        .putString("download_state", "downloading")
        .commit();
      UpdateManager.verifyCompleted(context);
      check(
        prefs.getString("download_state", "").equals("failed"),
        "Missing both transport record and verified APK must be retryable, not stuck downloading"
      );
      UpdateManager.cancel(context);
      prefs
        .edit()
        .putBoolean("automatic", false)
        .putString("repository", "example/myserver")
        .putString("channel", parsed.channel)
        .putString("branch", "feature/inbox")
        .putString("catalog", new JSONArray().put(newer.metadata).toString())
        .putLong("checked", System.currentTimeMillis())
        .commit();
      activity = (UpdateActivity) test.startActivitySync(
        new Intent(context, UpdateActivity.class).addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK
        )
      );
      test.waitForIdleSync();
      check(
        !activity.isFinishing(),
        "Update settings must render without a server"
      );
      UpdateActivity shown = activity;
      test.runOnMainSync(() -> {
        android.view.View root = shown.getWindow().getDecorView();
        check(
          InteractionChecks.find(root, "更新分支") != null,
          "Update settings must expose the selected update branch"
        );
        check(
          InteractionChecks.find(root, "渠道") == null &&
            InteractionChecks.find(root, "更新渠道") == null &&
            InteractionChecks.find(root, "开发分支") == null,
          "Unified package settings must not retain preview-channel controls"
        );
      });
      test.waitForIdleSync();
      Thread.sleep(250);
      android.graphics.Bitmap bitmap = test.getUiAutomation().takeScreenshot();
      if (bitmap != null) {
        File folder = new File(
          context.getExternalFilesDir(null),
          "screenshots"
        );
        folder.mkdirs();
        try (
          OutputStream out = new FileOutputStream(
            new File(folder, "app-updates.png")
          )
        ) {
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally {
          bitmap.recycle();
        }
      }
    } finally {
      if (activity != null) {
        UpdateActivity screen = activity;
        test.runOnMainSync(screen::finish);
        test.waitForIdleSync();
      }
      UpdateManager.cancel(context);
      prefs.edit().clear().putBoolean("automatic", false).commit();
    }
  }
}
