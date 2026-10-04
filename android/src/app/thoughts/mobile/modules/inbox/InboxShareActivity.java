package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.*;
import app.thoughts.mobile.core.connection.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Share import and confirmation. Its durable draft is never a public thought. */
public final class InboxShareActivity extends Activity implements Feature.Host {

  private static final int PICK = 7303;
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final Map<String, ImportState> IMPORTS =
    new ConcurrentHashMap<>();

  private static final class ImportState {

    volatile int current, total;
    volatile long bytes;
    volatile boolean complete;
    volatile String error = "";
  }

  private final Handler handler = new Handler();
  private InboxStore store;
  private Ui ui;
  private String draftId;
  private EditText body, note;
  private TextView destination, feedback, importStatus;
  private LinearLayout attachments;
  private ProgressBar progress;
  private Button send, addFiles;
  private boolean submitting, finished, restoring;
  private int shownFiles = -1;
  private final Runnable poll = new Runnable() {
    public void run() {
      renderProgress();
      if (!isFinishing() && !isDestroyed() && importing()) handler.postDelayed(
        this,
        250
      );
    }
  };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    store = new InboxStore(this);
    ui = new Ui(this);
    try {
      draftId =
        state == null
          ? getIntent().getStringExtra("draft_id")
          : state.getString("draft_id");
      boolean fresh = draftId == null;
      if (fresh) draftId = store.createDraft(
        sharedText(getIntent()),
        subject(getIntent())
      );
      store.draft(draftId);
      render();
      if (fresh) {
        List<Uri> uris = sharedFiles(getIntent());
        if (!uris.isEmpty()) importFiles(uris);
        else if (getIntent().getBooleanExtra("pick_files", false)) pickFiles();
      } else if (
        state != null &&
        state.getBoolean("importing") &&
        !IMPORTS.containsKey(draftId)
      ) {
        status("上次导入被中断，已导入内容仍在；请补充未完成的文件。");
      }
    } catch (Exception e) {
      TextView error = new TextView(this);
      error.setPadding(32, 48, 32, 32);
      error.setText("无法读取收件箱草稿，请返回后重试。");
      setContentView(error);
      Toast.makeText(this, "草稿读取失败", Toast.LENGTH_LONG).show();
    }
  }

  private void render() throws Exception {
    JSONObject draft = store.draft(draftId);
    restoring = true;
    LinearLayout root = ui.column();
    root.setBackgroundColor(Ui.PAPER);
    root.setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(12));
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(
        ui.dp(22),
        insets.getSystemWindowInsetTop() + ui.dp(12),
        ui.dp(22),
        insets.getSystemWindowInsetBottom() + ui.dp(12)
      );
      return insets;
    });
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(ui.iconButton("back", "返回", this::onBackPressed));
    TextView title = ui.text("发送到收件箱", 20, Ui.INK);
    title.setTypeface(Typeface.create("sans-serif-medium", 0));
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setTooltipText(title.getText());
    title.setPadding(ui.dp(8), 0, ui.dp(8), 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(ui.button("丢弃", this::discard, false));
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout sheet = ui.column();
    sheet.setPadding(0, ui.dp(18), 0, ui.dp(20));
    scroll.addView(sheet);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    destination = ui.text("", 13, Ui.MUTED);
    sheet.addView(destination);
    destination.setPadding(0, ui.dp(8), 0, ui.dp(14));
    destination.setMinHeight(ui.dp(48));
    destination.setOnClickListener(v -> {
      if (serverProfile() == null) configureServer();
    });
    body = ui.input("文字或链接", true);
    body.setText(draft.optString("text"));
    body.setMinLines(3);
    body.setGravity(Gravity.TOP | Gravity.START);
    body.setBackground(ui.flatBackground(Ui.PAPER, 0));
    body.setPadding(0, ui.dp(12), 0, ui.dp(12));
    body.setContentDescription("收件箱文字");
    sheet.addView(body);
    ui.divider(sheet);
    ui.space(sheet, 18);
    attachments = ui.column();
    sheet.addView(attachments);
    addFiles = ui.button("＋ 添加文件", this::pickFiles, false);
    sheet.addView(addFiles);
    importStatus = ui.text("", 12, Ui.MUTED);
    sheet.addView(importStatus);
    progress = new ProgressBar(
      this,
      null,
      android.R.attr.progressBarStyleHorizontal
    );
    progress.setIndeterminate(true);
    sheet.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(6)));
    ui.space(sheet, 18);
    note = ui.input("备注（可选）", true);
    note.setText(draft.optString("note"));
    note.setMinLines(2);
    note.setFilters(new InputFilter[] { new InputFilter.LengthFilter(2000) });
    sheet.addView(note);
    feedback = ui.text("", 13, Ui.ALERT);
    feedback.setPadding(0, ui.dp(10), 0, ui.dp(10));
    feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(feedback);
    send = ui.button("发送", this::submit, true);
    root.addView(send, new LinearLayout.LayoutParams(-1, -2));
    body.addTextChangedListener(ui.watcher(this::saveSoon));
    note.addTextChangedListener(ui.watcher(this::saveSoon));
    setContentView(root);
    restoring = false;
    shownFiles = -1;
    renderAttachments();
    renderProgress();
    updateDestination();
  }

  private final Runnable saveDraft = () -> saveNow();

  private void saveSoon() {
    if (!restoring) {
      handler.removeCallbacks(saveDraft);
      handler.postDelayed(saveDraft, 350);
    }
  }

  private boolean saveNow() {
    if (finished || draftId == null || body == null) return true;
    try {
      store.updateDraft(
        draftId,
        body.getText().toString(),
        note.getText().toString()
      );
      return true;
    } catch (Exception e) {
      status("草稿未能保存，请检查手机存储空间");
      return false;
    }
  }

  protected void onSaveInstanceState(Bundle state) {
    saveNow();
    state.putString("draft_id", draftId);
    state.putBoolean("importing", importing());
    super.onSaveInstanceState(state);
  }

  protected void onPause() {
    saveNow();
    handler.removeCallbacks(poll);
    super.onPause();
  }

  protected void onResume() {
    super.onResume();
    if (destination != null) {
      updateDestination();
      handler.removeCallbacks(poll);
      handler.post(poll);
    }
  }

  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  public void onConfigurationChanged(android.content.res.Configuration c) {
    super.onConfigurationChanged(c);
  }

  private void updateDestination() {
    ServerProfile profile = serverProfile();
    destination.setText(
      profile == null ? "选择目标 · 配置服务器 ›" : "发送到  " + profile.name
    );
    send.setText(
      profile == null ? "配置服务器" : submitting ? "正在发送…" : "发送"
    );
  }

  private boolean importing() {
    ImportState state = IMPORTS.get(draftId);
    return state != null && !state.complete;
  }

  private void renderProgress() {
    if (progress == null) return;
    ImportState state = IMPORTS.get(draftId);
    boolean active = state != null && !state.complete;
    progress.setVisibility(active ? View.VISIBLE : View.GONE);
    importStatus.setVisibility(state == null ? View.GONE : View.VISIBLE);
    if (state != null) importStatus.setText(
      active
        ? "正在导入 " +
            Math.min(state.current + 1, state.total) +
            " / " +
            state.total +
            " · " +
            Ui.size(state.bytes)
        : state.error.isEmpty()
          ? "文件已就绪"
          : state.error
    );
    addFiles.setEnabled(!active && !submitting);
    send.setEnabled(!active && !submitting);
    try {
      renderAttachments();
    } catch (Exception e) {
      status("无法读取附件");
    }
  }

  private void renderAttachments() throws Exception {
    JSONArray files = store.draft(draftId).getJSONArray("files");
    if (shownFiles == files.length()) return;
    shownFiles = files.length();
    attachments.removeAllViews();
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.getJSONObject(i);
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, ui.dp(8), 0, ui.dp(8));
      LinearLayout names = ui.column();
      TextView name = ui.text(file.optString("name"), 15, Ui.INK);
      name.setSingleLine(true);
      name.setEllipsize(TextUtils.TruncateAt.END);
      name.setTooltipText(name.getText());
      names.addView(name);
      ui.space(names, 6);
      names.addView(ui.text(Ui.size(file.optLong("size")), 12, Ui.MUTED));
      row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
      Button remove = ui.button(
        "移除",
        () -> {
          if (importing() || submitting) {
            status("请等待文件导入完成");
            return;
          }
          try {
            store.removeDraftFile(draftId, file.optString("id"));
            shownFiles = -1;
            renderAttachments();
          } catch (Exception e) {
            status("附件移除失败");
          }
        },
        false
      );
      remove.setContentDescription("移除 " + file.optString("name"));
      row.addView(remove);
      attachments.addView(row);
      ui.divider(attachments);
    }
  }

  private void pickFiles() {
    if (importing()) return;
    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
      .setType("*/*")
      .addCategory(Intent.CATEGORY_OPENABLE)
      .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try {
      startActivityForResult(picker, PICK);
    } catch (ActivityNotFoundException e) {
      status("没有可用的文件选择器");
    }
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == PICK && result == RESULT_OK && data != null) {
      List<Uri> uris = sharedFiles(data);
      if (data.getData() != null && !uris.contains(data.getData())) uris.add(
        data.getData()
      );
      importFiles(uris);
    }
  }

  private void importFiles(List<Uri> uris) {
    if (uris.isEmpty()) return;
    ImportState state = new ImportState();
    state.total = uris.size();
    IMPORTS.put(draftId, state);
    final String id = draftId;
    final InboxStore destinationStore = new InboxStore(getApplicationContext());
    IO.execute(() -> {
      int failed = 0;
      for (Uri uri : uris) {
        state.bytes = 0;
        try {
          destinationStore.importFile(id, uri, bytes -> state.bytes = bytes);
        } catch (Exception e) {
          failed++;
        }
        state.current++;
      }
      state.error = failed == 0 ? "" : failed + " 个文件未能导入，请重新选择";
      state.complete = true;
      destinationStore.close();
    });
    handler.removeCallbacks(poll);
    handler.post(poll);
  }

  private void submit() {
    if (importing() || submitting || !saveNow()) return;
    ServerProfile profile = serverProfile();
    if (profile == null) {
      configureServer();
      return;
    }
    try {
      JSONObject draft = store.draft(draftId);
      String value = draft.optString("text"),
        annotation = draft.optString("note");
      if (value.codePointCount(0, value.length()) > 20000) {
        status("文字最多 20000 字，请删减或以文件发送");
        return;
      }
      if (annotation.codePointCount(0, annotation.length()) > 2000) {
        status("备注最多 2000 字");
        return;
      }
      if (value.indexOf('\0') >= 0 || annotation.indexOf('\0') >= 0) {
        status("文字中包含无法发送的空字符，请删除后重试");
        return;
      }
      if (draft.getJSONArray("files").length() > 32) {
        status("每次最多发送 32 个文件，请移除部分附件");
        return;
      }
      if (
        draft.optString("text").trim().isEmpty() &&
        draft.getJSONArray("files").length() == 0
      ) {
        status("添加文字或文件后再发送");
        return;
      }
    } catch (Exception e) {
      status("草稿读取失败");
      return;
    }
    if (!account().can("inbox.write")) {
      CapabilityEnrollment.show(this, "inbox.write", this::submit);
      return;
    }
    submitting = true;
    send.setEnabled(false);
    body.setEnabled(false);
    note.setEnabled(false);
    addFiles.setEnabled(false);
    send.setText("正在发送…");
    IO.execute(() -> {
      try {
        store.enqueueDraft(profile, draftId);
        runOnUiThread(() -> {
          if (isDestroyed()) return;
          finished = true;
          handler.removeCallbacks(saveDraft);
          IMPORTS.remove(draftId);
          if (!InboxFeature.requestNotifications(this)) finishSending();
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          if (isDestroyed()) return;
          submitting = false;
          send.setEnabled(true);
          body.setEnabled(true);
          note.setEnabled(true);
          addFiles.setEnabled(true);
          updateDestination();
          if (
            e instanceof ConnectionFailure &&
            (((ConnectionFailure) e).code == 401 ||
              ((ConnectionFailure) e).code == 403)
          ) CapabilityEnrollment.show(this, "inbox.write", this::submit);
          else status(
            e instanceof ConnectionFailure
              ? e.getMessage()
              : "发送失败，草稿已保留，请重试"
          );
        });
      }
    });
  }

  private void finishSending() {
    try {
      TransferService.start(this);
    } catch (RuntimeException e) {
      Toast.makeText(
        this,
        "任务已保存，请在收件箱继续传输",
        Toast.LENGTH_LONG
      ).show();
    }
    openInbox();
    finish();
  }

  public void onRequestPermissionsResult(
    int request,
    String[] permissions,
    int[] results
  ) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == InboxFeature.NOTIFICATION_PERMISSION) finishSending();
  }

  private void configureServer() {
    if (!saveNow()) return;
    launchMain("server", false);
  }

  private void launchMain(String feature, boolean returnToMain) {
    Intent intent = getPackageManager().getLaunchIntentForPackage(
      getPackageName()
    );
    if (intent == null) {
      status("无法打开应用主页");
      return;
    }
    intent.setFlags(
      returnToMain
        ? Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
        : 0
    );
    intent.putExtra("open_feature", feature);
    startActivity(intent);
  }

  private void openInbox() {
    launchMain("inbox", true);
  }

  public void onBackPressed() {
    if (submitting) {
      status("正在创建传输任务，请稍候");
      return;
    }
    if (importing()) {
      status("正在导入文件，请等待完成后返回");
      return;
    }
    if (saveNow()) {
      try {
        JSONObject draft = store.draft(draftId);
        if (
          draft.optString("text").trim().isEmpty() &&
          draft.optString("note").trim().isEmpty() &&
          draft.getJSONArray("files").length() == 0
        ) {
          store.deleteDraft(draftId);
          finished = true;
          IMPORTS.remove(draftId);
          finish();
          return;
        }
      } catch (Exception ignored) {}
      Toast.makeText(this, "已保留为收件箱草稿", Toast.LENGTH_SHORT).show();
      finish();
    }
  }

  private void discard() {
    if (importing() || submitting) {
      status("请等待当前操作完成");
      return;
    }
    new AlertDialog.Builder(this)
      .setTitle("丢弃这份草稿？")
      .setNegativeButton("保留", null)
      .setPositiveButton("丢弃", (d, w) -> {
        try {
          store.deleteDraft(draftId);
          finished = true;
          IMPORTS.remove(draftId);
          finish();
        } catch (Exception e) {
          status("草稿删除失败");
        }
      })
      .show();
  }

  static List<Uri> sharedFiles(Intent intent) {
    LinkedHashSet<Uri> files = new LinkedHashSet<>();
    try {
      if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
        ArrayList<Uri> values = intent.getParcelableArrayListExtra(
          Intent.EXTRA_STREAM
        );
        if (values != null) files.addAll(values);
      } else {
        android.os.Parcelable value = intent.getParcelableExtra(
          Intent.EXTRA_STREAM
        );
        if (value instanceof Uri) files.add((Uri) value);
      }
      ClipData clip = intent.getClipData();
      if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
        Uri uri = clip.getItemAt(i).getUri();
        if (uri != null) files.add(uri);
      }
    } catch (RuntimeException ignored) {}
    files.remove(null);
    return new ArrayList<>(files);
  }

  static String sharedText(Intent intent) {
    CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    if (text != null) return text.toString();
    String html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
    if (html != null) return Html.fromHtml(
      html,
      Html.FROM_HTML_MODE_COMPACT
    ).toString();
    StringBuilder value = new StringBuilder();
    ClipData clip = intent.getClipData();
    if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
      CharSequence part = clip.getItemAt(i).getText();
      if (part != null) {
        if (value.length() > 0) value.append('\n');
        value.append(part);
      }
    }
    return value.toString();
  }

  private static String subject(Intent intent) {
    String value = intent.getStringExtra(Intent.EXTRA_SUBJECT);
    return value == null || value.equals(sharedText(intent)) ? "" : value;
  }

  public Activity activity() {
    return this;
  }

  public ServerProfile serverProfile() {
    return ServerProfile.load(this);
  }

  public ServerApi api() {
    return new ServerApi(serverProfile());
  }

  public DeviceAccount account() {
    return new DeviceAccount(this);
  }

  public void authorizationChanged() {}

  public void renderFeatureSettings(LinearLayout surface) {}

  public void configure(ServerProfile profile) {
    profile.save(this);
    updateDestination();
  }

  public ExecutorService executor() {
    return IO;
  }

  public String activeFeature() {
    return "inbox";
  }

  public void navigate(String id) {
    launchMain(id, false);
  }

  public void redraw() {
    updateDestination();
  }

  public void status(String message) {
    if (feedback != null) feedback.setText(message);
  }
}
