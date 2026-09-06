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
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Application shell: navigation and lifecycle only. Features own their screens. */
public final class MainActivity extends Activity implements Feature.Host {

  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
  private final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
  private Store store;
  private Account account;
  private Api api;
  private ThoughtsModule thoughts;
  private Ui ui;
  private Feature active;
  private String current = "capture",
    message = "本机优先 · 随时记录";
  private TextView statusView;
  private boolean registered, remoteExport;
  private final ConnectivityManager.NetworkCallback network =
    new ConnectivityManager.NetworkCallback() {
      public void onAvailable(Network value) {
        runOnUiThread(() -> autoSync());
      }
    };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    account = new Account(this);
    api = new Api(ServerProfile.load(this));
    ui = new Ui(this);
    thoughts = new ThoughtsModule(this);
    add(thoughts.screen("capture", "记录"));
    add(thoughts.screen("notes", "想法"));
    add(new ServerFeature(this));
    add(new SettingsFeature(this));
    if (state != null) {
      current = state.getString("feature", "capture");
      remoteExport = state.getBoolean("remote_export");
    }
    redraw();
    thoughts.receiveShare(getIntent());
  }

  private void add(Feature feature) {
    if (
      features.put(feature.id(), feature) != null
    ) throw new IllegalStateException("Duplicate feature");
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    thoughts.receiveShare(intent);
  }

  protected void onSaveInstanceState(Bundle state) {
    thoughts.saveDraft();
    state.putString("feature", current);
    state.putBoolean("remote_export", remoteExport);
    super.onSaveInstanceState(state);
  }

  protected void onResume() {
    super.onResume();
    try {
      (
        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)
      ).registerDefaultNetworkCallback(network);
      registered = true;
    } catch (Exception ignored) {}
    autoSync();
  }

  protected void onPause() {
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

  public Account account() {
    return account;
  }

  public ExecutorService executor() {
    return IO;
  }

  public Api api() {
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
      profile.save(this);
      account.clear();
      api = new Api(profile);
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
    if (!features.containsKey(id)) return;
    (
      (android.view.inputmethod.InputMethodManager) getSystemService(
        INPUT_METHOD_SERVICE
      )
    ).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    current = id;
    redraw();
  }

  public void redraw() {
    if (active != null) active.leave();
    active = features.get(current);
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
    TextView brand = ui.text(active.title(), 22, Ui.INK);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    header.addView(brand, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
    if (!active.headerAction().isEmpty()) {
      Button action = ui.button(
        active.headerAction(),
        () -> active.performHeaderAction(),
        false
      );
      action.setTextColor(Ui.BLUE);
      if (!active.headerIcon().isEmpty()) {
        action.setContentDescription(active.headerAction());
        action.setText("");
        Drawable symbol = ui.icon(active.headerIcon(), Ui.MUTED);
        symbol.setBounds(0, 0, ui.dp(21), ui.dp(21));
        action.setCompoundDrawables(symbol, null, null, null);
      }
      header.addView(action);
    }
    root.addView(header);
    statusView = ui.text(message, 12, Ui.MUTED);
    statusView.setPadding(0, ui.dp(6), 0, ui.dp(6));
    statusView.setMaxLines(2);
    statusView.setVisibility(View.GONE);
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
    TextView screenStatus = statusView;
    root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
      android.graphics.Rect visible = new android.graphics.Rect();
      root.getWindowVisibleDisplayFrame(visible);
      boolean keyboard =
        getResources().getDisplayMetrics().heightPixels - visible.height() >
        ui.dp(160);
      int visibility = keyboard ? View.GONE : View.VISIBLE;
      if (nav.getVisibility() != visibility) {
        nav.setVisibility(visibility);
        if (keyboard) screenStatus.setVisibility(View.GONE);
      }
    });
    setContentView(root);
  }

  public void status(String value) {
    message = value == null ? "操作未完成，请重试" : value;
    if (statusView != null) {
      TextView feedback = statusView;
      feedback.setText(message);
      feedback.setVisibility(View.VISIBLE);
      feedback.postDelayed(() -> feedback.setVisibility(View.GONE), 5000);
    }
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
    if (automaticSync()) sync();
  }

  public void sync() {
    if (!account.isVerified()) return;
    if (!SYNCING.compareAndSet(false, true)) return;
    status("正在同步 · 本机记录已保留");
    IO.execute(() -> {
      String result;
      try {
        result = Sync.run(store, account, api);
      } catch (Exception e) {
        result = ui.errorMessage(e);
      } finally {
        SYNCING.set(false);
      }
      account.recordSync(result);
      String value = result;
      runOnUiThread(() -> {
        if (!isDestroyed()) {
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

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
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
    try {
      for (Store.Entry entry : store.entries())
        if (entry.pending != null) {
          status("还有未同步记录，请先同步或导出");
          return;
        }
      if (
        !getSharedPreferences("draft", 0).getString("content", "").isEmpty()
      ) {
        status("还有草稿，请先保存或导出");
        return;
      }
    } catch (Exception e) {
      status(ui.errorMessage(e));
      return;
    }
    new AlertDialog.Builder(this)
      .setTitle("断开这台设备？")
      .setMessage(
        "清除本机已同步记录，服务器内容保留。手机密钥仍然保留，可再次验证连接；如要撤销设备，请在服务器删除设备登记。"
      )
      .setNegativeButton("取消", null)
      .setPositiveButton("断开", (d, w) -> {
        account.clear();
        store.clear();
        getSharedPreferences("draft", 0).edit().clear().commit();
        getSharedPreferences("server_status", 0).edit().clear().commit();
        status("已断开，本机记录已清除");
        redraw();
      })
      .show();
  }
}
