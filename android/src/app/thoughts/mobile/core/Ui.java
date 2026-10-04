package app.thoughts.mobile.core;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.connection.ConnectionFailure;
import java.util.concurrent.ExecutorService;

/** Shared spacing, typography and touch targets used by every feature. */
public class Ui {

  public static final int INK = Color.rgb(43, 41, 38),
    MUTED = Color.rgb(121, 116, 107),
    BLUE = Color.rgb(155, 90, 67),
    PAPER = Color.rgb(255, 254, 252),
    LINE = Color.rgb(235, 232, 226),
    WHITE = PAPER,
    ALERT = Color.rgb(168, 69, 48);
  protected final Feature.Host host;
  protected final Activity activity;
  protected final ExecutorService IO;

  public Ui(Feature.Host host) {
    this.host = host;
    activity = host.activity();
    IO = host.executor();
  }

  public int dp(int value) {
    return Math.round(
      value * activity.getResources().getDisplayMetrics().density
    );
  }

  public LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  public TextView text(String value, int size, int color) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(dp(3), 1);
    v.setIncludeFontPadding(false);
    return v;
  }

  public GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), LINE);
    return d;
  }

  public GradientDrawable flatBackground(int color, int radius) {
    GradientDrawable result = background(color, radius);
    result.setStroke(0, color);
    return result;
  }

  public void divider(LinearLayout parent) {
    View line = new View(activity);
    line.setBackgroundColor(LINE);
    parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  public void setting(
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
    value.setMaxWidth(
      activity.getResources().getDisplayMetrics().widthPixels / 2
    );
    value.setMaxLines(2);
    value.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

  public void space(LinearLayout parent, int size) {
    parent.addView(
      new View(activity),
      new LinearLayout.LayoutParams(1, dp(size))
    );
  }

  public Button button(String label, Runnable action, boolean primary) {
    Button b = new Button(activity);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(48));
    b.setTextColor(primary ? WHITE : INK);
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    b.setMinWidth(dp(48));
    b.setMinimumWidth(dp(48));
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

  public ImageButton iconButton(String symbol, String label, Runnable action) {
    return iconButton(activity, symbol, label, action, INK);
  }

  public ImageButton iconButton(
    String symbol,
    String label,
    Runnable action,
    int color
  ) {
    return iconButton(activity, symbol, label, action, color);
  }

  /** A 24 dp glyph inside a 48 dp button, with the same label for touch and assistive users. */
  public static ImageButton iconButton(
    Activity activity,
    String symbol,
    String label,
    Runnable action,
    int color
  ) {
    float density = activity.getResources().getDisplayMetrics().density;
    int target = Math.round(48 * density),
      padding = Math.round(12 * density);
    ImageButton button = new ImageButton(activity);
    button.setLayoutParams(new LinearLayout.LayoutParams(target, target));
    button.setMinimumWidth(target);
    button.setMinimumHeight(target);
    button.setPadding(padding, padding, padding, padding);
    button.setScaleType(ImageView.ScaleType.FIT_XY);
    button.setImageDrawable(icon(symbol, color));
    button.setImageTintList(
      new ColorStateList(
        new int[][] {
          new int[] { -android.R.attr.state_enabled },
          new int[] {},
        },
        new int[] { (color & 0x00ffffff) | 0x61000000, color }
      )
    );
    button.setContentDescription(label);
    button.setTooltipText(label);
    button.setFocusable(true);
    button.setStateListAnimator(null);
    GradientDrawable mask = new GradientDrawable();
    mask.setColor(Color.WHITE);
    mask.setCornerRadius(8 * density);
    button.setBackground(
      new RippleDrawable(
        ColorStateList.valueOf((color & 0x00ffffff) | 0x28000000),
        null,
        mask
      )
    );
    button.setOnClickListener(view -> action.run());
    return button;
  }

  /** Status is attached to content; it does not add another action or tab stop. */
  public ImageView statusIcon(String symbol, String label) {
    ImageView image = new ImageView(activity);
    image.setImageDrawable(icon(symbol, MUTED));
    image.setScaleType(ImageView.ScaleType.FIT_XY);
    image.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
    image.setContentDescription(label);
    image.setTooltipText(label);
    image.setFocusable(false);
    image.setClickable(false);
    return image;
  }

  public EditText input(String hint, boolean multiline) {
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

  public TextWatcher watcher(Runnable fn) {
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

  public void heading(
    LinearLayout parent,
    String eyebrow,
    String title,
    String description
  ) {
    if (!description.isEmpty()) parent.addView(text(description, 13, MUTED));
    space(parent, 16);
  }

  public LinearLayout card(
    LinearLayout parent,
    String title,
    String description
  ) {
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

  public void row(LinearLayout parent, String label, String value) {
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

  public void status(String message) {
    host.status(message);
  }

  public void runOnUiThread(Runnable fn) {
    activity.runOnUiThread(() -> {
      if (!activity.isDestroyed()) fn.run();
    });
  }

  public String errorMessage(Exception e) {
    return e instanceof ConnectionFailure
      ? e.getMessage()
      : "操作未完成，请检查连接后重试。";
  }

  public void copy(String value) {
    (
      (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE)
    ).setPrimaryClip(ClipData.newPlainText("服务器工具", value));
    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
  }

  public static String size(long bytes) {
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

  public static Drawable icon(String id, int color) {
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
        p.setStrokeJoin(Paint.Join.ROUND);
        if (id.equals("back")) {
          c.drawLine(5, 12, 20, 12, p);
          c.drawLine(5, 12, 11, 6, p);
          c.drawLine(5, 12, 11, 18, p);
        } else if (id.equals("add")) {
          c.drawLine(12, 5, 12, 19, p);
          c.drawLine(5, 12, 19, 12, p);
        } else if (id.equals("close")) {
          c.drawLine(6, 6, 18, 18, p);
          c.drawLine(18, 6, 6, 18, p);
        } else if (id.equals("more")) {
          p.setStyle(Paint.Style.FILL);
          for (int y = 5; y <= 19; y += 7) c.drawCircle(12, y, 1.6f, p);
        } else if (id.equals("filter")) {
          c.drawLine(4, 6, 20, 6, p);
          c.drawLine(7, 12, 17, 12, p);
          c.drawLine(10, 18, 14, 18, p);
        } else if (id.equals("copy")) {
          c.drawRoundRect(8, 7, 20, 21, 2, 2, p);
          Path rear = new Path();
          rear.moveTo(5, 17);
          rear.lineTo(3, 17);
          rear.lineTo(3, 3);
          rear.lineTo(16, 3);
          rear.lineTo(16, 4);
          c.drawPath(rear, p);
        } else if (id.equals("share")) {
          c.drawLine(8.5f, 10.7f, 15.5f, 6.3f, p);
          c.drawLine(8.5f, 13.3f, 15.5f, 17.7f, p);
          c.drawCircle(6, 12, 2.7f, p);
          c.drawCircle(18, 5, 2.7f, p);
          c.drawCircle(18, 19, 2.7f, p);
        } else if (id.equals("download_done")) {
          c.drawLine(5, 10, 9, 14, p);
          c.drawLine(9, 14, 18, 5, p);
          c.drawLine(5, 20, 19, 20, p);
        } else if (id.equals("download") || id.equals("partial")) {
          c.drawLine(12, 3, 12, 15, p);
          c.drawLine(7, 10, 12, 15, p);
          c.drawLine(12, 15, 17, 10, p);
          c.drawLine(5, 20, 19, 20, p);
        } else if (id.equals("check_box") || id.equals("check_box_outline")) {
          c.drawRoundRect(3, 3, 21, 21, 3, 3, p);
          if (id.equals("check_box")) {
            c.drawLine(7, 12, 10.5f, 15.5f, p);
            c.drawLine(10.5f, 15.5f, 17, 8, p);
          }
        } else if (id.equals("capture")) {
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
        } else if (id.equals("inbox")) {
          Path tray = new Path();
          tray.moveTo(3, 10);
          tray.lineTo(6, 4);
          tray.lineTo(18, 4);
          tray.lineTo(21, 10);
          tray.lineTo(21, 20);
          tray.lineTo(3, 20);
          tray.close();
          c.drawPath(tray, p);
          c.drawLine(3, 12, 8, 12, p);
          c.drawLine(8, 12, 10, 15, p);
          c.drawLine(10, 15, 14, 15, p);
          c.drawLine(14, 15, 16, 12, p);
          c.drawLine(16, 12, 21, 12, p);
        } else if (id.equals("thoughts")) {
          c.drawRoundRect(5, 3, 19, 21, 2, 2, p);
          for (int y = 8; y < 18; y += 4) c.drawLine(9, y, 15, y, p);
        } else if (id.equals("terminal")) {
          c.drawRoundRect(2, 4, 22, 20, 2, 2, p);
          c.drawLine(6, 9, 9, 12, p);
          c.drawLine(9, 12, 6, 15, p);
          c.drawLine(12, 15, 18, 15, p);
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
