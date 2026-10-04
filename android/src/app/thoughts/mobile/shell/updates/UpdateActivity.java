package app.thoughts.mobile.shell.updates;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.*;
import app.thoughts.mobile.core.connection.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** A settings subpage; update operations do not depend on a server connection. */
public final class UpdateActivity extends Activity implements Feature.Host {

  private Ui ui;
  private boolean visible;
  private TextView progress;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = new Runnable() {
    public void run() {
      if (!visible) return;
      if (
        UpdateManager.preferences(UpdateActivity.this)
          .getString("download_state", "")
          .equals("downloading")
      ) {
        long[] status = UpdateManager.progress(UpdateActivity.this);
        if (progress != null) progress.setText(
          status[0] == DownloadManager.STATUS_PAUSED
            ? "等待网络…"
            : status[0] == DownloadManager.STATUS_SUCCESSFUL
              ? "正在校验…"
              : "正在下载 · " +
                (status[2] > 0
                  ? Math.min(100, (status[1] * 100) / status[2]) + "%"
                  : "准备中")
        );
        UpdateManager.IO.execute(() ->
          UpdateManager.verifyCompleted(UpdateActivity.this)
        );
      }
      handler.postDelayed(this, 700);
    }
  };
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
      if (visible) render();
    }
  };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    ui = new Ui(this);
    render();
  }

  protected void onResume() {
    super.onResume();
    UpdateManager.pruneInstalled(this);
    visible = true;
    IntentFilter filter = new IntentFilter(UpdateManager.CHANGED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(
      receiver,
      filter,
      Context.RECEIVER_NOT_EXPORTED
    );
    else registerReceiver(receiver, filter);
    render();
    handler.post(tick);
    UpdateManager.resumeInstall(this);
  }

  protected void onPause() {
    visible = false;
    handler.removeCallbacks(tick);
    unregisterReceiver(receiver);
    super.onPause();
  }

  public void onConfigurationChanged(
    android.content.res.Configuration configuration
  ) {
    super.onConfigurationChanged(configuration);
    render();
  }

  private void render() {
    if (isFinishing() || isDestroyed()) return;
    LinearLayout root = ui.column();
    root.setBackgroundColor(Ui.PAPER);
    root.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(
        ui.dp(24),
        insets.getSystemWindowInsetTop() + ui.dp(12),
        ui.dp(24),
        insets.getSystemWindowInsetBottom() + ui.dp(12)
      );
      return insets;
    });
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(ui.iconButton("back", "返回", this::finish));
    TextView title = ui.text("应用更新", 22, Ui.INK);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setPadding(ui.dp(8), 0, ui.dp(8), 0);
    title.setMinimumHeight(ui.dp(52));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button check = ui.button(
      UpdateManager.checking() ? "检查中" : "检查",
      () -> UpdateManager.check(this, this::render),
      false
    );
    check.setEnabled(!UpdateManager.checking());
    header.addView(check);
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = ui.column();
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    ui.space(content, 24);
    String current = "";
    try {
      current = getPackageManager()
        .getPackageInfo(getPackageName(), 0)
        .versionName;
    } catch (Exception ignored) {}
    content.addView(ui.text(current, 32, Ui.INK));
    ui.space(content, 8);
    content.addView(ui.text("已安装", 13, Ui.MUTED));
    UpdateCatalog.Source source = UpdateCatalog.Source.load(this);
    LinearLayout updates = ui.card(content, "", "");
    ui.setting(updates, "更新分支", source.branch, this::chooseBranch);
    UpdateCatalog.Release latest = UpdateManager.selected(this),
      pending = UpdateManager.pending(this);
    String downloadState = UpdateManager.preferences(this).getString(
      "download_state",
      ""
    );
    LinearLayout update = ui.card(content, "", "");
    if (pending != null) {
      update.addView(ui.text(pending.label(), 17, Ui.INK));
      ui.space(update, 10);
      progress = ui.text(
        downloadState.equals("ready")
          ? "可以安装"
          : downloadState.equals("failed")
            ? UpdateManager.preferences(this).getString(
                "download_error",
                "下载失败"
              )
            : "正在下载…",
        13,
        downloadState.equals("failed") ? Ui.ALERT : Ui.MUTED
      );
      update.addView(progress);
      ui.space(update, 12);
      LinearLayout actions = new LinearLayout(this);
      if (downloadState.equals("ready")) actions.addView(
        ui.button("安装", () -> UpdateManager.install(this), true)
      );
      if (downloadState.equals("failed")) actions.addView(
        ui.button("重试", () -> download(pending), true)
      );
      actions.addView(
        ui.button(
          downloadState.equals("downloading") ? "取消下载" : "移除安装包",
          () -> {
            UpdateManager.cancel(this);
            render();
          },
          false
        )
      );
      update.addView(actions);
    } else if (latest != null) {
      progress = null;
      long installed = UpdateManager.installedCode(this, latest.packageName);
      update.addView(ui.text(latest.version, 20, Ui.INK));
      ui.space(update, 8);
      update.addView(
        ui.text(
          String.format(
            java.util.Locale.ROOT,
            "%.1f MB",
            latest.size / 1048576.0
          ),
          13,
          Ui.MUTED
        )
      );
      ui.space(update, 14);
      if (latest.minSdk > Build.VERSION.SDK_INT) update.addView(
        ui.text("此版本需要更高的 Android 版本", 14, Ui.MUTED)
      );
      else if (latest.code <= installed) update.addView(
        ui.text(
          latest.code == installed ? "已是最新版本" : "已安装更新的版本",
          14,
          Ui.MUTED
        )
      );
      else update.addView(ui.button("下载更新", () -> download(latest), true));
    } else {
      progress = null;
      update.addView(
        ui.text(
          UpdateManager.checking()
            ? "正在检查…"
            : !UpdateCatalog.validRepository(source.repository)
              ? "请先设置发布仓库"
              : UpdateManager.preferences(this).getLong("checked", 0) == 0
                ? "检查可用版本"
                : "此分支尚无可用版本",
          14,
          Ui.MUTED
        )
      );
    }
    String error = UpdateManager.preferences(this).getString("check_error", "");
    if (!error.isEmpty()) {
      ui.space(update, 12);
      update.addView(ui.text(error, 13, Ui.ALERT));
    }
    LinearLayout options = ui.card(content, "", "");
    Switch automatic = new Switch(this);
    automatic.setText("自动检查更新");
    automatic.setTextColor(Ui.INK);
    automatic.setTextSize(15);
    automatic.setMinHeight(ui.dp(56));
    automatic.setChecked(
      UpdateManager.preferences(this).getBoolean("automatic", true)
    );
    automatic.setOnCheckedChangeListener((v, enabled) -> {
      UpdateManager.preferences(this)
        .edit()
        .putBoolean("automatic", enabled)
        .apply();
      if (enabled) UpdateManager.automatic(this);
    });
    options.addView(automatic);
    ui.divider(options);
    ui.setting(
      options,
      "发布仓库",
      source.repository.isEmpty() ? "未设置" : source.repository,
      this::repository
    );
    ui.space(content, 24);
    setContentView(root);
  }

  private void download(UpdateCatalog.Release release) {
    try {
      if (
        Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
          android.content.pm.PackageManager.PERMISSION_GRANTED &&
        !UpdateManager.preferences(this).getBoolean("notification_asked", false)
      ) {
        UpdateManager.preferences(this)
          .edit()
          .putBoolean("notification_asked", true)
          .apply();
        requestPermissions(
          new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
          7821
        );
      }
      UpdateManager.start(this, release);
      render();
    } catch (Exception e) {
      status(UpdateManager.error(e));
    }
  }

  private void chooseBranch() {
    Set<String> values = new LinkedHashSet<>();
    for (UpdateCatalog.Release release : UpdateManager.cached(this))
      values.add(release.branch);
    if (values.isEmpty()) {
      UpdateManager.check(this, this::render);
      status("正在获取更新分支");
      return;
    }
    String[] branches = values.toArray(new String[0]);
    int selected = Arrays.asList(branches).indexOf(
      UpdateCatalog.Source.load(this).branch
    );
    new AlertDialog.Builder(this)
      .setTitle("更新分支")
      .setSingleChoiceItems(branches, selected, (d, which) -> {
        if (which == selected) {
          d.dismiss();
          return;
        }
        UpdateManager.cancel(this);
        UpdateManager.preferences(this)
          .edit()
          .putString("branch", branches[which])
          .apply();
        d.dismiss();
        render();
      })
      .setNegativeButton("取消", null)
      .show();
  }

  private void repository() {
    EditText input = ui.input("owner/repository", false);
    input.setSingleLine(true);
    input.setText(UpdateCatalog.Source.load(this).repository);
    LinearLayout form = ui.column();
    form.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0);
    form.addView(input);
    AlertDialog dialog = new AlertDialog.Builder(this)
      .setTitle("发布仓库")
      .setView(form)
      .setNegativeButton("取消", null)
      .setPositiveButton("保存", null)
      .create();
    dialog.setOnShowListener(d ->
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String value = input.getText().toString().trim();
        if (!UpdateCatalog.validRepository(value)) {
          input.setError("请输入 owner/repository");
          return;
        }
        UpdateManager.cancel(this);
        UpdateManager.preferences(this)
          .edit()
          .putString("repository", value)
          .remove("catalog")
          .remove("checked")
          .remove("check_error")
          .apply();
        dialog.dismiss();
        render();
        UpdateManager.check(this, this::render);
      })
    );
    dialog.show();
  }

  public Activity activity() {
    return this;
  }

  public ServerProfile serverProfile() {
    return null;
  }

  public ServerApi api() {
    return null;
  }

  public DeviceAccount account() {
    return null;
  }

  public void authorizationChanged() {}

  public void renderFeatureSettings(LinearLayout surface) {}

  public void configure(ServerProfile profile) {}

  public ExecutorService executor() {
    return UpdateManager.IO;
  }

  public String activeFeature() {
    return "updates";
  }

  public void navigate(String id) {
    finish();
  }

  public void redraw() {
    render();
  }

  public void status(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }
}
