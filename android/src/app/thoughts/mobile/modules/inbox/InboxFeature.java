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
  private LinearLayout content, tasks, entries, drafts, selectionBar;
  private TextView selectionCount;
  private int thumbnailCount;
  private InboxThumbnails remoteThumbnails;
  private final LinkedHashMap<String, JSONObject> visibleItems =
    new LinkedHashMap<>();
  private final Map<String, InboxStore.Task> downloadIndex = new HashMap<>();
  private ServerProfile downloadProfile;
  private final LinkedHashMap<String, JSONObject> selected =
    new LinkedHashMap<>();
  private String source = "all",
    sort = "newest";
  private long since;
  private final android.util.LruCache<
    String,
    android.graphics.Bitmap
  > thumbnails = new android.util.LruCache<String, android.graphics.Bitmap>(
    8 * 1024 * 1024
  ) {
    protected int sizeOf(String key, android.graphics.Bitmap value) {
      return value.getAllocationByteCount();
    }
  };
  private TextView summary;
  private String query = "",
    filter = "all",
    detailId = "";
  private boolean listening, loading, enableNeeded, updatePending, selecting;
  private boolean listsInvalidated;
  private final Runnable update = () -> {
    updatePending = false;
    if (content != null) {
      updateLists();
      if (listsInvalidated) {
        listsInvalidated = false;
        loadPage(false);
      }
    }
  };
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
      if (InboxStore.invalidates(intent, host.serverProfile())) {
        listsInvalidated = true;
        requestGeneration++;
        loading = loadingMore = false;
      }
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

  public String headerIcon() {
    return detailId.isEmpty() ? "add" : "more";
  }

  public boolean hasBack() {
    return !detailId.isEmpty() || selecting;
  }

  public void back() {
    if (selecting) {
      selecting = false;
      selected.clear();
      updateEntries();
      updateSelection();
      return;
    }
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
      detailId = "";
      selected.clear();
      selecting = false;
      renderedProfile = host.serverProfile();
    }
    content = surface;
    if (remoteThumbnails == null) remoteThumbnails = new InboxThumbnails(
      activity
    );
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
    top.addView(iconButton("sync", "刷新", this::refresh));
    top.addView(
      iconButton("more", "收件箱操作", () ->
        new AlertDialog.Builder(activity)
          .setItems(new String[] { "选择条目" }, (d, w) -> {
            if (visibleItems.isEmpty()) {
              status("没有可选择的条目");
              return;
            }
            selecting = true;
            updateEntries();
            updateSelection();
          })
          .show()
      )
    );
    surface.addView(top);
    if (profile == null) surface.addView(
      button("配置服务器", () -> host.navigate("server"), true)
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
          boolean leavingLocal = localOnly();
          filter = next;
          for (int j = 0; j < filters.getChildCount(); j++) {
            View child = filters.getChildAt(j);
            boolean chosen = next.equals(child.getTag());
            child.setSelected(chosen);
            ((Button) child).setTextColor(chosen ? BLUE : MUTED);
          }
          scheduleSearch();
          if (leavingLocal) host.redraw();
        },
        false
      );
      b.setTag(next);
      b.setSelected(filter.equals(next));
      b.setTextColor(filter.equals(next) ? BLUE : MUTED);
      filters.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    Button moreFilters = button(
      localOnly() ? "已下载" : "筛选",
      this::filterMenu,
      false
    );
    moreFilters.setContentDescription("筛选");
    moreFilters.setTextColor(
      (!filter.equals("all") &&
        !filter.equals("files") &&
        !filter.equals("text")) ||
        !source.equals("all") ||
        since > 0 ||
        !sort.equals("newest")
        ? BLUE
        : MUTED
    );
    filters.addView(moreFilters, new LinearLayout.LayoutParams(0, dp(48), 1));
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
    loadPage(false);
  }

  public void resume() {
    if (remoteThumbnails == null) remoteThumbnails = new InboxThumbnails(
      activity
    );
    listen();
    if (content != null) updateLists();
    loadPage(false);
  }

  public void pause() {
    if (remoteThumbnails != null) {
      remoteThumbnails.cancel();
      remoteThumbnails = null;
    }
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
      // Keep an already-paid request alive; its response can fill the private cache off-page.
      searchPending = false;
    }
    updatePending = false;
    if (listening) {
      activity.unregisterReceiver(receiver);
      listening = false;
    }
  }

  private void scheduleSearch() {
    selected.clear();
    selecting = false;
    updateSelection();
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
    loadPage(false, true);
  }

  private boolean localOnly() {
    return filter.equals("downloaded");
  }

  private String filterKey() {
    return filter + "|" + source + "|" + sort + "|" + since;
  }

  private boolean defaultQuery() {
    return query.trim().isEmpty() && filterKey().equals("all|all|newest|0");
  }

  private static String typeLabel(String type) {
    String[] keys = {
        "all",
        "files",
        "text",
        "image",
        "video",
        "audio",
        "document",
        "archive",
        "apk",
        "other",
        "downloaded",
      },
      labels = {
        "全部",
        "文件",
        "文字",
        "图片",
        "视频",
        "音频",
        "文档",
        "压缩包",
        "安装包",
        "其他",
        "已下载",
      };
    for (int i = 0; i < keys.length; i++) if (
      keys[i].equals(type)
    ) return labels[i];
    return "全部";
  }

  private void filterMenu() {
    new AlertDialog.Builder(activity)
      .setTitle("筛选与排序")
      .setItems(
        new String[] {
          "文件类型",
          "来源",
          "时间",
          "排序",
          "已下载",
          "重置筛选",
          "其他服务器的下载",
        },
        (d, w) -> {
          if (w == 6) {
            otherDownloads();
            return;
          }
          String[][] ids = {
            {
              "all",
              "files",
              "text",
              "image",
              "video",
              "audio",
              "document",
              "archive",
              "apk",
              "other",
            },
            { "all", "phone", "computer", "server", "other" },
            { "0", "7", "30" },
            { "newest", "oldest", "name", "size" },
          };
          String[][] labels = {
            {
              "全部",
              "所有文件",
              "文字",
              "图片",
              "视频",
              "音频",
              "文档",
              "压缩包",
              "安装包",
              "其他",
            },
            { "全部来源", "手机", "电脑", "服务器", "其他" },
            { "不限时间", "最近 7 天", "最近 30 天" },
            { "最新优先", "最早优先", "名称", "文件大小" },
          };
          if (w == 4 || w == 5) {
            filter = w == 4 ? "downloaded" : "all";
            source = "all";
            sort = "newest";
            since = 0;
            scheduleSearch();
            host.redraw();
            return;
          }
          new AlertDialog.Builder(activity)
            .setItems(labels[w], (dialog, index) -> {
              String value = ids[w][index];
              if (w == 0) filter = value;
              else if (w == 1) source = value;
              else if (w == 2) since =
                index == 0
                  ? 0
                  : System.currentTimeMillis() / 1000 -
                    Long.parseLong(value) * 86400;
              else sort = value;
              scheduleSearch();
              host.redraw();
            })
            .show();
        }
      )
      .show();
  }

  public void renderFooter(LinearLayout footer) {
    selectionBar = column();
    selectionCount = text("", 12, MUTED);
    selectionBar.addView(selectionCount);
    LinearLayout actions = new LinearLayout(activity);
    actions.addView(
      button(
        "取消",
        () -> {
          selected.clear();
          selecting = false;
          updateEntries();
          updateSelection();
        },
        false
      ),
      new LinearLayout.LayoutParams(0, -2, 1)
    );
    actions.addView(
      button(
        "全选",
        () -> {
          selected.putAll(visibleItems);
          updateEntries();
          updateSelection();
        },
        false
      ),
      new LinearLayout.LayoutParams(0, -2, 1)
    );
    if (!localOnly()) actions.addView(
      button("下载", () -> batch(false), false),
      new LinearLayout.LayoutParams(0, -2, 1)
    );
    actions.addView(
      button(localOnly() ? "移除副本" : "删除", () -> batch(true), false),
      new LinearLayout.LayoutParams(0, -2, 1)
    );
    selectionBar.addView(actions);
    footer.addView(selectionBar);
    updateSelection();
  }

  private void updateSelection() {
    if (selectionBar != null) {
      selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
      selectionCount.setText("已选 " + selected.size() + " 项");
    }
  }

  private void toggle(JSONObject item) {
    selecting = true;
    String id = item.optString("id");
    if (selected.containsKey(id)) selected.remove(id);
    else selected.put(id, item);
    updateEntries();
    updateSelection();
  }

  private void batch(boolean deleting) {
    List<JSONObject> items = new ArrayList<>(selected.values());
    if (items.isEmpty()) return;
    if (localOnly()) {
      new AlertDialog.Builder(activity)
        .setTitle("移除所选手机副本？")
        .setMessage("仅移除手机副本，不修改服务器。")
        .setNegativeButton("取消", null)
        .setPositiveButton("移除", (d, w) -> {
          try {
            for (InboxStore.Task task : localTasks())
              if (
                available(task) &&
                selected.containsKey(
                  task.document.optJSONObject("item").optString("id")
                )
              ) store.removeTask(task.id);
            selected.clear();
            selecting = false;
            updateSelection();
            updateLists();
          } catch (Exception e) {
            status("部分副本未移除，请重试");
            updateLists();
          }
        })
        .show();
      return;
    }
    Runnable run = () -> {
      ServerProfile profile = host.serverProfile();
      WORK.execute(() -> {
        int failed = 0,
          files = 0;
        for (JSONObject item : items)
          try {
            if (deleting) {
              try (InboxTransfer transfer = new InboxTransfer(profile)) {
                transfer.request(
                  "/inbox/items/" + item.optString("id"),
                  "DELETE",
                  null
                );
              }
            } else {
              JSONArray attached = item.optJSONArray("files");
              if (attached != null) for (
                int i = 0;
                i < attached.length();
                i++
              ) {
                store.enqueueDownload(
                  profile,
                  item,
                  item.getJSONArray("files").getJSONObject(i)
                );
                files++;
              }
            }
          } catch (Exception e) {
            failed++;
          }
        final int errors = failed,
          total = files;
        runOnUiThread(() -> {
          selected.clear();
          selecting = false;
          updateSelection();
          if (deleting) {
            try {
              store.invalidateLists(profile);
            } catch (Exception ignored) {}
          } else if (total > 0) {
            if (!requestNotifications(activity)) TransferService.start(
              activity
            );
            updateLists();
          }
          status(
            errors > 0
              ? errors + " 项操作失败，请重试"
              : deleting
                ? "已删除"
                : total > 0
                  ? "已加入下载"
                  : "所选条目没有附件"
          );
        });
      });
    };
    if (deleting) new AlertDialog.Builder(activity)
      .setTitle("删除所选 " + items.size() + " 项？")
      .setNegativeButton("取消", null)
      .setPositiveButton("删除", (d, w) -> run.run())
      .show();
    else run.run();
  }

  private boolean resultMatches() {
    return (
      searchResult != null &&
      loadedQuery.equals(query.trim()) &&
      loadedFilter.equals(filterKey()) &&
      InboxStore.sameIdentity(resultProfile, host.serverProfile())
    );
  }

  private JSONObject visibleResponse() {
    if (!defaultQuery()) {
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
    loadPage(more, false);
  }

  private void loadPage(boolean more, boolean force) {
    final ServerProfile profile = host.serverProfile();
    if (localOnly()) {
      searchPending = loading = loadingMore = false;
      remoteFailed = false;
      if (summary != null) summary.setText(
        profile == null ? "尚未连接服务器" : profile.name
      );
      updateLists();
      return;
    }
    if (loading || profile == null || content == null) {
      searchPending = false;
      return;
    }
    final String requestedQuery = query.trim(),
      requestedFilter = filterKey();
    final String cacheKey = requestedQuery + "\n" + requestedFilter;
    if (!more && !force) try {
      JSONObject cached = store.freshQuery(profile, cacheKey);
      if (cached != null) {
        searchResult = cached;
        loadedQuery = requestedQuery;
        loadedFilter = requestedFilter;
        resultProfile = profile;
        searchPending = remoteFailed = false;
        if (summary != null) summary.setText(profile.name);
        updateLists();
        return;
      }
    } catch (Exception ignored) {}
    final long revision = InboxStore.revision();
    final String requestedType = filter,
      options = "&source=" + source + "&sort=" + sort + "&since=" + since;
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
            requestedType +
            options +
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
            if (!store.cacheQuery(profile, cacheKey, response, revision)) {
              loading = loadingMore = false;
              loadPage(false);
              return;
            }
            searchResult = response;
            loadedQuery = requestedQuery;
            loadedFilter = requestedFilter;
            resultProfile = profile;
            if (
              requestedQuery.isEmpty() &&
              requestedFilter.equals("all|all|newest|0")
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
      query.trim().equals(requestedQuery) &&
      filterKey().equals(requestedFilter) &&
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
    if (!enableNeeded && listening && content != null) status(
      errorMessage(error)
    );
  }

  private void enable() {
    CapabilityEnrollment.show(host, "inbox.write", this::refresh);
  }

  private void updateLists() {
    if (content == null) return;
    if (!detailId.isEmpty()) {
      content.removeAllViews();
      renderDetail(content);
      return;
    }
    if (tasks == null) return;
    drafts.removeAllViews();
    JSONArray pending = localOnly() ? new JSONArray() : localDrafts();
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
    for (InboxStore.Task task : localOnly()
      ? Collections.<InboxStore.Task>emptyList()
      : localTasks()) {
      if (task.state.equals("done") || task.state.equals("canceled")) continue;
      taskRow(tasks, task);
    }
    updateEntries();
  }

  private void updateEntries() {
    if (entries == null) return;
    indexDownloads();
    entries.removeAllViews();
    visibleItems.clear();
    thumbnailCount = 0;
    if (
      !localOnly() &&
      (enableNeeded ||
        (host.serverProfile() != null && !host.account().can("inbox.write")))
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
      if (
        !filter.equals("all") &&
        !filter.equals("files") &&
        !filter.equals("text") &&
        !localOnly()
      ) {
        boolean matches = false;
        if (files != null) for (int f = 0; f < files.length(); f++) {
          JSONObject file = files.optJSONObject(f);
          String kind = file.optString("kind");
          if (kind.isEmpty()) {
            String mime = InboxMedia.mime(file);
            kind = mime.startsWith("image/")
              ? "image"
              : mime.startsWith("video/")
                ? "video"
                : mime.startsWith("audio/")
                  ? "audio"
                  : mime.equals("application/pdf") || mime.startsWith("text/")
                    ? "document"
                    : file.optString("name").endsWith(".apk")
                      ? "apk"
                      : "other";
          }
          if (filter.equals(kind)) matches = true;
        }
        if (!matches) continue;
      }
      if (!source.equals("all")) {
        String kind = item.optString("source_kind");
        if (kind.isEmpty()) {
          String raw = item.optString("source").toLowerCase(Locale.ROOT);
          kind =
            raw.equals("android") || raw.equals("app") || raw.equals("mobile")
              ? "phone"
              : raw.equals("browser") ||
                  raw.equals("desktop") ||
                  raw.equals("pc")
                ? "computer"
                : raw.equals("server") || raw.equals("cli")
                  ? "server"
                  : "other";
        }
        if (!source.equals(kind)) continue;
      }
      if (since > 0) try {
        if (
          java.time.Instant.parse(
            item.optString("created_at")
          ).getEpochSecond() < since
        ) continue;
      } catch (Exception ignored) {}
      StringBuilder haystack = new StringBuilder(display(item))
        .append(' ')
        .append(item.optString("text"))
        .append(' ')
        .append(item.optString("note"));
      if (files != null) for (int f = 0; f < files.length(); f++) {
        JSONObject attachment = files.optJSONObject(f);
        if (localOnly() && !available(download(item, attachment))) continue;
        haystack.append(' ').append(attachment.optString("name"));
      }
      if (
        !haystack.toString().toLowerCase(Locale.ROOT).contains(needle)
      ) continue;
      String detail =
        count > 0
          ? count + " 个文件"
          : isLink(item.optString("text"))
            ? "链接"
            : "文字";
      if (count > 0) {
        long bytes = item.optLong("total_size", -1);
        if (bytes < 0) {
          bytes = 0;
          for (int f = 0; f < files.length(); f++) bytes += files
            .optJSONObject(f)
            .optLong("size");
        }
        detail += " · " + size(bytes);
      }
      String created = date(item.optString("created_at"));
      if (!created.isEmpty()) detail += " · " + created;
      visibleItems.put(item.optString("id"), item);
      LinearLayout row = rowLink(entries, display(item), detail, () -> {
        if (selecting) {
          toggle(item);
          return;
        }
        detailId = item.optString("id");
        detailSnapshot = item;
        host.redraw();
      });
      TextView heading = (TextView) row.getChildAt(0);
      if (selecting) {
        boolean chosen = selected.containsKey(item.optString("id"));
        android.graphics.drawable.Drawable box = icon(
          chosen ? "check_box" : "check_box_outline",
          INK
        );
        box.setBounds(0, 0, dp(22), dp(22));
        heading.setCompoundDrawables(box, null, null, null);
        heading.setCompoundDrawablePadding(dp(8));
        heading.setContentDescription(
          (chosen ? "已选中，" : "未选中，") + display(item)
        );
      }
      row.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
            View hostView,
            android.view.accessibility.AccessibilityNodeInfo info
          ) {
            super.onInitializeAccessibilityNodeInfo(hostView, info);
            if (selecting) {
              info.setCheckable(true);
              info.setChecked(selected.containsKey(item.optString("id")));
              info.setClassName("android.widget.CheckBox");
            }
          }
        }
      );
      row.setTooltipText("长按选择条目");
      appendDownloadStatus(row, completedCount(item), count);
      row.setOnLongClickListener(v -> {
        toggle(item);
        return true;
      });
      if (files != null && files.length() > 0) {
        JSONObject cover = files.optJSONObject(0);
        if (localOnly()) for (int f = 0; f < files.length(); f++) {
          if (available(download(item, files.optJSONObject(f)))) {
            cover = files.optJSONObject(f);
            break;
          }
        }
        thumbnail(row, item, cover);
      }
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
    }
    JSONObject response = visibleResponse();
    boolean validPage = defaultQuery() || resultMatches();
    if (!localOnly() && validPage && response.optBoolean("has_more")) {
      Button more = button(
        loadingMore ? "正在加载…" : "加载更多",
        () -> loadPage(true),
        false
      );
      more.setEnabled(!loading && !searchPending);
      entries.addView(more, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    if (!localOnly() && remoteFailed && !enableNeeded) entries.addView(
      button("重试", this::refresh, false)
    );
  }

  private void renderDetail(LinearLayout surface) {
    indexDownloads();
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
      surface.addView(
        text(
          localOnly()
            ? "手机副本 · " + completedCount(item) + "/" + files.length()
            : "文件 · " + files.length(),
          12,
          MUTED
        )
      );
      for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.optJSONObject(i);
        if (file == null) continue;
        InboxStore.Task local = download(item, file);
        boolean ready = available(local);
        if (localOnly() && !ready) continue;
        LinearLayout row = rowLink(
          surface,
          file.optString("name", "文件"),
          size(file.optLong("size")),
          () -> fileMenu(item, file)
        );
        appendDownloadStatus(row, ready ? 1 : 0, 1);
        if (ready) {
          LinearLayout metadata = (LinearLayout) row.getChildAt(
            row.getChildCount() - 1
          );
          metadata.addView(
            iconButton("more", "文件操作：" + file.optString("name"), () ->
              localFileMenu(local)
            )
          );
          row.setOnLongClickListener(v -> {
            localFileMenu(local);
            return true;
          });
        }
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
      if (localOnly()) {
        LinkedHashMap<String, JSONObject> downloaded = new LinkedHashMap<>();
        for (InboxStore.Task task : localTasks())
          if (available(task)) {
            JSONObject item = task.document.getJSONObject("item");
            downloaded.putIfAbsent(item.getString("id"), item);
          }
        List<JSONObject> sorted = new ArrayList<>(downloaded.values());
        sorted.sort((a, b) ->
          sort.equals("name")
            ? display(a).compareToIgnoreCase(display(b))
            : sort.equals("size")
              ? Long.compare(b.optLong("total_size"), a.optLong("total_size"))
              : sort.equals("oldest")
                ? a.optString("created_at").compareTo(b.optString("created_at"))
                : b.optString("created_at").compareTo(a.optString("created_at"))
        );
        return new JSONArray(sorted);
      }
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

  private void indexDownloads() {
    downloadProfile = host.serverProfile();
    downloadIndex.clear();
    for (InboxStore.Task task : localTasks())
      if (task.kind.equals("download") && !task.state.equals("canceled")) {
        JSONObject item = task.document.optJSONObject("item"),
          file = task.document.optJSONObject("file");
        if (item != null && file != null) downloadIndex.putIfAbsent(
          item.optString("id") + "\n" + file.optString("id"),
          task
        );
      }
  }

  private InboxStore.Task download(JSONObject item, JSONObject file) {
    if (
      !InboxStore.sameIdentity(downloadProfile, host.serverProfile())
    ) indexDownloads();
    return downloadIndex.get(
      item.optString("id") + "\n" + file.optString("id")
    );
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

  private void thumbnail(LinearLayout row, JSONObject item, JSONObject file) {
    if (
      file == null ||
      !InboxMedia.mime(file).startsWith("image/") ||
      thumbnailCount >= 12
    ) return;
    thumbnailCount++;
    ImageView image = new ImageView(activity);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(flatBackground(LINE, 6));
    image.setImageResource(android.R.drawable.ic_menu_gallery);
    image.setContentDescription("预览 " + file.optString("name"));
    row.addView(image, 0, new LinearLayout.LayoutParams(-1, dp(132)));
    image.setOnClickListener(v -> {
      if (selecting) toggle(item);
      else fileMenu(item, file);
    });
    image.setOnLongClickListener(v -> {
      toggle(item);
      return true;
    });
    InboxStore.Task task = download(item, file);
    if (available(task)) {
      android.graphics.Bitmap cached = thumbnails.get(task.id);
      if (cached != null) {
        image.setImageBitmap(cached);
        return;
      }
      SEARCH.execute(() -> {
        try {
          android.graphics.Bitmap bitmap = InboxMedia.bitmap(
            store.localFile(task),
            320
          );
          runOnUiThread(() -> {
            thumbnails.put(task.id, bitmap);
            if (image.isAttachedToWindow()) image.setImageBitmap(bitmap);
          });
        } catch (Exception ignored) {}
      });
      return;
    }
    if (
      localOnly() ||
      !InboxThumbnails.eligible(file) ||
      !host.account().can("inbox.read")
    ) return;
    final boolean[] started = { false };
    final android.graphics.Rect visible = new android.graphics.Rect();
    Runnable load = () -> {
      if (
        started[0] ||
        remoteThumbnails == null ||
        !image.isShown() ||
        !image.getGlobalVisibleRect(visible)
      ) return;
      started[0] = remoteThumbnails.request(
        host.serverProfile(),
        item,
        file,
        bitmap -> {
          if (
            bitmap != null && image.isAttachedToWindow()
          ) image.setImageBitmap(bitmap);
        }
      );
    };
    image.addOnAttachStateChangeListener(
      new View.OnAttachStateChangeListener() {
        ViewTreeObserver observer;
        final ViewTreeObserver.OnScrollChangedListener scroll = () ->
          load.run();
        final ViewTreeObserver.OnPreDrawListener draw = () -> {
          load.run();
          return true;
        };

        public void onViewAttachedToWindow(View v) {
          observer = v.getViewTreeObserver();
          observer.addOnScrollChangedListener(scroll);
          observer.addOnPreDrawListener(draw);
        }

        public void onViewDetachedFromWindow(View v) {
          if (observer != null && observer.isAlive()) {
            observer.removeOnScrollChangedListener(scroll);
            observer.removeOnPreDrawListener(draw);
          }
        }
      }
    );
  }

  private boolean available(InboxStore.Task task) {
    if (
      task == null ||
      !task.kind.equals("download") ||
      !task.state.equals("done")
    ) return false;
    try {
      return store.localFile(task).isFile();
    } catch (Exception ignored) {
      return false;
    }
  }

  private int completedCount(JSONObject item) {
    JSONArray files = item.optJSONArray("files");
    int count = 0;
    if (files != null) for (int i = 0; i < files.length(); i++) if (
      available(download(item, files.optJSONObject(i)))
    ) count++;
    return count;
  }

  private void appendDownloadStatus(
    LinearLayout row,
    int completed,
    int total
  ) {
    if (total == 0 || completed == 0) return;
    int index = row.getChildCount() - 1;
    View subtitle = row.getChildAt(index);
    row.removeViewAt(index);
    LinearLayout line = new LinearLayout(activity);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
    String label =
      completed == total
        ? "已下载到手机"
        : "已下载 " + completed + "/" + total + " 个文件";
    line.addView(
      statusIcon(completed == total ? "download_done" : "partial", label)
    );
    if (completed > 0 && completed < total) {
      TextView fraction = text(completed + "/" + total, 12, MUTED);
      fraction.setPadding(dp(4), 0, 0, 0);
      line.addView(fraction);
    }
    row.addView(line);
  }

  private void removeLocal(InboxStore.Task task) {
    new AlertDialog.Builder(activity)
      .setTitle("移除手机副本？")
      .setMessage("仅移除手机副本，不修改服务器。")
      .setNegativeButton("取消", null)
      .setPositiveButton("移除", (d, w) -> {
        try {
          store.removeTask(task.id);
          if (localOnly()) {
            detailId = "";
            detailSnapshot = null;
            host.redraw();
          } else updateLists();
          status("已移除手机副本");
        } catch (Exception e) {
          status("移除失败，请重试");
        }
      })
      .show();
  }

  private void otherDownloads() {
    try {
      List<InboxStore.Task> copies = new ArrayList<>();
      for (InboxStore.Task task : store.tasks())
        if (
          !InboxStore.sameIdentity(task.profile, host.serverProfile()) &&
          available(task)
        ) copies.add(task);
      if (copies.isEmpty()) {
        status("没有其他服务器的手机副本");
        return;
      }
      String[] names = new String[copies.size()];
      for (int i = 0; i < names.length; i++) {
        InboxStore.Task task = copies.get(i);
        names[i] =
          task.document.optJSONObject("file").optString("name") +
          "\n" +
          task.profile.name +
          " · " +
          task.profile.host;
      }
      new AlertDialog.Builder(activity)
        .setTitle("其他服务器的下载")
        .setItems(names, (d, w) -> localFileMenu(copies.get(w)))
        .setNegativeButton("关闭", null)
        .show();
    } catch (Exception e) {
      status("无法读取手机副本");
    }
  }

  private void localFileMenu(InboxStore.Task local) {
    JSONObject file = local.document.optJSONObject("file");
    new AlertDialog.Builder(activity)
      .setTitle(file.optString("name"))
      .setItems(
        new String[] {
          file.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")
            ? "安装应用"
            : "打开",
          "分享",
          "另存为",
          "移除手机副本",
        },
        (d, which) -> {
          try {
            if (which == 0) {
              if (InboxMedia.preview(file)) activity.startActivity(
                new Intent(activity, InboxPreviewActivity.class).putExtra(
                  "task_id",
                  local.id
                )
              );
              else InboxFiles.open(activity, local);
            } else if (which == 1) InboxFiles.share(activity, local);
            else if (which == 3) removeLocal(local);
            else {
              activity
                .getSharedPreferences("inbox_ui", 0)
                .edit()
                .putString("save_task", local.id)
                .commit();
              activity.startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType(InboxMedia.mime(file))
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

  private void fileMenu(JSONObject item, JSONObject file) {
    indexDownloads();
    InboxStore.Task local = download(item, file);
    if (available(local)) {
      if (InboxMedia.preview(file)) activity.startActivity(
        new Intent(activity, InboxPreviewActivity.class).putExtra(
          "task_id",
          local.id
        )
      );
      else localFileMenu(local);
      return;
    }
    if (localOnly()) {
      status("手机副本已移除");
      updateLists();
      return;
    }
    if (InboxMedia.preview(file)) {
      try {
        InboxStore.Task preview = !available(local)
          ? store.enqueueDownload(host.serverProfile(), item, file)
          : local;
        activity.startActivity(
          new Intent(activity, InboxPreviewActivity.class).putExtra(
            "task_id",
            preview.id
          )
        );
      } catch (Exception e) {
        status(errorMessage(e));
      }
      return;
    }
    if (!available(local)) {
      if (
        local != null &&
        !local.state.equals("canceled") &&
        !local.state.equals("done")
      ) {
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
    if (localOnly()) {
      List<InboxStore.Task> copies = new ArrayList<>();
      for (InboxStore.Task task : localTasks())
        if (
          available(task) &&
          task.document
            .optJSONObject("item")
            .optString("id")
            .equals(item.optString("id"))
        ) copies.add(task);
      if (copies.size() == 1) localFileMenu(copies.get(0));
      else {
        String[] names = new String[copies.size()];
        for (int i = 0; i < names.length; i++) names[i] = copies
          .get(i)
          .document.optJSONObject("file")
          .optString("name");
        new AlertDialog.Builder(activity)
          .setTitle("手机副本")
          .setItems(names, (d, w) -> localFileMenu(copies.get(w)))
          .show();
      }
      return;
    }
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
        store.invalidateLists(profile);
        runOnUiThread(() -> {
          if (method.equals("DELETE")) {
            detailId = "";
            detailSnapshot = null;
          }
          searchResult = null;
          host.redraw();
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

  private LinearLayout rowLink(
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
    return row;
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
