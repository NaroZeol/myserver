package app.thoughts.mobile.shell.updates;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Public release metadata only. No GitHub credentials are accepted or stored. */
public final class UpdateCatalog {

  public static final String STABLE_PACKAGE = "app.thoughts.mobile";
  public static final long MAX_APK = 128L * 1024 * 1024;

  public static final class Source {

    public final String repository, channel, branch;

    public Source(String repository, String channel, String branch) {
      this.repository = repository;
      this.channel = "stable";
      this.branch = branch;
    }

    public static Source load(Context context) {
      JSONObject build = new JSONObject();
      try (InputStream in = context.getAssets().open("update-source.json")) {
        build = new JSONObject(read(in, 8192));
      } catch (Exception ignored) {}
      android.content.SharedPreferences prefs = UpdateManager.preferences(
        context
      );
      return new Source(
        prefs.getString("repository", build.optString("repository")),
        "stable",
        prefs.getString("branch", build.optString("branch", "main"))
      );
    }
  }

  public static final class Release {

    public final JSONObject metadata;
    public final String repository, channel, branch, version, packageName, sha256, certificate, apkUrl;
    public final long code, size;
    public final int minSdk;

    public Release(JSONObject value) throws Exception {
      metadata = new JSONObject(value.toString());
      repository = value.getString("repository");
      channel = value.getString("channel");
      branch = value.getString("branch");
      version = value.getString("version_name");
      packageName = value.getString("package_name");
      sha256 = value.getString("sha256");
      certificate = value.getString("certificate_sha256");
      apkUrl = value.getString("apk_url");
      code = value.getLong("version_code");
      size = value.getLong("size");
      minSdk = value.getInt("min_sdk");
      if (
        value.getInt("schema") != 1 ||
        !validRepository(repository) ||
        !channel.equals("stable") ||
        !packageName.equals(STABLE_PACKAGE) ||
        code < 1 ||
        code > Integer.MAX_VALUE ||
        size < 1 ||
        size > MAX_APK ||
        minSdk < 26 ||
        minSdk > 100 ||
        version.isEmpty() ||
        version.length() > 100 ||
        branch.isEmpty() ||
        branch.length() > 255 ||
        java.util.regex.Pattern.compile("\\p{Cntrl}").matcher(branch).find() ||
        !sha256.matches("[0-9a-f]{64}") ||
        !certificate.matches("[0-9a-f]{64}") ||
        !value.getString("commit").matches("[0-9a-f]{40}")
      ) {
        throw new IOException("发布信息无效");
      }
      URL url = new URL(apkUrl);
      if (
        !secure(url) ||
        !url.getHost().equals("github.com") ||
        !url.getPath().startsWith("/" + repository + "/releases/download/") ||
        !url.getPath().endsWith("/myserver.apk") ||
        url.getQuery() != null
      ) throw new IOException("安装包来源无效");
    }

    public String label() {
      return version + " · " + branch;
    }
  }

  public static boolean validRepository(String repository) {
    return (
      repository != null &&
      repository.matches(
        "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}"
      ) &&
      !repository.endsWith("/.") &&
      !repository.endsWith("/..")
    );
  }

  public static Release parseRelease(String repository, JSONObject published)
    throws Exception {
    if (published.optBoolean("draft")) return null;
    String body = published.optString("body");
    String marker = "<!-- myserver-update\n";
    int start = body.indexOf(marker),
      end = start < 0 ? -1 : body.indexOf("\n-->", start);
    if (start < 0 || end < 0 || end - start > 16384) return null;
    Release result = new Release(
      new JSONObject(body.substring(start + marker.length(), end))
    );
    String tag = published.getString("tag_name");
    String prefix = "android-" + result.channel + "-";
    if (
      !result.repository.equalsIgnoreCase(repository) ||
      !tag.startsWith(prefix) ||
      !tag.matches("[A-Za-z0-9._-]+") ||
      published.optBoolean("prerelease") ||
      !result.apkUrl.equals(
        "https://github.com/" +
          result.repository +
          "/releases/download/" +
          tag +
          "/myserver.apk"
      ) ||
      !result.metadata
        .getString("release_url")
        .equals(
          "https://github.com/" + result.repository + "/releases/tag/" + tag
        )
    ) throw new IOException("发布来源与所选仓库不一致");
    JSONArray assets = published.getJSONArray("assets");
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.getJSONObject(i);
      if (!"myserver.apk".equals(asset.optString("name"))) continue;
      if (
        !"uploaded".equals(asset.optString("state")) ||
        asset.getLong("size") != result.size ||
        !result.apkUrl.equals(asset.getString("browser_download_url"))
      ) throw new IOException("安装包信息不一致");
      String digest = asset.optString("digest", "");
      if (
        !digest.isEmpty() &&
        !digest.equals("null") &&
        !digest.equals("sha256:" + result.sha256)
      ) throw new IOException("安装包摘要不一致");
      return result;
    }
    return null;
  }

  public static List<Release> fetch(String repository) throws Exception {
    if (!validRepository(repository)) throw new IOException(
      "请设置 GitHub 发布仓库"
    );
    List<Release> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    // Check latest and retain releases from every branch in the paginated catalog.
    try {
      String latest = get(
        "https://api.github.com/repos/" + repository + "/releases/latest"
      );
      try {
        Release stable = parseRelease(repository, new JSONObject(latest));
        if (stable != null) {
          result.add(stable);
          seen.add(stable.apkUrl);
        }
      } catch (JSONException | IOException ignored) {
        /* An unrelated latest release must not hide other branches. */
      }
    } catch (NotFound ignored) {}
    for (int page = 1; page <= 5; page++) {
      JSONArray values = new JSONArray(
        get(
          "https://api.github.com/repos/" +
            repository +
            "/releases?per_page=100&page=" +
            page
        )
      );
      for (int i = 0; i < values.length(); i++) {
        try {
          Release release = parseRelease(repository, values.getJSONObject(i));
          if (release != null && seen.add(release.apkUrl)) result.add(release);
        } catch (JSONException | IOException ignored) {
          /* Ignore unrelated or incomplete releases. */
        }
      }
      if (values.length() < 100) break;
    }
    result.sort((a, b) -> Long.compare(b.code, a.code));
    return result;
  }

  public static Release select(List<Release> releases, Source source) {
    Release best = null;
    for (Release value : releases) {
      if (
        !value.repository.equalsIgnoreCase(source.repository) ||
        !value.channel.equals(source.channel) ||
        !value.branch.equals(source.branch)
      ) continue;
      if (best == null || value.code > best.code) best = value;
    }
    return best;
  }

  private static final class NotFound extends IOException {}

  private static String get(String address) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(
      address
    ).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    connection.setRequestProperty("User-Agent", "myserver-android");
    try {
      int status = connection.getResponseCode();
      if (status == 404) throw new NotFound();
      if (status == 403 || status == 429) throw new IOException(
        "检查过于频繁，请稍后重试"
      );
      if (status != 200) throw new IOException(
        "暂时无法获取版本（" + status + "）"
      );
      try (InputStream stream = connection.getInputStream()) {
        return read(stream, 2 * 1024 * 1024);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static boolean secure(URL url) {
    return (
      url.getProtocol().equals("https") &&
      (url.getPort() == -1 || url.getPort() == 443) &&
      url.getUserInfo() == null &&
      url.getRef() == null
    );
  }

  static String read(InputStream input, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (out.size() + n > max) throw new IOException("发布信息过大");
      out.write(buffer, 0, n);
    }
    return out.toString("UTF-8");
  }
}
