package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.*;
import app.thoughts.mobile.core.connection.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Private relay inbox. Views observe durable transfers; they never own an SSH session. */
public final class InboxFeature extends Ui implements Feature {

  private static final ExecutorService WORK =
    Executors.newSingleThreadExecutor();
  private static final ExecutorService SEARCH = Executors.newFixedThreadPool(2);

  interface PageLoader {
    JSONObject load(ServerProfile profile, String path) throws Exception;
  }

  private final PageLoader pageLoader;
  private volatile int requestGeneration;
  private String loadedQuery = "",
    loadedFilter = "all";
  private JSONObject searchResult, detailSnapshot;
  private ServerProfile resultProfile, renderedProfile;
  private boolean loadingMore, remoteFailed, searchPending;
  private final Runnable searchRemote = () -> {
    searchPending = false;
    loadPage(false);
  };
  private static final int SAVE_FILE = 7304;
  static final int NOTIFICATION_PERMISSION = 7305;
  private final InboxStore store;
  private final Handler handler = new Handler();
  private LinearLayout content, tasks, entries, drafts;
  private TextView summary;
  private String query = "",
    filter = "all",
    detailId = "";
  private boolean listening, loading, enableNeeded, updatePending;
  private final Runnable update = () -> {
    updatePending = false;
    if (content != null) updateLists();
  };
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
      if (!updatePending) {
        updatePending = true;
        handler.postDelayed(update, 250);
      }
    }
  };

  public InboxFeature(Feature.Host host) {
    this(host, (profile, path) -> {
      try (InboxTransfer transfer = new InboxTransfer(profile)) {
        return transfer.request(path, "GET", null);
      }
    });
  }

  InboxFeature(Feature.Host host, PageLoader loader) {
    super(host);
    store = new InboxStore(activity);
    pageLoader = loader;
  }

  public String id() {
    return "inbox";
  }

  public String label() {
    return "收件箱";
  }

  public String title() {
    return detailId.isEmpty() ? label() : "条目";
  }

  public String headerAction() {
    return detailId.isEmpty() ? "添加" : "更多";
  }

  public boolean hasBack() {
    return !detailId.isEmpty();
  }

  public void back() {
    detailId = "";
    host.redraw();
  }

  public void performHeaderAction() {
    if (!detailId.isEmpty()) {
      JSONObject item = item(detailId);
      if (item != null) itemMenu(item);
      return;
    }
    new AlertDialog.Builder(activity)
      .setTitle("添加到收件箱")
      .setItems(new String[] { "文字或链接", "选择文件" }, (d, which) -> {
        Intent intent = new Intent(activity, InboxShareActivity.class);
        if (which == 1) intent.putExtra("pick_files", true);
        activity.startActivity(intent);
      })
      .show();
  }

  public void render(LinearLayout surface) {
    if (!InboxStore.sameIdentity(renderedProfile, host.serverProfile())) {
      requestGeneration++;
      loading = loadingMore = false;
      searchResult = null;
      detailSnapshot = null;
      renderedProfile = host.serverProfile();
    }
    content = surface;
    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    listen();
    if (!detailId.isEmpty()) {
      renderDetail(surface);
      return;
    }
    LinearLayout top = new LinearLayout(activity);
    top.setGravity(Gravity.CENTER_VERTICAL);
    ServerProfile profile = host.serverProfile();
    summary = text(
      profile == null ? "尚未连接服务器" : profile.name,
      13,
      MUTED
    );
    summary.setMaxLines(2);
    top.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(button("本机文件", this::localFiles, false));
    top.addView(button("刷新", this::refresh, false));
    surface.addView(top);
    if (profile == null) surface.addView(
      button("配置服务器", () -> host.navigate("settings"), true)
    );
    EditText search = input("搜索文字、备注或文件名", false);
    search.setSingleLine(true);
    search.setFilters(new android.text.InputFilter[] {
      new android.text.InputFilter.LengthFilter(200),
    });
    search.setText(query);
    search.setContentDescription("搜索收件箱");
    search.addTextChangedListener(
      watcher(() -> {
        query = search.getText().toString();
        scheduleSearch();
      })
    );
    space(surface, 12);
    surface.addView(search, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout filters = new LinearLayout(activity);
    String[] ids = { "all", "files", "text" },
      names = { "全部", "文件", "文字" };
    for (int i = 0; i < ids.length; i++) {
      final String next = ids[i];
      Button b = button(
        names[i],
        () -> {
          filter = next;
          for (int j = 0; j < filters.getChildCount(); j++) {
            View child = filters.getChildAt(j);
            boolean chosen = next.equals(child.getTag());
            child.setSelected(chosen);
            ((Button) child).setTextColor(chosen ? BLUE : MUTED);
          }
          scheduleSearch();
        },
        false
      );
      b.setTag(next);
      b.setSelected(filter.equals(next));
      b.setTextColor(filter.equals(next) ? BLUE : MUTED);
      filters.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    surface.addView(filters);
    divider(surface);
    drafts = column();
    tasks = column();
    entries = column();
    surface.addView(drafts);
    surface.addView(tasks);
    surface.addView(entries);
    updateLists();
  }

  public void enter() {
    listen();
    refresh();
  }

  public void resume() {
    listen();
    if (content != null) updateLists();
    refresh();
  }

  public void pause() {
    unlisten(true);
  }

  public void leave() {
    content = tasks = entries = drafts = null;
    summary = null;
    unlisten(false);
    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  private void listen() {
    if (listening) return;
    IntentFilter actions = new IntentFilter(TransferService.ACTION_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(
      receiver,
      actions,
      Context.RECEIVER_NOT_EXPORTED
    );
    else activity.registerReceiver(receiver, actions);
    listening = true;
  }

  private void unlisten(boolean invalidate) {
    handler.removeCallbacks(update);
    if (invalidate) {
      handler.removeCallbacks(searchRemote);
      requestGeneration++;
      loading = loadingMore = searchPending = false;
    }
    updatePending = false;
    if (listening) {
      activity.unregisterReceiver(receiver);
      listening = false;
    }
  }

  private void scheduleSearch() {
    handler.removeCallbacks(searchRemote);
    requestGeneration++;
    loading = loadingMore = false;
    remoteFailed = false;
    searchPending = true;
    updateEntries();
    handler.postDelayed(searchRemote, 300);
  }

  public void refresh() {
    handler.removeCallbacks(searchRemote);
    searchPending = false;
    loadPage(false);
  }

  private boolean resultMatches() {
    return (
      searchResult != null &&
      loadedQuery.equals(query.trim()) &&
      loadedFilter.equals(filter) &&
      InboxStore.sameIdentity(resultProfile, host.serverProfile())
    );
  }

  private JSONObject visibleResponse() {
    if (!query.trim().isEmpty() || !filter.equals("all")) {
      if (resultMatches()) return searchResult;
    }
    try {
      return store.cachedResponse(host.serverProfile());
    } catch (Exception e) {
      status("无法读取收件箱缓存");
      return new JSONObject();
    }
  }

  private void loadPage(boolean more) {
    final ServerProfile profile = host.serverProfile();
    if (loading || profile == null || content == null) {
      searchPending = false;
      return;
    }
    final String requestedQuery = query.trim(),
      requestedFilter = filter;
    final JSONObject previous = visibleResponse();
    final int offset = more ? previous.optInt("next_offset", 0) : 0;
    if (more && (!previous.optBoolean("has_more") || offset <= 0)) return;
    final int generation = ++requestGeneration;
    loading = true;
    loadingMore = more;
    remoteFailed = false;
    if (summary != null) summary.setText(
      more ? "正在加载…" : requestedQuery.isEmpty() ? "正在刷新…" : "正在搜索…"
    );
    updateEntries();
    SEARCH.execute(() -> {
      if (generation != requestGeneration) return;
      try {
        JSONObject response = pageLoader.load(
          profile,
          "/inbox?limit=100&offset=" +
            offset +
            "&type=" +
            requestedFilter +
            "&q=" +
            Uri.encode(requestedQuery)
        );
        runOnUiThread(() -> {
          if (
            !accept(generation, profile, requestedQuery, requestedFilter)
          ) return;
          try {
            if (more) {
              LinkedHashMap<String, JSONObject> combined =
                new LinkedHashMap<>();
              for (JSONArray page : new JSONArray[] {
                previous.optJSONArray("items"),
                response.optJSONArray("items"),
              }) {
                if (page != null) for (int i = 0; i < page.length(); i++) {
                  JSONObject item = page.optJSONObject(i);
                  if (item != null) combined.put(item.optString("id"), item);
                }
              }
              JSONArray values = new JSONArray();
              for (JSONObject item : combined.values()) values.put(item);
              response.put("items", values);
            }
            searchResult = response;
            loadedQuery = requestedQuery;
            loadedFilter = requestedFilter;
            resultProfile = profile;
            if (
              requestedQuery.isEmpty() && requestedFilter.equals("all")
            ) store.cache(profile, response);
            enableNeeded = false;
            loading = loadingMore = false;
            if (summary != null) summary.setText(profile.name);
            updateLists();
          } catch (Exception e) {
            failed(generation, profile, requestedQuery, requestedFilter, e);
          }
        });
      } catch (Exception e) {
        runOnUiThread(() ->
          failed(generation, profile, requestedQuery, requestedFilter, e)
        );
      }
    });
  }

  private boolean accept(
    int generation,
    ServerProfile profile,
    String requestedQuery,
    String requestedFilter
  ) {
    return (
      generation == requestGeneration &&
      content != null &&
      query.trim().equals(requestedQuery) &&
      filter.equals(requestedFilter) &&
      InboxStore.sameIdentity(profile, host.serverProfile())
    );
  }

  private void failed(
    int generation,
    ServerProfile profile,
    String requestedQuery,
    String requestedFilter,
    Exception error
  ) {
    if (!accept(generation, profile, requestedQuery, requestedFilter)) return;
    loading = loadingMore = false;
    remoteFailed = true;
    enableNeeded =
      error instanceof ConnectionFailure &&
      (((ConnectionFailure) error).code == 401 ||
        ((ConnectionFailure) error).code == 403);
    if (summary != null) summary.setText(
      enableNeeded ? "收件箱尚未授权" : "连接失败 · 显示本机缓存"
    );
    updateLists();
    if (!enableNeeded) status(errorMessage(error));
  }

  private void enable() {
    CapabilityEnrollment.show(host, "inbox.write", this::refresh);
  }

  private void updateLists() {
    if (!detailId.isEmpty()) {
      content.removeAllViews();
      renderDetail(content);
      return;
    }
    if (tasks == null) return;
    drafts.removeAllViews();
    JSONArray pending = localDrafts();
    for (int i = 0; i < pending.length(); i++) {
      JSONObject draft = pending.optJSONObject(i);
      if (draft == null) continue;
      rowLink(drafts, display(draft), "未发送草稿", () ->
        activity.startActivity(
          new Intent(activity, InboxShareActivity.class).putExtra(
            "draft_id",
            draft.optString("id")
          )
        )
      );
    }
    tasks.removeAllViews();
    for (InboxStore.Task task : localTasks()) {
      if (task.state.equals("done") || task.state.equals("canceled")) continue;
      taskRow(tasks, task);
    }
    updateEntries();
  }

  private void updateEntries() {
    if (entries == null) return;
    entries.removeAllViews();
    if (
      enableNeeded ||
      (host.serverProfile() != null && !host.account().can("inbox.write"))
    ) entries.addView(button("启用收件箱", this::enable, true));
    JSONArray all =
      host.serverProfile() == null ? new JSONArray() : localEntries();
    int shown = 0;
    String needle = query.trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < all.length(); i++) {
      JSONObject item = all.optJSONObject(i);
      if (item == null) continue;
      JSONArray files = item.optJSONArray("files");
      int count = files == null ? 0 : files.length();
      if (
        (filter.equals("files") && count == 0) ||
        (filter.equals("text") && item.optString("text").trim().isEmpty())
      ) continue;
      StringBuilder haystack = new StringBuilder(display(item))
        .append(' ')
        .append(item.optString("text"))
        .append(' ')
        .append(item.optString("note"));
      if (files != null) for (int f = 0; f < files.length(); f++) haystack
        .append(' ')
        .append(files.optJSONObject(f).optString("name"));
      if (
        !haystack.toString().toLowerCase(Locale.ROOT).contains(needle)
      ) continue;
      String detail =
        count > 0
          ? count + " 个文件"
          : isLink(item.optString("text"))
            ? "链接"
            : "文字";
      String created = date(item.optString("created_at"));
      if (!created.isEmpty()) detail += " · " + created;
      rowLink(entries, display(item), detail, () -> {
        detailId = item.optString("id");
        detailSnapshot = item;
        host.redraw();
      });
      shown++;
    }
    if (shown == 0) {
      space(entries, 28);
      entries.addView(
        text(
          loading || searchPending
            ? "正在查找…"
            : needle.isEmpty() && filter.equals("all")
              ? "还没有收到内容"
              : "没有符合条件的内容",
          18,
          INK
        )
      );
      space(entries, 10);
      entries.addView(
        text(
          needle.isEmpty() && filter.equals("all")
            ? "从手机分享过来，或添加一条文字。"
            : "试试其他关键词或分类。",
          13,
          MUTED
        )
      );
    }
    JSONObject response = visibleResponse();
    boolean validPage =
      (query.trim().isEmpty() && filter.equals("all")) || resultMatches();
    if (validPage && response.optBoolean("has_more")) {
      Button more = button(
        loadingMore ? "正在加载…" : "加载更多",
        () -> loadPage(true),
        false
      );
      more.setEnabled(!loading && !searchPending);
      entries.addView(more, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    if (remoteFailed && !enableNeeded) entries.addView(
      button("重试", this::refresh, false)
    );
  }

  private void renderDetail(LinearLayout surface) {
    JSONObject item = item(detailId);
    if (item == null) {
      surface.addView(text("条目已删除，或尚未缓存。", 15, MUTED));
      surface.addView(button("返回收件箱", this::back, false));
      return;
    }
    TextView title = text(display(item), 23, INK);
    surface.addView(title);
    space(surface, 12);
    String body = item.optString("text"),
      note = item.optString("note");
    if (!body.isEmpty()) {
      TextView value = text(body, 16, INK);
      value.setTextIsSelectable(true);
      surface.addView(value);
      LinearLayout actions = new LinearLayout(activity);
      actions.addView(button("复制", () -> copy(body), false));
      if (isLink(body)) actions.addView(
        button(
          "打开链接",
          () -> {
            try {
              activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(body.trim()))
              );
            } catch (ActivityNotFoundException e) {
              status("没有可以打开链接的应用");
            }
          },
          false
        )
      );
      surface.addView(actions);
    }
    if (!note.isEmpty()) {
      space(surface, 12);
      surface.addView(text(note, 14, MUTED));
    }
    JSONArray files = item.optJSONArray("files");
    if (files != null && files.length() > 0) {
      space(surface, 24);
      surface.addView(text("文件 · " + files.length(), 12, MUTED));
      for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.optJSONObject(i);
        if (file == null) continue;
        InboxStore.Task local = download(item, file);
        String state =
          local != null && local.state.equals("done") ? "已下载" : "下载 ↓";
        rowLink(
          surface,
          file.optString("name", "文件"),
          size(file.optLong("size")) + " · " + state,
          () -> fileMenu(item, file)
        );
        if (
          local != null &&
          !local.state.equals("done") &&
          !local.state.equals("canceled")
        ) taskRow(surface, local);
      }
    }
  }

  private JSONArray localDrafts() {
    try {
      return store.drafts();
    } catch (Exception e) {
      status("无法读取收件箱草稿");
      return new JSONArray();
    }
  }

  private JSONArray localEntries() {
    try {
      JSONArray entries = visibleResponse().optJSONArray("items");
      return entries == null ? new JSONArray() : entries;
    } catch (Exception e) {
      status("无法读取收件箱缓存");
      return new JSONArray();
    }
  }

  private List<InboxStore.Task> localTasks() {
    try {
      return store.tasks(host.serverProfile());
    } catch (Exception e) {
      status("无法读取传输任务");
      return Collections.emptyList();
    }
  }

  private InboxStore.Task download(JSONObject item, JSONObject file) {
    try {
      return store.findDownload(
        host.serverProfile(),
        item.optString("id"),
        file.optString("id")
      );
    } catch (Exception e) {
      status("无法读取下载记录");
      return null;
    }
  }

  private JSONObject item(String id) {
    if (host.serverProfile() == null) return null;
    JSONArray values = localEntries();
    for (int i = 0; i < values.length(); i++) {
      JSONObject item = values.optJSONObject(i);
      if (item != null && id.equals(item.optString("id"))) return item;
    }
    return detailSnapshot != null && id.equals(detailSnapshot.optString("id"))
      ? detailSnapshot
      : null;
  }

  private void localFiles() {
    try {
      List<InboxStore.Task> downloads = new ArrayList<>();
      for (InboxStore.Task task : store.tasks())
        if (
          task.kind.equals("download") && task.state.equals("done")
        ) downloads.add(task);
      if (downloads.isEmpty()) {
        new AlertDialog.Builder(activity)
          .setTitle("本机文件")
          .setMessage("还没有下载到手机的文件。")
          .setPositiveButton("知道了", null)
          .show();
        return;
      }
      String[] names = new String[downloads.size()];
      for (int i = 0; i < names.length; i++) {
        InboxStore.Task task = downloads.get(i);
        names[i] =
          task.document.optJSONObject("file").optString("name") +
          "\n" +
          size(task.total) +
          " · " +
          task.profile.name;
      }
      new AlertDialog.Builder(activity)
        .setTitle("本机文件")
        .setItems(names, (dialog, which) -> {
          InboxStore.Task task = downloads.get(which);
          new AlertDialog.Builder(activity)
            .setTitle(task.document.optJSONObject("file").optString("name"))
            .setItems(new String[] { "打开", "移除本机副本" }, (d, action) -> {
              if (action == 0) InboxFiles.open(activity, task);
              else new AlertDialog.Builder(activity)
                .setTitle("移除本机副本？")
                .setMessage("服务器上的文件会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (confirm, w) -> {
                  try {
                    store.removeTask(task.id);
                    updateLists();
                    status("已移除本机副本");
                  } catch (Exception e) {
                    status("文件移除失败，请重试");
                  }
                })
                .show();
            })
            .show();
        })
        .setNegativeButton("关闭", null)
        .show();
    } catch (Exception e) {
      status("无法读取本机文件");
    }
  }

  private void fileMenu(JSONObject item, JSONObject file) {
    InboxStore.Task local = download(item, file);
    if (local == null || !local.state.equals("done")) {
      if (local != null && !local.state.equals("canceled")) {
        status("请在传输任务中继续下载");
        return;
      }
      try {
        store.enqueueDownload(host.serverProfile(), item, file);
        if (!requestNotifications(activity)) TransferService.start(activity);
        updateLists();
      } catch (Exception e) {
        status(errorMessage(e));
      }
      return;
    }
    new AlertDialog.Builder(activity)
      .setTitle(file.optString("name"))
      .setItems(
        new String[] {
          file.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")
            ? "安装应用"
            : "打开",
          "另存为…",
          "分享…",
        },
        (d, which) -> {
          try {
            if (which == 0) InboxFiles.open(activity, local);
            else if (which == 2) InboxFiles.share(activity, local);
            else {
              activity
                .getSharedPreferences("inbox_ui", 0)
                .edit()
                .putString("save_task", local.id)
                .commit();
              activity.startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType(file.optString("mime", "application/octet-stream"))
                  .putExtra(Intent.EXTRA_TITLE, file.optString("name", "文件")),
                SAVE_FILE
              );
            }
          } catch (Exception e) {
            status(e.getMessage() == null ? "无法打开文件" : e.getMessage());
          }
        }
      )
      .show();
  }

  static boolean requestNotifications(Activity activity) {
    if (
      Build.VERSION.SDK_INT < 33 ||
      activity.checkSelfPermission(
        android.Manifest.permission.POST_NOTIFICATIONS
      ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return false;
    android.content.SharedPreferences prefs = activity.getSharedPreferences(
      "inbox_ui",
      0
    );
    if (prefs.getBoolean("notification_requested", false)) return false;
    prefs.edit().putBoolean("notification_requested", true).apply();
    try {
      activity.requestPermissions(
        new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
        NOTIFICATION_PERMISSION
      );
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public boolean onRequestPermissionsResult(int request) {
    if (request != NOTIFICATION_PERMISSION) return false;
    try {
      TransferService.start(activity);
    } catch (RuntimeException e) {
      status("下载任务已保存，请点击重试继续");
    }
    return true;
  }

  public boolean onActivityResult(int request, int result, Intent data) {
    if (request != SAVE_FILE) return false;
    String taskId = activity
      .getSharedPreferences("inbox_ui", 0)
      .getString("save_task", "");
    activity
      .getSharedPreferences("inbox_ui", 0)
      .edit()
      .remove("save_task")
      .apply();
    if (
      result == Activity.RESULT_OK && data != null && data.getData() != null
    ) WORK.execute(() -> {
      try {
        InboxFiles.saveTo(activity, store.task(taskId), data.getData());
        runOnUiThread(() -> status("文件已保存"));
      } catch (Exception e) {
        runOnUiThread(() -> status("保存失败，请重新选择位置"));
      }
    });
    return true;
  }

  private void itemMenu(JSONObject item) {
    new AlertDialog.Builder(activity)
      .setTitle("条目操作")
      .setItems(new String[] { "重命名", "删除" }, (d, which) -> {
        if (which == 0) {
          EditText title = input("名称", false);
          title.setText(display(item));
          title.setSelectAllOnFocus(true);
          new AlertDialog.Builder(activity)
            .setTitle("重命名")
            .setView(title)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, w) ->
              mutate(item, "PATCH", title.getText().toString().trim())
            )
            .show();
        } else new AlertDialog.Builder(activity)
          .setTitle("删除这个条目？")
          .setMessage("服务器上的文字和文件将被删除。")
          .setNegativeButton("取消", null)
          .setPositiveButton("删除", (dialog, w) ->
            mutate(item, "DELETE", null)
          )
          .show();
      })
      .show();
  }

  private void mutate(JSONObject item, String method, String name) {
    if (name != null && name.isEmpty()) {
      status("名称不能为空");
      return;
    }
    ServerProfile profile = host.serverProfile();
    WORK.execute(() -> {
      try (InboxTransfer transfer = new InboxTransfer(profile)) {
        transfer.request(
          "/inbox/items/" + item.optString("id"),
          method,
          name == null ? null : new JSONObject().put("title", name)
        );
        store.cache(profile, transfer.request("/inbox", "GET", null));
        runOnUiThread(() -> {
          if (method.equals("DELETE")) {
            detailId = "";
            detailSnapshot = null;
          }
          searchResult = null;
          host.redraw();
          refresh();
          status(method.equals("DELETE") ? "已删除" : "已重命名");
        });
      } catch (Exception e) {
        runOnUiThread(() -> status(errorMessage(e)));
      }
    });
  }

  private void taskRow(LinearLayout parent, InboxStore.Task task) {
    space(parent, 16);
    TextView name = text(
      task.kind.equals("upload")
        ? display(task.document)
        : task.document.optJSONObject("file").optString("name", "下载"),
      14,
      INK
    );
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    parent.addView(name);
    Map<String, String> labels = new HashMap<>();
    labels.put("importing", "正在准备");
    labels.put("queued", "等待传输");
    labels.put("running", task.kind.equals("upload") ? "正在上传" : "正在下载");
    labels.put("paused", "已暂停");
    labels.put("failed", "传输失败");
    parent.addView(
      text(
        labels.containsKey(task.state) ? labels.get(task.state) : task.state,
        12,
        MUTED
      )
    );
    ProgressBar progress = new ProgressBar(
      activity,
      null,
      android.R.attr.progressBarStyleHorizontal
    );
    progress.setMax(1000);
    progress.setIndeterminate(task.total <= 0);
    progress.setProgress(
      task.total > 0
        ? (int) Math.min(1000, (task.transferred * 1000.0) / task.total)
        : 0
    );
    parent.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
    space(parent, 8);
    parent.addView(
      text(
        size(task.transferred) +
          (task.total > 0 ? " / " + size(task.total) : ""),
        12,
        MUTED
      )
    );
    if (!task.error.isEmpty()) parent.addView(text(task.error, 12, ALERT));
    LinearLayout buttons = new LinearLayout(activity);
    boolean active =
      task.state.equals("running") || task.state.equals("queued");
    buttons.addView(
      button(
        active ? "暂停" : "重试",
        () -> {
          if (active) TransferService.pause(activity, task.id);
          else TransferService.retry(activity, task.id);
        },
        false
      )
    );
    buttons.addView(
      button(
        "取消",
        () ->
          new AlertDialog.Builder(activity)
            .setTitle("取消这次传输？")
            .setNegativeButton("继续传输", null)
            .setPositiveButton("取消传输", (d, w) ->
              TransferService.cancel(activity, task.id)
            )
            .show(),
        false
      )
    );
    parent.addView(buttons);
    divider(parent);
  }

  private void rowLink(
    LinearLayout parent,
    String title,
    String subtitle,
    Runnable action
  ) {
    LinearLayout row = column();
    row.setPadding(0, dp(18), 0, dp(18));
    row.setMinimumHeight(dp(72));
    TextView heading = text(title, 16, INK);
    heading.setMaxLines(2);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(heading);
    space(row, 7);
    row.addView(text(subtitle + "   ›", 12, MUTED));
    row.setFocusable(true);
    row.setBackground(
      new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x129b5a43),
        null,
        flatBackground(PAPER, 0)
      )
    );
    row.setOnClickListener(v -> action.run());
    parent.addView(row);
    divider(parent);
  }

  static String display(JSONObject value) {
    String title = value.optString("title").trim();
    if (!title.isEmpty()) return title;
    String body = value.optString("text").trim();
    if (!body.isEmpty()) return body.split("\\n", 2)[0];
    JSONArray files = value.optJSONArray("files");
    if (files != null && files.length() > 0) return (
      files.optJSONObject(0).optString("name", "文件") +
      (files.length() > 1 ? " 等 " + files.length() + " 个文件" : "")
    );
    return "未命名草稿";
  }

  private static String date(String value) {
    try {
      return java.time.Instant.parse(value)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    } catch (Exception ignored) {
      return "";
    }
  }

  private static boolean isLink(String value) {
    String text = value.trim();
    if (text.matches(".*\\s+.*")) return false;
    Uri uri = Uri.parse(text);
    return (
      ("https".equalsIgnoreCase(uri.getScheme()) ||
        "http".equalsIgnoreCase(uri.getScheme())) &&
      uri.getHost() != null
    );
  }
}
