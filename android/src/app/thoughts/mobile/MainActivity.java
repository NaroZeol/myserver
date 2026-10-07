package app.thoughts.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.core.connection.ConnectionFailure;
import app.thoughts.mobile.core.connection.DeviceAccount;
import app.thoughts.mobile.core.connection.ServerApi;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.modules.inbox.InboxFeature;
import app.thoughts.mobile.modules.inbox.InboxFiles;
import app.thoughts.mobile.modules.inbox.InboxShareActivity;
import app.thoughts.mobile.modules.server.ServerFeature;
import app.thoughts.mobile.modules.thoughts.Store;
import app.thoughts.mobile.modules.thoughts.Sync;
import app.thoughts.mobile.modules.thoughts.SyncPolicy;
import app.thoughts.mobile.modules.thoughts.ThoughtsHost;
import app.thoughts.mobile.modules.thoughts.ThoughtsModule;
import app.thoughts.mobile.shell.SettingsFeature;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Application shell: navigation and lifecycle only. Features own their screens. */
public final class MainActivity extends Activity implements ThoughtsHost {

  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
  private static java.lang.ref.WeakReference<MainActivity> syncHost =
    new java.lang.ref.WeakReference<>(null);
  private final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
  private Store store;
  private SyncPolicy syncPolicy;
  private DeviceAccount account;
  private ServerApi api;
  private ThoughtsModule thoughts;
  private InboxFeature inbox;
  private Ui ui;
  private Feature active;
  private String current = "server",
    message = "";
  private TextView statusView;
  private long feedbackUntil;
  private boolean registered, remoteExport, foreground;
  private final ConnectivityManager.NetworkCallback network =
    new ConnectivityManager.NetworkCallback() {
      public void onAvailable(Network value) {
        runOnUiThread(() -> autoSync());
      }
    };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    syncHost = new java.lang.ref.WeakReference<>(this);
    store = new Store(this);
    android.content.SharedPreferences syncState = getSharedPreferences(
      SyncPolicy.PREFERENCES,
      0
    );
    syncPolicy = new SyncPolicy(
      new SyncPolicy.Storage() {
        public String read(String server) {
          return syncState.getString(server, "");
        }

        public void write(String server, String state) {
          if (
            !syncState.edit().putString(server, state).commit()
          ) throw new IllegalStateException("无法保存同步状态");
        }
      },
      System::currentTimeMillis
    );
    account = new DeviceAccount(this);
    api = new ServerApi(ServerProfile.load(this));
    ui = new Ui(this);
    thoughts = new ThoughtsModule(this);
    add(new ServerFeature(this));
    inbox = new InboxFeature(this);
    add(inbox);
    add(thoughts.screen());
    add(new SettingsFeature(this));
    if (state != null) {
      current = state.getString("feature", "server");
      boolean oldEditor = current.equals("capture");
      if (oldEditor || current.equals("notes")) current = "thoughts";
      if (!features.containsKey(current)) current = "server";
      thoughts.page(state.getBoolean("thoughts_editor", oldEditor));
      remoteExport = state.getBoolean("remote_export");
    }
    redraw();
    receiveIntent(getIntent());
    active.enter();
  }

  private void add(Feature feature) {
    if (
      features.put(feature.id(), feature) != null
    ) throw new IllegalStateException("Duplicate feature");
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    receiveIntent(intent);
  }

  private void receiveIntent(Intent intent) {
    if (
      Intent.ACTION_SEND.equals(intent.getAction()) ||
      Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())
    ) {
      Intent shared = new Intent(intent).setClass(
        this,
        InboxShareActivity.class
      );
      startActivity(shared);
      intent.setAction(null);
      return;
    }
    String requested = intent.getStringExtra("open_feature");
    if (intent.getBooleanExtra("inbox", false)) {
      requested = "inbox";
      intent.removeExtra("inbox");
    }
    if (requested != null) {
      intent.removeExtra("open_feature");
      navigate(requested);
    }
    thoughts.receiveShare(intent);
  }

  protected void onSaveInstanceState(Bundle state) {
    thoughts.saveDraft();
    state.putString("feature", current);
    state.putBoolean("remote_export", remoteExport);
    state.putBoolean("thoughts_editor", thoughts.isComposing());
    super.onSaveInstanceState(state);
  }

  protected void onResume() {
    super.onResume();
    foreground = true;
    app.thoughts.mobile.shell.updates.UpdateManager.automatic(this);
    InboxFiles.resumeInstall(this);
    if (active != null) active.resume();
    try {
      (
        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)
      ).registerDefaultNetworkCallback(network);
      registered = true;
    } catch (Exception ignored) {}
    autoSync();
  }

  protected void onPause() {
    foreground = false;
    if (active != null) active.pause();
    thoughts.saveDraft();
    if (registered) {
      try {
        (
          (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)
        ).unregisterNetworkCallback(network);
      } catch (Exception ignored) {}
      registered = false;
    }
    super.onPause();
  }

  public void onConfigurationChanged(
    android.content.res.Configuration configuration
  ) {
    super.onConfigurationChanged(configuration);
    redraw();
  }

  public Activity activity() {
    return this;
  }

  public Store store() {
    return store;
  }

  public DeviceAccount account() {
    return account;
  }

  public ExecutorService executor() {
    return IO;
  }

  public ServerProfile serverProfile() {
    return api.profile;
  }

  public ServerApi api() {
    return api;
  }

  public void configure(ServerProfile profile) {
    if (SYNCING.get()) {
      status("正在同步，请完成后再修改连接");
      return;
    }
    thoughts.saveDraft();
    try {
      if (
        api.profile != null &&
        !profile.sameEndpoint(api.profile) &&
        (!store.entries().isEmpty() ||
          !getSharedPreferences("draft", 0).getString("content", "").isEmpty())
      ) {
        status("更换服务器前，请先同步或导出，并在设置中清除本机记录。");
        return;
      }
      boolean sameIdentity =
        api.profile != null &&
        profile.sameEndpoint(api.profile) &&
        profile.knownHost.equals(api.profile.knownHost);
      profile.save(this);
      if (sameIdentity) {
        api = new ServerApi(profile);
        redraw();
        status("服务器配置已保存");
        return;
      }
      app.thoughts.mobile.modules.server.PortForwardService.State forward =
        app.thoughts.mobile.modules.server.PortForwardService.snapshot();
      if ("active".equals(forward.phase) || "connecting".equals(forward.phase))
        app.thoughts.mobile.modules.server.PortForwardService.stop(this);
      account.clear();
      api = new ServerApi(profile);
      getSharedPreferences("server_status", 0).edit().clear().commit();
      features.put("server", new ServerFeature(this));
      status("服务器配置已保存");
      redraw();
    } catch (Exception e) {
      status(e.getMessage());
    }
  }

  public String activeFeature() {
    return current;
  }

  public void navigate(String id) {
    boolean editorRoute = id.equals("capture") || id.equals("notes");
    if (editorRoute) {
      thoughts.page(id.equals("capture"));
      id = "thoughts";
    }
    if (id.equals("terminal")) {
      startActivity(
        new Intent(
          this,
          app.thoughts.mobile.modules.terminal.TerminalActivity.class
        )
      );
      return;
    }
    if (!features.containsKey(id)) return;
    if (id.equals(current) && !editorRoute) return;
    message = "";
    feedbackUntil = 0;
    (
      (android.view.inputmethod.InputMethodManager) getSystemService(
        INPUT_METHOD_SERVICE
      )
    ).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    current = id;
    redraw();
    active.enter();
  }

  public void redraw() {
    Feature next = features.get(current);
    if (active != null) {
      if (active != next) active.pause();
      active.leave();
    }
    boolean changed = active != next;
    active = next;
    LinearLayout root = ui.column();
    root.setBackgroundColor(Ui.PAPER);
    root.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(
        ui.dp(24),
        insets.getSystemWindowInsetTop() + ui.dp(12),
        ui.dp(24),
        insets.getSystemWindowInsetBottom() + ui.dp(8)
      );
      return insets;
    });
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    if (active.hasBack()) header.addView(
      ui.iconButton("back", "返回", () -> active.back())
    );
    TextView brand = ui.text(active.title(), 22, Ui.INK);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setSingleLine(true);
    brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
    brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    brand.setMinimumHeight(ui.dp(48));
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    if (!active.headerAction().isEmpty()) {
      if (!active.headerIcon().isEmpty()) header.addView(
        ui.iconButton(active.headerIcon(), active.headerAction(), () ->
          active.performHeaderAction()
        )
      );
      else {
        Button action = ui.button(
          active.headerAction(),
          () -> active.performHeaderAction(),
          false
        );
        action.setTextColor(Ui.BLUE);
        header.addView(action);
      }
    }
    root.addView(header);
    statusView = ui.text(message, 12, Ui.MUTED);
    statusView.setPadding(0, ui.dp(6), 0, ui.dp(6));
    statusView.setMaxLines(2);
    statusView.setVisibility(
      android.os.SystemClock.elapsedRealtime() < feedbackUntil
        ? View.VISIBLE
        : View.GONE
    );
    expireStatus(statusView);
    statusView.setAccessibilityLiveRegion(
      View.ACCESSIBILITY_LIVE_REGION_POLITE
    );
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout surface = ui.column();
    surface.setPadding(0, ui.dp(16), 0, ui.dp(24));
    FrameLayout frame = new FrameLayout(this);
    int width = Math.min(
      getResources().getDisplayMetrics().widthPixels - ui.dp(48),
      ui.dp(680)
    );
    frame.addView(
      surface,
      new FrameLayout.LayoutParams(
        width,
        -2,
        Gravity.TOP | Gravity.CENTER_HORIZONTAL
      )
    );
    scroll.addView(frame);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    active.render(surface);
    LinearLayout footer = ui.column();
    active.renderFooter(footer);
    root.addView(footer);
    root.addView(statusView);
    ui.divider(root);
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(0, ui.dp(8), 0, 0);
    for (Feature feature : features.values()) {
      boolean selected = current.equals(feature.id());
      Button button = ui.button(
        feature.label(),
        () -> navigate(feature.id()),
        false
      );
      button.setTextSize(11);
      button.setTextColor(selected ? Ui.INK : Ui.MUTED);
      button.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(6));
      button.setMinWidth(0);
      button.setMinimumWidth(0);
      button.setSelected(selected);
      button.setBackground(ui.flatBackground(Ui.PAPER, 0));
      Drawable icon = ui.icon(feature.id(), selected ? Ui.INK : Ui.MUTED);
      icon.setBounds(0, 0, ui.dp(24), ui.dp(24));
      button.setCompoundDrawables(null, icon, null, null);
      button.setCompoundDrawablePadding(ui.dp(5));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        0,
        ui.dp(62),
        1
      );
      params.setMargins(ui.dp(2), 0, ui.dp(2), 0);
      nav.addView(button, params);
    }
    root.addView(nav);
    // Give the editor the available height while typing; navigation returns with the keyboard dismissed.
    root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
      android.graphics.Rect visible = new android.graphics.Rect();
      root.getWindowVisibleDisplayFrame(visible);
      boolean keyboard =
        getResources().getDisplayMetrics().heightPixels - visible.height() >
        ui.dp(160);
      int visibility = keyboard ? View.GONE : View.VISIBLE;
      if (nav.getVisibility() != visibility) {
        nav.setVisibility(visibility);
      }
    });
    setContentView(root);
    if (changed && foreground) active.resume();
  }

  public void authorizationChanged() {
    autoSync();
  }

  public void renderFeatureSettings(LinearLayout surface) {
    for (Feature feature : features.values()) feature.renderSettings(surface);
  }

  public void onBackPressed() {
    if (active != null && active.hasBack()) active.back();
    else super.onBackPressed();
  }

  public void status(String value) {
    message = value == null ? "操作未完成，请重试" : value;
    feedbackUntil = android.os.SystemClock.elapsedRealtime() + 7000;
    if (statusView != null) {
      TextView feedback = statusView;
      feedback.setText(message);
      feedback.setVisibility(View.VISIBLE);
      expireStatus(feedback);
    }
  }

  private void expireStatus(TextView feedback) {
    long remaining = feedbackUntil - android.os.SystemClock.elapsedRealtime();
    feedback.postDelayed(
      () -> {
        if (
          feedback == statusView &&
          android.os.SystemClock.elapsedRealtime() >= feedbackUntil
        ) feedback.setVisibility(View.GONE);
      },
      Math.max(1, remaining)
    );
  }

  public boolean automaticSync() {
    return getSharedPreferences("sync_settings", 0).getBoolean(
      "automatic",
      true
    );
  }

  public void setAutomaticSync(boolean enabled) {
    if (
      !getSharedPreferences("sync_settings", 0)
        .edit()
        .putBoolean("automatic", enabled)
        .commit()
    ) {
      status("同步设置保存失败");
      return;
    }
    redraw();
    if (!enabled && SYNCING.get()) status(
      "已关闭自动同步；正在进行的同步仍会完成。"
    );
  }

  public void autoSync() {
    if (automaticSync()) sync(false);
  }

  public void sync() {
    sync(true);
  }

  private void sync(boolean manual) {
    if (!account.can("thoughts")) return;
    if (!account.isVerified()) return;
    if (!SYNCING.compareAndSet(false, true)) return;
    final ServerApi target = api;
    IO.execute(() -> {
      String result = null;
      SyncPolicy.Attempt attempt = null;
      Store.Pending before = null;
      boolean success = false;
      try {
        before = store.pendingState();
        ServerProfile profile = target.profile;
        if (profile == null) return;
        attempt = syncPolicy.begin(
          SyncPolicy.identity(
            profile.host,
            profile.port,
            profile.user,
            profile.knownHost
          ),
          before.fingerprint,
          manual
        );
        if (attempt == null) return;
        runOnUiThread(() -> {
          if (!isDestroyed() && current.equals("thoughts")) status(
            "正在同步 · 本机记录已保留"
          );
        });
        result = Sync.run(store, account, target);
        success = true;
      } catch (Exception e) {
        if (
          e instanceof ConnectionFailure && ((ConnectionFailure) e).code == 401
        ) account.clear();
        result = ui.errorMessage(e);
      } finally {
        if (attempt != null) {
          try {
            Store.Pending after = store.pendingState();
            syncPolicy.finish(
              attempt,
              success,
              before.localChanges == after.localChanges
                ? after.fingerprint
                : before.fingerprint
            );
          } catch (Exception e) {
            result = ui.errorMessage(e);
          }
        }
        SYNCING.set(false);
        long changes =
          before == null ? store.localChanges() : before.localChanges;
        runOnUiThread(() -> {
          // Include cache skips: a save can race the fingerprint read itself.
          MainActivity host = syncHost.get();
          if (
            host != null &&
            !host.isDestroyed() &&
            host.store.localChanges() != changes
          ) host.autoSync();
        });
      }
      app.thoughts.mobile.modules.thoughts.ThoughtsSyncLog.record(this, result);
      String value = result;
      runOnUiThread(() -> {
        if (!isDestroyed() && current.equals("thoughts")) {
          status(value);
          active.refresh();
        }
      });
    });
  }

  public void export(boolean remote) {
    thoughts.saveDraft();
    remoteExport = remote;
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .setType("application/json")
      .addCategory(Intent.CATEGORY_OPENABLE)
      .putExtra(
        Intent.EXTRA_TITLE,
        "thoughts-" +
          (remote ? "server-" : "local-") +
          java.time.LocalDate.now() +
          ".json"
      );
    startActivityForResult(intent, 41);
  }

  public void onRequestPermissionsResult(
    int request,
    String[] permissions,
    int[] results
  ) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (inbox != null) inbox.onRequestPermissionsResult(request);
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (inbox != null && inbox.onActivityResult(request, result, data)) return;
    if (request != 41 || result != RESULT_OK || data == null) return;
    android.net.Uri uri = data.getData();
    boolean remote = remoteExport;
    status("正在导出…");
    IO.execute(() -> {
      try {
        JSONObject output;
        if (remote) output = api.request("/export", "GET", null);
        else {
          JSONArray records = new JSONArray();
          for (Store.Entry entry : store.entries())
            records.put(
              new JSONObject()
                .put("thought", entry.note)
                .put(
                  "pending",
                  entry.pending == null ? JSONObject.NULL : entry.pending
                )
                .put(
                  "error",
                  entry.error == null ? JSONObject.NULL : entry.error
                )
            );
          android.content.SharedPreferences drafts = getSharedPreferences(
            "draft",
            0
          );
          output = new JSONObject()
            .put("schema_version", 1)
            .put("records", records)
            .put(
              "draft",
              new JSONObject()
                .put("content", drafts.getString("content", ""))
                .put("tags", drafts.getString("tags", ""))
                .put("id", drafts.getString("id", null))
                .put("version", drafts.getInt("version", 0))
            );
        }
        try (
          java.io.OutputStream out = getContentResolver().openOutputStream(uri)
        ) {
          out.write(output.toString(2).getBytes("UTF-8"));
        }
        runOnUiThread(() -> status("已导出，草稿与记录保持完整"));
      } catch (Exception e) {
        runOnUiThread(() -> status(ui.errorMessage(e)));
      }
    });
  }

  public void disconnect() {
    if (SYNCING.get()) {
      status("正在同步，请完成后再断开");
      return;
    }
    thoughts.saveDraft();
    int pending = 0;
    try {
      for (Store.Entry entry : store.entries())
        if (entry.pending != null) pending++;
    } catch (Exception e) {
      status(ui.errorMessage(e));
      return;
    }
    boolean hasDraft = !getSharedPreferences("draft", 0)
      .getString("content", "")
      .isEmpty();
    boolean hasUnsaved = pending > 0 || hasDraft;
    LinearLayout form = ui.column();
    form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(12));
    form.addView(
      ui.text(
        "清除本机想法与草稿，并关闭想法自动同步。服务器连接、授权和数据保留；手动同步可重新拉取想法。" +
          (hasUnsaved
            ? "\n\n当前有 " +
              pending +
              " 条未同步记录" +
              (hasDraft ? "和编辑草稿" : "") +
              "，清除后无法从本机恢复。可先导出，再回来清除。"
            : ""),
        14,
        Ui.INK
      )
    );
    CheckBox confirmed = new CheckBox(this);
    confirmed.setText("我确认清除尚未同步的本机内容");
    if (hasUnsaved) form.addView(confirmed);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle("清除本机数据？")
      .setView(scroll)
      .setNegativeButton("取消", null)
      .setNeutralButton("先导出", (d, w) -> export(false))
      .setPositiveButton("清除", (d, w) -> {
        // Recheck after the confirmation dialog: automatic sync may have started meanwhile.
        if (SYNCING.get()) {
          status("正在同步，请完成后再清除");
          return;
        }
        try {
          thoughts.discardDraft();
          store.clear();
          getSharedPreferences("sync_settings", 0)
            .edit()
            .putBoolean("automatic", false)
            .commit();
          redraw();
          status("本机数据已清除，服务器数据保留");
        } catch (Exception e) {
          status(ui.errorMessage(e));
        }
      })
      .create();
    dialog.setOnShowListener(d -> {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!hasUnsaved);
      confirmed.setOnCheckedChangeListener((b, checked) ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(checked)
      );
    });
    dialog.show();
  }
}
