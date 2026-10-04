package app.thoughts.mobile;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import app.thoughts.mobile.core.connection.ConnectionFailure;
import app.thoughts.mobile.core.connection.DeviceAccess;
import app.thoughts.mobile.core.connection.DeviceAccount;
import app.thoughts.mobile.core.connection.DeviceKey;
import app.thoughts.mobile.core.connection.ServerApi;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.core.connection.ShellIdentity;
import app.thoughts.mobile.core.connection.SshConnection;
import app.thoughts.mobile.modules.inbox.InboxUiChecks;
import app.thoughts.mobile.modules.inbox.TransferChecks;
import app.thoughts.mobile.modules.server.ServerChecks;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import app.thoughts.mobile.modules.thoughts.DeviceEnrollment;
import app.thoughts.mobile.modules.thoughts.SshTransport;
import app.thoughts.mobile.modules.thoughts.Store;
import org.json.JSONArray;

public final class SmokeTest extends Instrumentation {

  private Bundle arguments;

  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args;
    start();
  }

  private View find(View view, String text) {
    if (
      text.equals(view.getContentDescription()) ||
      (view instanceof TextView &&
        text.contentEquals(((TextView) view).getText()))
    ) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
  }

  private Button findButton(View view, String label) {
    if (
      view instanceof Button && label.contentEquals(((Button) view).getText())
    ) return (Button) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button result = findButton(group.getChildAt(i), label);
        if (result != null) return result;
      }
    }
    return null;
  }

  public void onStart() {
    getTargetContext()
      .getSharedPreferences("app_updates", 0)
      .edit()
      .putBoolean("automatic", false)
      .commit();
    Bundle result = new Bundle();
    MainActivity activity = null;
    if ("key".equals(arguments.getString("mode"))) {
      try {
        result.putString(
          "stream",
          "DEVICE_PUBLIC_KEY: " + DeviceEnrollment.key().publicKey() + "\n"
        );
        finish(-1, result);
      } catch (Exception e) {
        result.putString("stream", "FAIL: " + e);
        finish(1, result);
      }
      return;
    }
    if ("visual".equals(arguments.getString("mode"))) {
      try {
        new DeviceAccount(getTargetContext()).clear();
        getTargetContext()
          .getSharedPreferences("sync_settings", 0)
          .edit()
          .clear()
          .commit();
        getTargetContext()
          .getSharedPreferences("server_status", 0)
          .edit()
          .putString(
            "snapshot",
            "{\"metrics\":{\"cpu\":{\"cores\":2,\"usage_percent\":7.5,\"load_average\":[0.12,0.08,0.04]},\"memory\":{\"used_bytes\":751619276,\"total_bytes\":2147483648,\"usage_percent\":35},\"disk\":{\"used_bytes\":23622320128,\"total_bytes\":42949672960,\"usage_percent\":55},\"uptime_seconds\":90061}}"
          )
          .putLong("checked_at", System.currentTimeMillis() - 60000)
          .commit();
        Store sample = new Store(getTargetContext());
        sample.clear();
        sample.save(
          null,
          "把博客重新捡起来。想让文字有一个安静、可以慢慢生长的地方。",
          new JSONArray().put("日常"),
          0
        );
        sample.save(
          null,
          "今天散步时想到：好的工具应该让人更愿意开始，而不是把精力花在准备上。",
          new JSONArray().put("设计").put("随想"),
          0
        );
        getTargetContext()
          .getSharedPreferences("draft", 0)
          .edit()
          .clear()
          .putString(
            "content",
            "给今天留一点空白。\n\n记录不必每次都有答案，有时只是把一个念头好好放下。"
          )
          .commit();
        activity = (MainActivity) startActivitySync(
          new Intent(getTargetContext(), MainActivity.class).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
          )
        );
        final MainActivity screen = activity;
        InboxUiChecks.seedVisual(getTargetContext());
        for (String page : new String[] {
          "capture",
          "notes",
          "server",
          "inbox",
          "settings",
        }) {
          runOnMainSync(() -> {
            screen.navigate(page);
            // Visual fixtures contain synthetic data only. Keep FLAG_SECURE in
            // production, but allow the test runner to inspect this layout.
            screen
              .getWindow()
              .clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
          });
          screenshot(page);
        }
        runOnMainSync(() -> {
          screen.navigate("capture");
          EditText editor = (EditText) find(
            screen.getWindow().getDecorView(),
            "想法内容"
          );
          editor.requestFocus();
          (
            (android.view.inputmethod.InputMethodManager) screen.getSystemService(
              android.content.Context.INPUT_METHOD_SERVICE
            )
          ).showSoftInput(
            editor,
            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
          );
        });
        android.os.SystemClock.sleep(600);
        screenshot("keyboard");
        result.putString(
          "stream",
          "PASS: visual review captured; widthDp=" +
            getTargetContext().getResources().getConfiguration().screenWidthDp +
            "; fontScale=" +
            getTargetContext().getResources().getConfiguration().fontScale +
            "\n"
        );
        finish(-1, result);
      } catch (Throwable error) {
        result.putString(
          "stream",
          "FAIL: " + android.util.Log.getStackTraceString(error)
        );
        finish(1, result);
      } finally {
        if (activity != null) {
          final MainActivity screen = activity;
          runOnMainSync(() -> screen.finish());
        }
      }
      return;
    }
    try {
      getTargetContext()
        .getSharedPreferences("server_config", 0)
        .edit()
        .clear()
        .commit();
      check(
        ServerProfile.load(getTargetContext()) == null,
        "A fresh install must not contain a server"
      );
      try {
        new ServerApi(null).request("/session", "GET", null);
        throw new AssertionError("Unconfigured API must reject requests");
      } catch (ConnectionFailure e) {
        check(e.code == 400, "Unconfigured API must explain setup");
      }
      getTargetContext()
        .getSharedPreferences("sync_settings", 0)
        .edit()
        .clear()
        .commit();
      checkDeviceSignature();
      app.thoughts.mobile.shell.updates.UpdateChecks.run(this);
      app.thoughts.mobile.modules.thoughts.ThoughtsSyncChecks.run(this);
      Store store = new Store(getTargetContext());
      store.clear();
      new DeviceAccount(getTargetContext()).clear();
      getTargetContext()
        .getSharedPreferences("draft", 0)
        .edit()
        .clear()
        .commit();
      activity = (MainActivity) startActivitySync(
        new Intent(getTargetContext(), MainActivity.class).addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK
        )
      );
      final MainActivity screen = activity;
      runOnMainSync(() -> {
        check(
          screen.activeFeature().equals("server"),
          "The application must open on the server workspace"
        );
        check(
          findButton(screen.getWindow().getDecorView(), "记录") == null,
          "Capture must not be a separate tab"
        );
        check(
          findButton(screen.getWindow().getDecorView(), "终端") == null,
          "Terminal must live inside the server workspace"
        );
        findButton(screen.getWindow().getDecorView(), "想法").performClick();
        findButton(screen.getWindow().getDecorView(), "写想法").performClick();
      });
      screenshot("capture");
      waitForIdleSync();
      runOnMainSync(() -> {
        EditText editor = (EditText) find(
          screen.getWindow().getDecorView(),
          "想法内容"
        );
        check(editor != null, "Native editor did not render");
        editor.setText("Offline capture smoke test");
        findButton(screen.getWindow().getDecorView(), "发布").performClick();
      });
      waitForIdleSync();
      check(
        store.entries().size() == 1,
        "Offline capture must persist exactly one record"
      );
      Store.Entry saved = store.entries().get(0);
      check(
        "create".equals(saved.pending),
        "Offline record must remain queued"
      );
      check(
        !saved.note.has("visibility"),
        "Records must have no visibility state"
      );
      runOnMainSync(() ->
        findButton(screen.getWindow().getDecorView(), "想法").performClick()
      );
      waitForIdleSync();
      check(
        find(screen.getWindow().getDecorView(), "Offline capture smoke test") !=
          null,
        "Saved record did not render in native list"
      );
      screenshot("notes");
      runOnMainSync(() ->
        findButton(screen.getWindow().getDecorView(), "写想法").performClick()
      );
      waitForIdleSync();
      runOnMainSync(() ->
        (
          (EditText) find(screen.getWindow().getDecorView(), "想法内容")
        ).setText("Unsent draft")
      );
      runOnMainSync(() ->
        findButton(screen.getWindow().getDecorView(), "设置").performClick()
      );
      waitForIdleSync();
      check(
        "Unsent draft".equals(
          getTargetContext()
            .getSharedPreferences("draft", 0)
            .getString("content", "")
        ),
        "Navigation lost the draft"
      );
      screenshot("settings");
      runOnMainSync(() ->
        findButton(screen.getWindow().getDecorView(), "服务器").performClick()
      );
      waitForIdleSync();
      screenshot("server");
      runOnMainSync(() -> {
        screen.status("Feedback survives redraw");
        screen.redraw();
        View feedback = find(
          screen.getWindow().getDecorView(),
          "Feedback survives redraw"
        );
        check(
          feedback != null && feedback.getVisibility() == View.VISIBLE,
          "Redraw must not swallow feedback"
        );
        screen.navigate("notes");
        EditText search = (EditText) find(
          screen.getWindow().getDecorView(),
          "搜索想法"
        );
        search.setText("Offline");
        screen.navigate("settings");
        screen.navigate("notes");
        check(
          "Offline".contentEquals(
            (
              (EditText) find(screen.getWindow().getDecorView(), "搜索想法")
            ).getText()
          ),
          "Search must survive switching tabs"
        );
        screen.navigate("capture");
        EditText editor = (EditText) find(
          screen.getWindow().getDecorView(),
          "想法内容"
        );
        editor.setSelection(3);
        screen.setAutomaticSync(false);
        check(
          (
            (EditText) find(screen.getWindow().getDecorView(), "想法内容")
          ).getSelectionStart() == 3,
          "Changing sync mode must preserve editor cursor"
        );
      });
      Store.Entry offline = store.entries().get(0);
      store.removeOrRestore(offline, false);
      check(
        "local-trash".equals(store.entries().get(0).pending),
        "Offline capture must be removable before first sync"
      );
      store.removeOrRestore(store.entries().get(0), true);
      check(
        "create".equals(store.entries().get(0).pending),
        "Restoring local trash must queue a create"
      );
      // A local edit while the first request is in flight must survive acknowledgement.
      store.save(
        saved.note.getString("id"),
        "Newer local edit",
        new JSONArray(),
        0
      );
      org.json.JSONObject response = new org.json.JSONObject(
        saved.note.toString()
      ).put("version", 1);
      store.acknowledge(saved, response);
      Store.Entry newer = store.entries().get(0);
      check(
        "Newer local edit".equals(newer.note.getString("content")),
        "Acknowledgement overwrote newer local content"
      );
      check(
        "update".equals(newer.pending),
        "Newer edit must remain queued as an update"
      );
      if (arguments.containsKey("ssh_host_key")) {
        checkSsh();
        ServerProfile configured = ServerProfile.load(getTargetContext());
        runOnMainSync(() -> screen.configure(configured));
        check(
          store.entries().size() == 1 && store.entries().get(0).pending != null,
          "First server setup must preserve offline records"
        );
        ServerProfile another = new ServerProfile(
          "other",
          "Other server",
          "other.example.com",
          22,
          "user",
          configured.knownHost
        );
        runOnMainSync(() -> screen.configure(another));
        check(
          screen.api().profile.sameEndpoint(configured),
          "Changing servers must not mix pending records with another account"
        );
        store.clear();
        store.save(null, "Manual synchronization test", new JSONArray(), 0);
        screen
          .account()
          .verified(new ServerApi(configured).request("/session", "GET", null));
        runOnMainSync(() -> {
          screen.setAutomaticSync(false);
          screen.autoSync();
          callActivityOnPause(screen);
          callActivityOnResume(screen);
        });
        screen
          .executor()
          .submit(() -> {})
          .get(45, java.util.concurrent.TimeUnit.SECONDS);
        check(
          "create".equals(store.entries().get(0).pending),
          "Manual mode must preserve queued records across network/resume automatic triggers"
        );
        runOnMainSync(() -> screen.sync());
        screen
          .executor()
          .submit(() -> {})
          .get(45, java.util.concurrent.TimeUnit.SECONDS);
        check(
          store
            .entries()
            .stream()
            .anyMatch(
              entry ->
                entry.note
                  .optString("content")
                  .equals("Manual synchronization test") &&
                entry.pending == null
            ),
          "Explicit sync must still upload records in manual mode"
        );
      }
      ServerChecks.run(this, screen);
      InteractionChecks.run(this, screen);
      InboxUiChecks.run(this, screen);
      result.putString(
        "stream",
        "PASS: native launch, offline capture, list rendering, draft persistence, edit/delete/restore/clear flows, feedback/search/cursor retention, in-flight edit preservation, SSH PTY and renderer, private inbox resumable transfers\n"
      );
      finish(-1, result);
    } catch (Throwable error) {
      result.putString(
        "stream",
        "FAIL: " + android.util.Log.getStackTraceString(error)
      );
      finish(1, result);
    } finally {
      if (activity != null) {
        MainActivity screen = activity;
        runOnMainSync(() -> screen.finish());
      }
    }
  }

  private void screenshot(String name) throws Exception {
    waitForIdleSync();
    android.os.SystemClock.sleep(250);
    android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
    for (int retry = 0; bitmap == null && retry < 3; retry++) {
      android.os.SystemClock.sleep(250);
      bitmap = getUiAutomation().takeScreenshot();
    }
    if (bitmap == null) {
      throw new AssertionError("Screenshot unavailable: " + name);
    }
    java.io.File dir = new java.io.File(
      getTargetContext().getExternalFilesDir(null),
      "screenshots"
    );
    dir.mkdirs();
    try (
      java.io.OutputStream out = new java.io.FileOutputStream(
        new java.io.File(dir, name + arguments.getString("suffix", "") + ".png")
      )
    ) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  private byte[] field(java.io.DataInputStream in) throws Exception {
    byte[] value = new byte[in.readInt()];
    in.readFully(value);
    return value;
  }

  private void checkDeviceSignature() throws Exception {
    DeviceKey key = DeviceEnrollment.key();
    java.io.DataInputStream pub = new java.io.DataInputStream(
      new java.io.ByteArrayInputStream(key.getPublicKeyBlob())
    );
    field(pub);
    java.math.BigInteger exponent = new java.math.BigInteger(field(pub)),
      modulus = new java.math.BigInteger(field(pub));
    java.security.PublicKey publicKey = java.security.KeyFactory.getInstance(
      "RSA"
    ).generatePublic(
      new java.security.spec.RSAPublicKeySpec(modulus, exponent)
    );
    byte[] payload = "device-signature-proof".getBytes("UTF-8");
    java.io.DataInputStream encoded = new java.io.DataInputStream(
      new java.io.ByteArrayInputStream(
        key.getSignature(payload, "rsa-sha2-256")
      )
    );
    check(
      new String(field(encoded), "UTF-8").equals("rsa-sha2-256"),
      "SSH must use RSA SHA-2"
    );
    java.security.Signature signature = java.security.Signature.getInstance(
      "SHA256withRSA"
    );
    signature.initVerify(publicKey);
    signature.update(payload);
    check(
      signature.verify(field(encoded)),
      "Android Keystore signature must verify with exported public key"
    );
    check(
      java.util.Arrays.equals(
        key.getPublicKeyBlob(),
        DeviceEnrollment.key().getPublicKeyBlob()
      ),
      "Device key must survive recreation"
    );
    check(
      key.getSignature(payload, "ssh-rsa") == null,
      "Reject legacy SHA-1 signatures"
    );
  }

  private void checkSsh() throws Exception {
    ServerProfile profile = new ServerProfile(
      "ci",
      "CI fixture",
      "10.0.2.2",
      2222,
      arguments.getString("ssh_user"),
      "ecdsa-sha2-nistp256 " + arguments.getString("ssh_host_key")
    );
    ServerProfile inspected = SshConnection.inspect(profile.trusted(""));
    check(
      inspected.fingerprint().equals(profile.fingerprint()),
      "Discovery must return the presented server identity"
    );
    check(
      ServerProfile.load(getTargetContext()) == null,
      "Discovery must not silently trust a server"
    );
    profile.save(getTargetContext());
    ServerProfile restored = ServerProfile.load(getTargetContext());
    check(
      restored.sameEndpoint(profile) &&
        restored.knownHost.equals(profile.knownHost),
      "Explicitly saved connection must persist its endpoint and pinned key"
    );
    byte[] wrongPassword = "invalid-password-for-fixture".getBytes("UTF-8");
    try {
      DeviceEnrollment.register(profile, wrongPassword);
      throw new AssertionError(
        "An incorrect password must not register a device"
      );
    } catch (ConnectionFailure e) {
      check(e.code == 401, "Incorrect password must be rejected");
    }
    for (byte value : wrongPassword)
      check(value == 0, "Password must be cleared after a failed login");
    byte[] password = arguments.getString("ssh_password").getBytes("UTF-8");
    arguments.remove("ssh_password");
    org.json.JSONObject enrollment = DeviceAccess.authorize(
      getTargetContext(),
      profile,
      password,
      true
    );
    check(
      enrollment.optString("transport").equals("ssh"),
      "Enrollment must verify the device key after password login"
    );
    for (byte value : password)
      check(value == 0, "Password must be cleared after enrollment");
    SshTransport transport = new SshTransport(profile);
    org.json.JSONObject session = new ServerApi(restored).request(
      "/session",
      "GET",
      null
    );
    check(
      session.getString("transport").equals("ssh"),
      "Actual SSH authentication must succeed"
    );
    String id = java.util.UUID.randomUUID().toString();
    org.json.JSONObject note = transport.request(
      "/thoughts",
      "POST",
      new org.json.JSONObject()
        .put("id", id)
        .put("content", "CI SSH record")
        .put("tags", new JSONArray())
    );
    check(note.getInt("version") == 1, "SSH create must persist");
    note = transport.request(
      "/thoughts/" + id,
      "PATCH",
      new org.json.JSONObject()
        .put("version", 1)
        .put("content", "CI SSH edited")
    );
    check(note.getInt("version") == 2, "SSH edit must use version check");
    check(
      transport
        .request("/thoughts/" + id + "/history", "GET", null)
        .getJSONArray("items")
        .length() == 1,
      "SSH edit history must persist"
    );
    transport.request(
      "/thoughts/" + id,
      "DELETE",
      new org.json.JSONObject().put("version", 2)
    );
    note = transport.request(
      "/thoughts/" + id,
      "PATCH",
      new org.json.JSONObject().put("version", 3).put("restore", true)
    );
    check(note.isNull("deleted_at"), "SSH restore must succeed");
    check(
      transport
        .request("/system", "GET", null)
        .getString("service")
        .equals("myserver"),
      "Read-only server capability must work"
    );
    org.json.JSONObject metrics = transport
      .request("/system", "GET", null)
      .getJSONObject("metrics");
    check(
      metrics.getJSONObject("cpu").getInt("cores") > 0 &&
        metrics.getJSONObject("memory").getLong("total_bytes") > 0 &&
        metrics.getJSONObject("disk").getLong("total_bytes") > 0,
      "Real SSH snapshot must include CPU, memory and disk metrics"
    );
    boolean rejected = false;
    try {
      transport.request("/login", "POST", new org.json.JSONObject());
    } catch (ConnectionFailure e) {
      rejected = e.code == 403;
    }
    check(rejected, "Restricted SSH key must not access other API routes");
    rejected = false;
    try {
      new SshTransport(
        new ServerProfile(
          "wrong",
          "CI",
          "10.0.2.2",
          2222,
          arguments.getString("ssh_user"),
          "ecdsa-sha2-nistp256 " + arguments.getString("ssh_wrong_host_key")
        )
      ).request("/session", "GET", null);
    } catch (ConnectionFailure e) {
      rejected = e.code == 495;
    }
    check(rejected, "Server host key mismatch must be rejected");
    check(
      ShellIdentity.registered(getTargetContext(), restored),
      "One authorization must also remember the separate terminal identity"
    );
    check(
      DeviceAccess.authorize(getTargetContext(), restored, null, false)
        .optString("transport")
        .equals("ssh"),
      "An authorized shell identity must enroll service access without a second password"
    );
    TransferChecks.run(this, restored);
    app.thoughts.mobile.modules.inbox.InboxMediaChecks.run(this);
    TerminalChecks.run(this, restored);
  }
}
