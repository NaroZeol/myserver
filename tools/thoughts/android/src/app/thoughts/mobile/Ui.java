package app.thoughts.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.ExecutorService;

/** Shared spacing, typography and touch targets used by every feature. */
class Ui {

  static final int INK = Color.rgb(43, 41, 38),
    MUTED = Color.rgb(125, 120, 111),
    BLUE = Color.rgb(155, 90, 67),
    PAPER = Color.rgb(255, 254, 252),
    LINE = Color.rgb(235, 232, 226),
    WHITE = PAPER,
    ALERT = Color.rgb(168, 69, 48);
  final Feature.Host host;
  final Activity activity;
  final Store store;
  final Account account;
  final ExecutorService IO;

  Ui(Feature.Host host) {
    this.host = host;
    activity = host.activity();
    store = host.store();
    account = host.account();
    IO = host.executor();
  }

  int dp(int value) {
    return Math.round(
      value * activity.getResources().getDisplayMetrics().density
    );
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  TextView text(String value, int size, int color) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(dp(3), 1);
    v.setIncludeFontPadding(false);
    return v;
  }

  GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), LINE);
    return d;
  }

  GradientDrawable flatBackground(int color, int radius) {
    GradientDrawable result = background(color, radius);
    result.setStroke(0, color);
    return result;
  }

  void divider(LinearLayout parent) {
    View line = new View(activity);
    line.setBackgroundColor(LINE);
    parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  void setting(
    LinearLayout parent,
    String label,
    String detail,
    Runnable action
  ) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(16), 0, dp(16));
    TextView name = text(label, 15, INK);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    TextView value = text(detail + (action == null ? "" : "   ›"), 12, MUTED);
    value.setGravity(Gravity.END);
    row.addView(value);
    if (action != null) {
      row.setMinimumHeight(dp(52));
      row.setBackground(
        new RippleDrawable(
          ColorStateList.valueOf(0x10000000),
          null,
          flatBackground(WHITE, 0)
        )
      );
      row.setOnClickListener(v -> action.run());
      row.setFocusable(true);
    }
    parent.addView(row);
    divider(parent);
  }

  void space(LinearLayout parent, int size) {
    parent.addView(
      new View(activity),
      new LinearLayout.LayoutParams(1, dp(size))
    );
  }

  Button button(String label, Runnable action, boolean primary) {
    Button b = new Button(activity);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(48));
    b.setTextColor(primary ? WHITE : INK);
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setBackground(
      new RippleDrawable(
        ColorStateList.valueOf(0x189b5a43),
        flatBackground(primary ? INK : Color.TRANSPARENT, 8),
        null
      )
    );
    b.setOnClickListener(v -> action.run());
    return b;
  }

  EditText input(String hint, boolean multiline) {
    EditText v = new EditText(activity);
    v.setTextSize(16);
    v.setTextColor(INK);
    v.setHintTextColor(MUTED);
    v.setHint(hint);
    v.setPadding(dp(16), dp(16), dp(16), dp(16));
    v.setBackground(background(WHITE, 14));
    v.setInputType(
      InputType.TYPE_CLASS_TEXT |
        (multiline
          ? InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
          : 0)
    );
    return v;
  }

  TextWatcher watcher(Runnable fn) {
    return new TextWatcher() {
      public void beforeTextChanged(
        CharSequence s,
        int start,
        int count,
        int after
      ) {}

      public void onTextChanged(
        CharSequence s,
        int start,
        int before,
        int count
      ) {
        fn.run();
      }

      public void afterTextChanged(Editable value) {}
    };
  }

  void heading(
    LinearLayout parent,
    String eyebrow,
    String title,
    String description
  ) {
    if (!description.isEmpty()) parent.addView(text(description, 13, MUTED));
    space(parent, 16);
  }

  LinearLayout card(LinearLayout parent, String title, String description) {
    space(parent, 22);
    if (!title.isEmpty()) parent.addView(text(title, 12, MUTED));
    LinearLayout card = column();
    if (!description.isEmpty()) {
      space(card, 12);
      card.addView(text(description, 13, MUTED));
    }
    parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    return card;
  }

  void row(LinearLayout parent, String label, String value) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    row.addView(
      text(label, 14, MUTED),
      new LinearLayout.LayoutParams(0, -2, 1)
    );
    TextView detail = text(value, 14, INK);
    detail.setGravity(Gravity.END);
    row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row);
  }

  void status(String message) {
    host.status(message);
  }

  void sync() {
    host.sync();
  }

  void runOnUiThread(Runnable fn) {
    activity.runOnUiThread(() -> {
      if (!activity.isDestroyed()) fn.run();
    });
  }

  String errorMessage(Exception e) {
    return e instanceof Api.Failure
      ? e.getMessage()
      : "操作未完成，本地内容已保留。请检查连接后重试。";
  }

  void copy(String value) {
    (
      (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)
    ).setPrimaryClip(ClipData.newPlainText("想法", value));
    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
  }

  static String size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1048576) return String.format(
      java.util.Locale.ROOT,
      "%.1f KB",
      bytes / 1024.0
    );
    if (bytes < 1073741824) return String.format(
      java.util.Locale.ROOT,
      "%.1f MB",
      bytes / 1048576.0
    );
    return String.format(
      java.util.Locale.ROOT,
      "%.1f GB",
      bytes / 1073741824.0
    );
  }

  Drawable icon(String id, int color) {
    return new Drawable() {
      final Paint p = new Paint(3);

      public void draw(Canvas c) {
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        p.setColor(color);
        p.setStrokeWidth(1.7f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (id.equals("capture")) {
          Path pencil = new Path();
          pencil.moveTo(4, 20);
          pencil.lineTo(8, 20);
          pencil.lineTo(20, 8);
          pencil.lineTo(16, 4);
          pencil.lineTo(4, 16);
          pencil.close();
          c.drawPath(pencil, p);
          c.drawLine(13, 7, 17, 11, p);
        } else if (id.equals("sync")) {
          c.drawArc(4, 4, 20, 20, 205, 150, false, p);
          c.drawArc(4, 4, 20, 20, 25, 150, false, p);
          c.drawLine(20, 6, 20, 11, p);
          c.drawLine(15, 11, 20, 11, p);
          c.drawLine(4, 18, 4, 13, p);
          c.drawLine(4, 13, 9, 13, p);
        } else if (id.equals("notes")) {
          c.drawRoundRect(5, 3, 19, 21, 2, 2, p);
          for (int y = 8; y < 18; y += 4) c.drawLine(9, y, 15, y, p);
        } else if (id.equals("server")) {
          c.drawRoundRect(3, 4, 21, 11, 2, 2, p);
          c.drawRoundRect(3, 14, 21, 21, 2, 2, p);
          c.drawPoint(7, 7.5f, p);
          c.drawPoint(7, 17.5f, p);
          c.drawLine(12, 7.5f, 17, 7.5f, p);
          c.drawLine(12, 17.5f, 17, 17.5f, p);
        } else {
          for (int y = 6; y <= 18; y += 6) c.drawLine(4, y, 20, y, p);
          c.drawCircle(9, 6, 2, p);
          c.drawCircle(16, 12, 2, p);
          c.drawCircle(8, 18, 2, p);
        }
        c.restore();
      }

      public void setAlpha(int a) {
        p.setAlpha(a);
      }

      public void setColorFilter(ColorFilter f) {
        p.setColorFilter(f);
      }

      public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
      }
    };
  }
}
