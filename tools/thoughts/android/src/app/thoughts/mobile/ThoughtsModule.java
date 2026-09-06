package app.thoughts.mobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Capture and browsing share the same draft/store controller, isolated from server features. */
final class ThoughtsModule extends Ui {

  private final SharedPreferences drafts;
  private LinearLayout surface, feed;
  private EditText content, tags, search;
  private String editingId = null,
    filter = "all";
  private int editingVersion = 0;
  private boolean restoring = false;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable persistDraft = () -> saveDraft();

  ThoughtsModule(Feature.Host host) {
    super(host);
    drafts = activity.getSharedPreferences("draft", 0);
  }

  Feature screen(String id, String label) {
    return new Feature() {
      public String id() {
        return id;
      }

      public String label() {
        return label;
      }

      public void render(LinearLayout parent) {
        surface = parent;
        if (id.equals("capture")) capture();
        else notes();
      }

      public String title() {
        return id.equals("capture")
          ? drafts.getString("id", null) == null
            ? "写一条"
            : "编辑想法"
          : "想法";
      }

      public String headerAction() {
        return id.equals("capture")
          ? host.automaticSync()
            ? "发布"
            : "保存"
          : "同步";
      }

      public String headerIcon() {
        return id.equals("notes") ? "sync" : "";
      }

      public void performHeaderAction() {
        if (id.equals("capture")) saveNote();
        else if (!account.isVerified()) host.navigate("server");
        else host.sync();
      }

      public void renderFooter(LinearLayout footer) {
        if (!id.equals("capture")) return;
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(0, 0, 0, dp(4));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
          text("同步后公开", 11, MUTED),
          new LinearLayout.LayoutParams(0, -2, 1)
        );
        Button mode = button(
          host.automaticSync() ? "自动同步 ▾" : "手动同步 ▾",
          () -> syncMode(),
          false
        );
        mode.setTextSize(11);
        mode.setTextColor(MUTED);
        row.addView(mode);
        footer.addView(row);
      }

      public void leave() {
        saveDraft();
        content = null;
        tags = null;
        feed = null;
        search = null;
      }

      public void refresh() {
        renderFeed();
      }
    };
  }

  void receiveShare(Intent intent) {
    if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
    String value = intent.getStringExtra(Intent.EXTRA_TEXT);
    if (value != null) {
      host.navigate("capture");
      String previous = content.getText().toString();
      content.setText(previous.isEmpty() ? value : previous + "\n\n" + value);
      saveDraft();
    }
    intent.setAction(null);
  }

  private void syncMode() {
    new AlertDialog.Builder(activity)
      .setTitle("保存方式")
      .setSingleChoiceItems(
        new String[] { "保存后立即同步", "仅保存本机，手动同步" },
        host.automaticSync() ? 0 : 1,
        (dialog, index) -> {
          dialog.dismiss();
          host.setAutomaticSync(index == 0);
        }
      )
      .setNegativeButton("取消", null)
      .show();
  }

  private void capture() {
    restoring = true;
    editingId = drafts.getString("id", null);
    editingVersion = drafts.getInt("version", 0);
    String date = java.time.LocalDate.now().format(
      java.time.format.DateTimeFormatter.ofPattern(
        "M 月 d 日，EEEE",
        java.util.Locale.CHINA
      )
    );
    surface.addView(
      text(
        date + (drafts.getString("content", "").isEmpty() ? "" : " · 草稿"),
        11,
        MUTED
      )
    );
    space(surface, 22);
    content = input("此刻，你在想什么？", true);
    content.setGravity(Gravity.TOP);
    content.setMinLines(8);
    content.setTextSize(18);
    content.setPadding(0, 0, 0, dp(8));
    content.setBackgroundColor(Color.TRANSPARENT);
    content.setMaxLines(14);
    content.setLineSpacing(dp(6), 1);
    content.setFilters(new android.text.InputFilter[] {
      new android.text.InputFilter.LengthFilter(20000),
    });
    content.setText(drafts.getString("content", ""));
    content.setContentDescription("想法内容");
    surface.addView(content, new LinearLayout.LayoutParams(-1, -2));
    space(surface, 16);
    tags = input("＋ 添加标签", false);
    tags.setContentDescription("标签，用逗号分隔");
    tags.setTextSize(13);
    tags.setBackgroundColor(Color.TRANSPARENT);
    tags.setPadding(0, dp(12), 0, dp(12));
    tags.setText(drafts.getString("tags", ""));
    surface.addView(tags);
    space(surface, 10);
    if (editingId != null) {
      space(surface, 10);
      surface.addView(
        button(
          "取消编辑",
          () ->
            new AlertDialog.Builder(activity)
              .setMessage("丢弃当前编辑草稿？")
              .setNegativeButton("保留", null)
              .setPositiveButton("丢弃", (d, w) -> {
                drafts.edit().clear().commit();
                content = null;
                host.navigate("capture");
              })
              .show(),
          false
        )
      );
    }
    space(surface, 16);
    content.addTextChangedListener(watcher(() -> scheduleDraft()));
    tags.addTextChangedListener(watcher(() -> scheduleDraft()));
    restoring = false;
  }

  private void scheduleDraft() {
    if (!restoring) {
      handler.removeCallbacks(persistDraft);
      handler.postDelayed(persistDraft, 250);
    }
  }

  void saveDraft() {
    handler.removeCallbacks(persistDraft);
    if (content != null && tags != null && !restoring) {
      boolean ok = drafts
        .edit()
        .putString("content", content.getText().toString())
        .putString("tags", tags.getText().toString())
        .putString("id", editingId)
        .putInt("version", editingVersion)
        .commit();
      if (!ok) status("草稿写入失败，请复制内容备份");
    }
  }

  private void saveNote() {
    try {
      String body = content.getText().toString().trim();
      if (body.isEmpty()) throw new Exception("先写下一点想法吧");
      JSONArray list = new JSONArray();
      java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
      for (String tag : tags.getText().toString().split("[,，]")) {
        String t = tag.trim();
        if (t.length() > 30) throw new Exception("每个标签最多 30 字");
        if (!t.isEmpty()) unique.add(t);
      }
      if (unique.size() > 12) throw new Exception("最多添加 12 个标签");
      for (String tag : unique) list.put(tag);
      store.save(editingId, body, list, editingVersion);
      handler.removeCallbacks(persistDraft);
      drafts.edit().clear().commit();
      content = null;
      tags = null;
      editingId = null;
      editingVersion = 0;
      host.redraw();
      status("已保存到手机 · 等待同步");
      host.autoSync();
    } catch (Exception e) {
      saveDraft();
      status(e.getMessage());
    }
  }

  private void notes() {
    search = input("搜索内容或标签", false);
    search.setContentDescription("搜索想法");
    search.setBackground(flatBackground(0xfff4f2ee, 8));
    search.setTextSize(13);
    search.setPadding(dp(12), dp(12), dp(12), dp(12));
    surface.addView(search);
    space(surface, 12);
    LinearLayout filters = new LinearLayout(activity);
    String[][] choices = {
      { "all", "全部" },
      { "pending", "待同步" },
      { "conflict", "冲突" },
      { "trash", "回收站" },
    };
    for (String[] item : choices) {
      Button b = button(
        item[1],
        () -> {
          filter = item[0];
          notesRebuild();
        },
        false
      );
      boolean selected = filter.equals(item[0]);
      b.setTextColor(selected ? BLUE : MUTED);
      b.setTextSize(13);
      b.setSingleLine(true);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(dp(6), dp(8), dp(6), dp(8));
      b.setBackground(flatBackground(Color.TRANSPARENT, 0));
      b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
      b.setSelected(selected);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
      p.setMargins(dp(2), 0, dp(2), 0);
      filters.addView(b, p);
    }
    surface.addView(filters);
    space(surface, 10);
    feed = column();
    surface.addView(feed);
    search.addTextChangedListener(watcher(() -> renderFeed()));
    renderFeed();
  }

  private void notesRebuild() {
    String query = search.getText().toString();
    surface.removeAllViews();
    notes();
    search.setText(query);
  }

  void renderFeed() {
    if (feed == null || !host.activeFeature().equals("notes")) return;
    feed.removeAllViews();
    try {
      String query = search
        .getText()
        .toString()
        .toLowerCase(java.util.Locale.ROOT);
      int count = 0;
      for (Store.Entry entry : store.entries()) {
        JSONObject note = entry.note;
        boolean deleted = !note.isNull("deleted_at");
        if (filter.equals("trash") ? !deleted : deleted) continue;
        if (filter.equals("pending") && entry.pending == null) continue;
        if (filter.equals("conflict") && entry.error == null) continue;
        if (
          !(note.optString("content") + note.optString("tags"))
            .toLowerCase(java.util.Locale.ROOT)
            .contains(query)
        ) continue;
        count++;
        LinearLayout card = column();
        card.setPadding(0, dp(20), 0, dp(18));
        String state =
          entry.error != null
            ? "需要处理冲突"
            : entry.pending != null
              ? "待同步"
              : deleted
                ? "回收站"
                : "已提交服务器";
        card.addView(
          text(
            formatDate(note.optString("created_at")) +
              (entry.error != null || entry.pending != null
                ? "  ·  " + state
                : ""),
            12,
            entry.error != null ? Color.rgb(160, 54, 44) : MUTED
          )
        );
        space(card, 12);
        TextView body = text(note.optString("content"), 16, INK);
        body.setMaxLines(7);
        body.setLineSpacing(dp(6), 1);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(body);
        JSONArray tags = note.optJSONArray("tags");
        if (tags != null && tags.length() > 0) {
          space(card, 10);
          StringBuilder line = new StringBuilder();
          for (int i = 0; i < tags.length(); i++) line
            .append("#")
            .append(tags.optString(i))
            .append("  ");
          card.addView(text(line.toString(), 12, BLUE));
        }
        space(card, 12);
        card.setOnClickListener(v -> openNote(entry));
        card.setOnLongClickListener(v -> {
          manage(entry);
          return true;
        });
        card.setContentDescription(
          note.optString("content") + "，点击查看，长按管理"
        );
        card.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        feed.addView(card, p);
        divider(feed);
      }
      if (count == 0) {
        space(feed, 28);
        feed.addView(
          text(
            !query.isEmpty()
              ? "没有找到相关想法，试试其他关键词。"
              : filter.equals("pending")
                ? "没有待同步记录。"
                : filter.equals("conflict")
                  ? "没有需要处理的冲突。"
                  : filter.equals("trash")
                    ? "回收站是空的。"
                    : "这里还没有想法，先记下今天的第一个念头。",
            14,
            MUTED
          )
        );
      }
    } catch (Exception e) {
      status("读取本机记录失败，请先导出备份");
    }
  }

  private String formatDate(String value) {
    try {
      return java.time.OffsetDateTime.parse(value)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(
          java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
        );
    } catch (Exception e) {
      return value;
    }
  }

  private void openNote(Store.Entry entry) {
    ScrollView scroll = new ScrollView(activity);
    TextView body = text(entry.note.optString("content"), 16, INK);
    body.setTextIsSelectable(true);
    body.setPadding(dp(24), dp(16), dp(24), dp(16));
    scroll.addView(body);
    new AlertDialog.Builder(activity)
      .setTitle(entry.error != null ? "本地内容已保留" : "想法")
      .setView(scroll)
      .setNegativeButton("关闭", null)
      .setNeutralButton("复制", (d, w) -> copy(entry.note.optString("content")))
      .setPositiveButton("管理", (d, w) -> manage(entry))
      .show();
  }

  private void manage(Store.Entry entry) {
    if (entry.error != null) {
      new AlertDialog.Builder(activity)
        .setTitle("同步冲突")
        .setMessage(
          entry.error +
            "\n\n保留为新想法，下次同步时发布并重新拉取服务器上的原记录。"
        )
        .setNegativeButton("稍后处理", null)
        .setPositiveButton("保留为新想法", (d, w) -> {
          try {
            store.keepConflictAsCopy(entry);
            renderFeed();
            host.autoSync();
          } catch (Exception e) {
            status(e.getMessage());
          }
        })
        .show();
      return;
    }
    boolean deleted = !entry.note.isNull("deleted_at");
    String[] actions = deleted
      ? new String[] { "恢复", "编辑历史" }
      : new String[] { "编辑", "移到回收站", "编辑历史" };
    new AlertDialog.Builder(activity)
      .setTitle("管理想法")
      .setItems(actions, (d, index) -> {
        if ((deleted && index == 1) || (!deleted && index == 2)) {
          history(entry);
          return;
        }
        if (!deleted && index == 0) {
          if (
            entry.pending != null &&
            !entry.pending.equals("create") &&
            !entry.pending.equals("update")
          ) {
            status("请先完成同步");
            return;
          }
          Runnable edit = () -> {
            drafts
              .edit()
              .putString("id", entry.note.optString("id"))
              .putInt("version", entry.note.optInt("version"))
              .putString("content", entry.note.optString("content"))
              .putString("tags", joinTags(entry.note.optJSONArray("tags")))
              .commit();
            host.navigate("capture");
          };
          if (
            !drafts.getString("content", "").trim().isEmpty()
          ) new AlertDialog.Builder(activity)
            .setMessage("当前有未保存的草稿，要用这条想法替换吗？")
            .setNegativeButton("保留草稿", null)
            .setPositiveButton("替换", (a, b) -> edit.run())
            .show();
          else edit.run();
          return;
        }
        new AlertDialog.Builder(activity)
          .setMessage(
            deleted
              ? "恢复后将在下次同步时重新发布到博客。"
              : "移到回收站？之后仍可恢复。"
          )
          .setNegativeButton("取消", null)
          .setPositiveButton("确定", (a, b) -> {
            try {
              store.removeOrRestore(entry, deleted);
              renderFeed();
              host.autoSync();
            } catch (Exception e) {
              status(e.getMessage());
            }
          })
          .show();
      })
      .show();
  }

  private String joinTags(JSONArray tags) {
    StringBuilder result = new StringBuilder();
    if (tags != null) for (int i = 0; i < tags.length(); i++) {
      if (i > 0) result.append(", ");
      result.append(tags.optString(i));
    }
    return result.toString();
  }

  private void history(Store.Entry entry) {
    status("正在读取编辑历史…");
    IO.execute(() -> {
      try {
        JSONArray items = host
          .api()
          .request(
            "/thoughts/" + entry.note.getString("id") + "/history",
            "GET",
            null
          )
          .getJSONArray("items");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
          JSONObject row = items.getJSONObject(i),
            note = row.getJSONObject("item");
          body
            .append("版本 ")
            .append(note.getInt("version"))
            .append(" · ")
            .append(formatDate(row.getString("saved_at")))
            .append("\n\n")
            .append(note.getString("content"))
            .append("\n\n────────\n\n");
        }
        runOnUiThread(() -> {
          TextView text = text(
            items.length() == 0 ? "还没有编辑历史。" : body.toString(),
            15,
            INK
          );
          text.setPadding(dp(20), dp(12), dp(20), dp(12));
          text.setTextIsSelectable(true);
          ScrollView scroll = new ScrollView(activity);
          scroll.addView(text);
          new AlertDialog.Builder(activity)
            .setTitle("编辑历史")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show();
          status("历史已加载");
        });
      } catch (Exception e) {
        runOnUiThread(() -> status(errorMessage(e)));
      }
    });
  }
}
