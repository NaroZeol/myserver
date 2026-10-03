package app.thoughts.mobile.modules.server;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import app.thoughts.mobile.core.Feature;
import app.thoughts.mobile.core.Ui;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** A small snapshot view; no timers, background polling or dependency on the thoughts store. */
final class SystemOverview extends Ui {

  SystemOverview(Feature.Host host) {
    super(host);
  }

  void render(
    LinearLayout surface,
    JSONObject metrics,
    long checkedAt,
    boolean connected,
    boolean loading
  ) {
    LinearLayout panel = card(surface, "服务器状态", "");
    if (metrics == null) {
      panel.addView(
        text(
          loading
            ? "正在读取服务器状态…"
            : connected
              ? "暂未获取负载信息，点击右上角刷新。若仍无数据，请更新服务端。"
              : "连接服务后，可查看 CPU、内存、磁盘与运行时间。",
          13,
          MUTED
        )
      );
      return;
    }
    JSONObject cpu = metrics.optJSONObject("cpu");
    int cores = cpu == null ? 0 : cpu.optInt("cores", 0);
    meter(panel, "CPU", cpu, cores > 0 ? cores + " 核 · 短时采样" : "短时采样");
    meter(
      panel,
      "内存",
      metrics.optJSONObject("memory"),
      capacity(metrics.optJSONObject("memory"))
    );
    JSONObject disk = metrics.optJSONObject("disk");
    meter(panel, "磁盘", disk, capacity(disk) + " · 服务所在磁盘");
    space(panel, 18);
    panel.addView(text("系统负载 · 1 / 5 / 15 分钟", 12, MUTED));
    JSONArray load = cpu == null ? null : cpu.optJSONArray("load_average");
    panel.addView(
      text(
        load == null || load.length() != 3
          ? "—"
          : String.format(
              Locale.ROOT,
              "%.2f    %.2f    %.2f",
              load.optDouble(0),
              load.optDouble(1),
              load.optDouble(2)
            ),
        18,
        INK
      )
    );
    space(panel, 10);
    row(
      panel,
      "已运行",
      metrics.isNull("uptime_seconds")
        ? "—"
        : duration(metrics.optLong("uptime_seconds"))
    );
    if (checkedAt > 0) panel.addView(
      text(
        (loading ? "正在刷新 · 上次 " : "更新于 ") +
          java.time.Instant.ofEpochMilli(checkedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
              java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm:ss")
            ),
        11,
        MUTED
      )
    );
  }

  private void meter(
    LinearLayout parent,
    String label,
    JSONObject metric,
    String detail
  ) {
    double value =
      metric == null || metric.isNull("usage_percent")
        ? Double.NaN
        : metric.optDouble("usage_percent");
    row(
      parent,
      label,
      Double.isNaN(value) ? "—" : String.format(Locale.ROOT, "%.1f%%", value)
    );
    parent.addView(text(detail, 11, MUTED));
    if (!Double.isNaN(value)) {
      space(parent, 6);
      ProgressBar bar = new ProgressBar(
        activity,
        null,
        android.R.attr.progressBarStyleHorizontal
      );
      bar.setMax(1000);
      bar.setProgress((int) Math.round(Math.max(0, Math.min(100, value)) * 10));
      bar.setProgressTintList(ColorStateList.valueOf(INK));
      bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
      bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
    }
    space(parent, 5);
  }

  private String capacity(JSONObject value) {
    if (
      value == null || value.isNull("total_bytes") || value.isNull("used_bytes")
    ) return "暂不可用";
    return (
      size(value.optLong("used_bytes")) +
      " / " +
      size(value.optLong("total_bytes"))
    );
  }

  private String duration(long seconds) {
    long minutes = Math.max(0, seconds) / 60;
    if (minutes < 60) return minutes + " 分钟";
    if (minutes < 1440) return (
      minutes / 60 + " 小时 " + (minutes % 60) + " 分钟"
    );
    return minutes / 1440 + " 天 " + (minutes % 1440) / 60 + " 小时";
  }
}
