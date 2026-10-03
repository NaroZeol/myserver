package app.thoughts.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Offline, isolated terminal renderer. The bridge has no filesystem, clipboard or credential APIs. */
final class TerminalSurface extends WebView {

  interface Listener {
    void ready();
    void input(byte[] bytes);
    void resize(int cols, int rows);
    void modifiersChanged(int control, int alt);
    void selectionChanged(boolean selected);
    void fontStep(int step);
    void failed();
  }

  private final ConcurrentHashMap<Integer, CountDownLatch> writes =
    new ConcurrentHashMap<>();
  private final AtomicInteger serial = new AtomicInteger();
  private volatile boolean disposed;
  volatile boolean loaded;
  volatile String rendererIssue = "";
  int columns = 80,
    rows = 24;

  @SuppressLint({ "SetJavaScriptEnabled", "AddJavascriptInterface" })
  TerminalSurface(Context context, Listener listener) {
    super(context);
    setBackgroundColor(android.graphics.Color.rgb(28, 29, 32));
    setContentDescription("交互式 SSH 终端");
    setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    setSaveEnabled(false);
    setOnLongClickListener(view -> true);
    WebSettings settings = getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setBlockNetworkLoads(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setSupportMultipleWindows(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false);
    addJavascriptInterface(
      new Object() {
        @JavascriptInterface
        public void ready() {
          post(() -> {
            if (!disposed) {
              loaded = true;
              listener.ready();
            }
          });
        }

        @JavascriptInterface
        public void input(String encoded) {
          if (disposed || encoded.length() > 90000) return;
          try {
            listener.input(Base64.decode(encoded, Base64.NO_WRAP));
          } catch (IllegalArgumentException ignored) {}
        }

        @JavascriptInterface
        public void resize(int cols, int lines) {
          post(() -> {
            if (!disposed) {
              columns = cols;
              rows = lines;
              listener.resize(cols, lines);
            }
          });
        }

        @JavascriptInterface
        public void ack(int id) {
          CountDownLatch latch = writes.remove(id);
          if (latch != null) latch.countDown();
        }

        @JavascriptInterface
        public void modifiersChanged(int control, int alt) {
          post(() -> {
            if (!disposed) listener.modifiersChanged(control, alt);
          });
        }

        @JavascriptInterface
        public void selectionChanged(boolean selected) {
          post(() -> {
            if (!disposed) listener.selectionChanged(selected);
          });
        }

        @JavascriptInterface
        public void fontStep(int step) {
          post(() -> {
            if (!disposed && Math.abs(step) == 1) listener.fontStep(step);
          });
        }
      },
      "Phone"
    );
    setWebChromeClient(
      new WebChromeClient() {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
          if (
            message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
          ) rendererIssue =
            "script error at " +
            message.sourceId() +
            ":" +
            message.lineNumber();
          return true;
        }
      }
    );
    setWebViewClient(
      new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(
          WebView view,
          WebResourceRequest request
        ) {
          return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
          return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
          WebView view,
          WebResourceRequest request
        ) {
          android.net.Uri uri = request.getUrl();
          String path = uri.getPath();
          if (
            "https".equals(uri.getScheme()) &&
            "terminal.invalid".equals(uri.getHost()) &&
            "GET".equals(request.getMethod()) &&
            path != null &&
            path.matches(
              "/(index\\.html|xterm\\.js|xterm\\.css|addon-fit\\.js|terminal\\.js|touch\\.js|compat\\.js|terminal\\.css)"
            )
          ) {
            try {
              String type = path.endsWith(".js")
                ? "application/javascript"
                : path.endsWith(".css")
                  ? "text/css"
                  : "text/html";
              return new WebResourceResponse(
                type,
                "UTF-8",
                context.getAssets().open("terminal" + path)
              );
            } catch (Exception ignored) {}
          }
          return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            java.util.Collections.emptyMap(),
            new ByteArrayInputStream(new byte[0])
          );
        }

        @Override
        public boolean onRenderProcessGone(
          WebView view,
          RenderProcessGoneDetail detail
        ) {
          dispose();
          listener.failed();
          return true;
        }
      }
    );
    loadUrl("https://terminal.invalid/index.html");
    postDelayed(() -> {
      if (!loaded && !disposed) listener.failed();
    }, 12000);
  }

  void writeBlocking(byte[] bytes) throws InterruptedException {
    if (disposed) throw new InterruptedException();
    int id = serial.incrementAndGet();
    CountDownLatch done = new CountDownLatch(1);
    writes.put(id, done);
    String data = Base64.encodeToString(bytes, Base64.NO_WRAP);
    post(() -> {
      if (!disposed) evaluateJavascript(
        "TerminalUI.write(" + JSONObject.quote(data) + "," + id + ")",
        null
      );
    });
    try {
      while (!done.await(1, TimeUnit.SECONDS))
        if (disposed) throw new InterruptedException();
    } finally {
      writes.remove(id);
    }
  }

  void call(String method, String value) {
    if (loaded && !disposed) evaluateJavascript(
      "TerminalUI." + method + "(" + JSONObject.quote(value) + ")",
      null
    );
  }

  void dispose() {
    if (disposed) return;
    disposed = true;
    writes.values().forEach(CountDownLatch::countDown);
    writes.clear();
    removeJavascriptInterface("Phone");
    stopLoading();
    if (getParent() instanceof android.view.ViewGroup) (
      (android.view.ViewGroup) getParent()
    ).removeView(this);
    destroy();
  }
}
