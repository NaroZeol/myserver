package app.thoughts.mobile.modules.inbox;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import app.thoughts.mobile.core.Ui;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** On-demand private preview; gallery export is always a separate explicit action. */
public final class InboxPreviewActivity extends Activity {

  private static final ExecutorService IO = Executors.newFixedThreadPool(2);
  private static final int GALLERY = 7310,
    SAVE = 7311;
  private InboxStore store;
  private String taskId;
  private TextView status;
  private FrameLayout content;
  private boolean rendered, listening, saving, updateQueued;
  private Bitmap image;
  private VideoView video;
  private InboxStore.Task task;
  private final Handler handler = new Handler();
  private final Runnable refresh = () -> {
    updateQueued = false;
    refresh();
  };
  private final BroadcastReceiver changes = new BroadcastReceiver() {
    public void onReceive(Context c, Intent i) {
      if (!updateQueued) {
        updateQueued = true;
        handler.postDelayed(refresh, 150);
      }
    }
  };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setStatusBarColor(0xff1c1d20);
    getWindow().setNavigationBarColor(0xff1c1d20);
    getWindow().getDecorView().setSystemUiVisibility(0);
    store = new InboxStore(this);
    taskId = getIntent().getStringExtra("task_id");
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(0xff1c1d20);
    root.setPadding(dp(12), dp(12), dp(12), dp(12));
    root.setOnApplyWindowInsetsListener((v, i) -> {
      v.setPadding(
        dp(12),
        i.getSystemWindowInsetTop() + dp(8),
        dp(12),
        i.getSystemWindowInsetBottom() + dp(8)
      );
      return i;
    });
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(Ui.iconButton(this, "back", "返回", this::finish, 0xffe8e6df));
    TextView title = text("预览", 17);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setPadding(dp(8), 0, dp(8), 0);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(Ui.iconButton(this, "more", "更多", this::menu, 0xffe8e6df));
    root.addView(bar);
    content = new FrameLayout(this);
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    status = text("", 13);
    status.setPadding(12, 12, 12, 12);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status);
    setContentView(root);
    try {
      task = store.task(taskId);
      if (task == null) throw new IOException("文件不存在");
      title.setText(
        task.document.getJSONObject("file").optString("name", "预览")
      );
      title.setTooltipText(title.getText());
      refresh();
      if (
        !task.state.equals("done") && !InboxFeature.requestNotifications(this)
      ) TransferService.start(this);
    } catch (Exception e) {
      status.setText("无法打开预览");
    }
  }

  protected void onResume() {
    super.onResume();
    if (!listening) {
      IntentFilter f = new IntentFilter(TransferService.ACTION_CHANGED);
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(
        changes,
        f,
        Context.RECEIVER_NOT_EXPORTED
      );
      else registerReceiver(changes, f);
      listening = true;
    }
    refresh();
  }

  protected void onPause() {
    if (video != null) video.pause();
    if (listening) {
      unregisterReceiver(changes);
      listening = false;
    }
    handler.removeCallbacks(refresh);
    updateQueued = false;
    super.onPause();
  }

  protected void onDestroy() {
    if (video != null) video.stopPlayback();
    if (image != null) image.recycle();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void refresh() {
    if (rendered || store == null) return;
    try {
      task = store.task(taskId);
      if (task == null) {
        status.setText("文件已移除");
        return;
      }
      if (task.state.equals("done")) {
        rendered = true;
        show();
        return;
      }
      status.setText(
        task.error.isEmpty()
          ? app.thoughts.mobile.core.Ui.size(task.transferred) +
              " / " +
              app.thoughts.mobile.core.Ui.size(task.total)
          : task.error
      );
      content.removeAllViews();
      LinearLayout wait = new LinearLayout(this);
      wait.setOrientation(1);
      wait.setGravity(Gravity.CENTER);
      wait.addView(
        text(
          task.state.equals("failed") || task.state.equals("paused")
            ? "预览未就绪"
            : "正在获取预览",
          18
        )
      );
      ProgressBar progress = new ProgressBar(
        this,
        null,
        android.R.attr.progressBarStyleHorizontal
      );
      progress.setMax(1000);
      progress.setIndeterminate(task.total <= 0);
      progress.setProgress(
        task.total > 0
          ? (int) Math.min(1000, (1000.0 * task.transferred) / task.total)
          : 0
      );
      wait.addView(progress, new LinearLayout.LayoutParams(-1, 32));
      if (
        task.state.equals("paused") ||
        task.state.equals("failed") ||
        task.state.equals("canceled")
      ) wait.addView(button("重试", () -> TransferService.retry(this, taskId)));
      else wait.addView(
        button("取消", () -> {
          TransferService.cancel(this, taskId);
          finish();
        })
      );
      content.addView(wait, new FrameLayout.LayoutParams(-1, -1));
    } catch (Exception e) {
      status.setText("预览读取失败");
    }
  }

  private void show() {
    status.setText("");
    IO.execute(() -> {
      try {
        File local = store.localFile(task);
        JSONObject file = task.document.getJSONObject("file");
        String type = InboxMedia.mime(file);
        if (type.startsWith("image/")) {
          Bitmap decoded = InboxMedia.bitmap(local, 2560);
          runOnUiThread(() -> {
            if (isDestroyed()) {
              decoded.recycle();
              return;
            }
            image = decoded;
            content.removeAllViews();
            content.addView(
              new Photo(this, decoded),
              new FrameLayout.LayoutParams(-1, -1)
            );
          });
        } else if (type.startsWith("video/")) {
          runOnUiThread(() -> {
            if (isDestroyed()) return;
            try {
              video = new VideoView(this);
              video.setVideoURI(InboxFiles.uri(this, task));
              MediaController controls = new MediaController(this);
              controls.setAnchorView(video);
              video.setMediaController(controls);
              video.setOnPreparedListener(p -> {
                status.setText("");
                video.seekTo(1);
                controls.show(0);
              });
              video.setOnErrorListener((v, w, e) -> {
                status.setText("无法播放此视频，可从更多菜单用其他应用打开");
                return true;
              });
              content.removeAllViews();
              content.addView(
                video,
                new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
              );
            } catch (Exception e) {
              status.setText("视频读取失败");
            }
          });
        } else {
          byte[] bytes;
          try (
            InputStream input = new FileInputStream(local);
            ByteArrayOutputStream output = new ByteArrayOutputStream()
          ) {
            byte[] buffer = new byte[8192];
            int count;
            while (
                output.size() < 256 * 1024 &&
                (count = input.read(
                  buffer,
                  0,
                  Math.min(buffer.length, 256 * 1024 - output.size())
                )) != -1
              )
              output.write(buffer, 0, count);
            bytes = output.toByteArray();
          }
          String body = new String(bytes, StandardCharsets.UTF_8);
          runOnUiThread(() -> {
            if (isDestroyed()) return;
            TextView value = text(body, 15);
            value.setTypeface(Typeface.MONOSPACE);
            value.setTextIsSelectable(true);
            value.setPadding(16, 16, 16, 16);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(value);
            content.removeAllViews();
            content.addView(scroll);
            if (local.length() > bytes.length) status.setText(
              "已显示前 256 KB"
            );
          });
        }
      } catch (Exception e) {
        runOnUiThread(() -> {
          if (!isDestroyed()) status.setText(
            e.getMessage() == null
              ? "无法预览，可用其他应用打开"
              : e.getMessage()
          );
        });
      }
    });
  }

  private void menu() {
    if (task == null || !task.state.equals("done")) return;
    try {
      boolean media = InboxMedia.media(task.document.getJSONObject("file"));
      String[] actions = media
        ? new String[] { "保存到相册", "分享", "另存为", "用其他应用打开" }
        : new String[] { "分享", "另存为", "用其他应用打开" };
      new AlertDialog.Builder(this)
        .setItems(actions, (d, w) -> {
          String selected = actions[w];
          if (selected.equals("保存到相册")) gallery();
          else if (selected.equals("分享")) InboxFiles.share(this, task);
          else if (selected.equals("用其他应用打开")) InboxFiles.open(
            this,
            task
          );
          else try {
            JSONObject file = task.document.getJSONObject("file");
            startActivityForResult(
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(InboxMedia.mime(file))
                .putExtra(Intent.EXTRA_TITLE, file.optString("name")),
              SAVE
            );
          } catch (Exception e) {
            status.setText("无法选择保存位置");
          }
        })
        .show();
    } catch (Exception e) {
      status.setText("无法读取文件");
    }
  }

  private void gallery() {
    if (saving) return;
    if (
      Build.VERSION.SDK_INT < 29 &&
      checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(
        new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE },
        GALLERY
      );
      return;
    }
    saving = true;
    status.setText("正在保存…");
    IO.execute(() -> {
      try {
        InboxMedia.save(this, task);
        runOnUiThread(() -> {
          saving = false;
          status.setText("已保存到相册");
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          saving = false;
          status.setText(
            e.getMessage() == null ? "保存失败，请重试" : e.getMessage()
          );
        });
      }
    });
  }

  public void onRequestPermissionsResult(
    int request,
    String[] names,
    int[] values
  ) {
    super.onRequestPermissionsResult(request, names, values);
    if (request == InboxFeature.NOTIFICATION_PERMISSION) TransferService.start(
      this
    );
    if (request == GALLERY) {
      if (
        checkSelfPermission(
          android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
      ) gallery();
      else status.setText("未保存，可从更多菜单另存为");
    }
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (
      request == SAVE &&
      result == RESULT_OK &&
      data != null &&
      data.getData() != null
    ) IO.execute(() -> {
      try {
        InboxFiles.saveTo(this, task, data.getData());
        runOnUiThread(() -> status.setText("已保存"));
      } catch (Exception e) {
        runOnUiThread(() -> status.setText("保存失败，请重试"));
      }
    });
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(0xffe8e6df);
    return v;
  }

  private Button button(String value, Runnable action) {
    Button b = new Button(this);
    b.setText(value);
    b.setTextColor(0xffe8e6df);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setMinWidth(dp(48));
    b.setMinimumWidth(dp(48));
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setContentDescription(value);
    b.setTooltipText(value);
    b.setBackgroundColor(Color.TRANSPARENT);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  static final class Photo extends View {

    final Bitmap bitmap;
    final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final ScaleGestureDetector scale;
    final GestureDetector gestures;
    float zoom = 1,
      x,
      y,
      lastX,
      lastY;

    Photo(Context context, Bitmap bitmap) {
      super(context);
      this.bitmap = bitmap;
      setContentDescription("图片预览，可双指缩放和拖动，双击复位");
      scale = new ScaleGestureDetector(
        context,
        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          public boolean onScale(ScaleGestureDetector detector) {
            float old = zoom;
            zoom = Math.max(1, Math.min(8, zoom * detector.getScaleFactor()));
            x =
              ((x + getWidth() / 2f - detector.getFocusX()) * zoom) / old -
              getWidth() / 2f +
              detector.getFocusX();
            y =
              ((y + getHeight() / 2f - detector.getFocusY()) * zoom) / old -
              getHeight() / 2f +
              detector.getFocusY();
            clamp();
            invalidate();
            return true;
          }
        }
      );
      gestures = new GestureDetector(
        context,
        new GestureDetector.SimpleOnGestureListener() {
          public boolean onDown(MotionEvent e) {
            return true;
          }

          public boolean onDoubleTap(MotionEvent e) {
            zoom = zoom > 1 ? 1 : 2;
            x = y = 0;
            clamp();
            invalidate();
            return true;
          }
        }
      );
    }

    float fit() {
      return Math.min(
        (float) getWidth() / bitmap.getWidth(),
        (float) getHeight() / bitmap.getHeight()
      );
    }

    void clamp() {
      float maxX = Math.max(
          0,
          (bitmap.getWidth() * fit() * zoom - getWidth()) / 2
        ),
        maxY = Math.max(
          0,
          (bitmap.getHeight() * fit() * zoom - getHeight()) / 2
        );
      x = Math.max(-maxX, Math.min(maxX, x));
      y = Math.max(-maxY, Math.min(maxY, y));
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float s = fit() * zoom;
      c.save();
      c.translate(getWidth() / 2f + x, getHeight() / 2f + y);
      c.scale(s, s);
      c.drawBitmap(
        bitmap,
        -bitmap.getWidth() / 2f,
        -bitmap.getHeight() / 2f,
        paint
      );
      c.restore();
    }

    protected void onSizeChanged(int w, int h, int ow, int oh) {
      zoom = 1;
      x = y = 0;
    }

    public boolean onTouchEvent(MotionEvent e) {
      scale.onTouchEvent(e);
      gestures.onTouchEvent(e);
      if (
        e.getActionMasked() == MotionEvent.ACTION_MOVE &&
        !scale.isInProgress() &&
        e.getPointerCount() == 1
      ) {
        x += e.getX() - lastX;
        y += e.getY() - lastY;
        clamp();
        invalidate();
      }
      if (
        e.getActionMasked() == MotionEvent.ACTION_POINTER_UP &&
        e.getPointerCount() > 1
      ) {
        int remaining = e.getActionIndex() == 0 ? 1 : 0;
        lastX = e.getX(remaining);
        lastY = e.getY(remaining);
        return true;
      }
      lastX = e.getX();
      lastY = e.getY();
      return true;
    }
  }
}
