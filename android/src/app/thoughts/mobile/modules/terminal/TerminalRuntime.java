package app.thoughts.mobile.modules.terminal;

import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import app.thoughts.mobile.core.connection.ServerProfile;
import app.thoughts.mobile.core.connection.ShellIdentity;

/** Main-thread owner of one live PTY and its emulator, independent of any Activity. */
final class TerminalRuntime implements TerminalSurface.Listener {

  interface Observer {
    void changed();
  }

  private static TerminalRuntime current;

  static TerminalRuntime current() {
    return current;
  }

  static TerminalRuntime obtain(Context context, ServerProfile profile) {
    if (
      current != null &&
      !current.busy &&
      (!current.profile.sameEndpoint(profile) ||
        !current.profile.knownHost.equals(profile.knownHost) ||
        !current.profile.name.equals(profile.name))
    ) current.dispose();
    if (current == null) current = new TerminalRuntime(context, profile);
    return current;
  }

  final Context app;
  final ServerProfile profile;
  final MutableContextWrapper context;
  final TerminalSurface surface;
  final Handler main = new Handler(Looper.getMainLooper());
  volatile TerminalSession connection;
  boolean busy, attempted;
  String status;
  private TerminalSurface.Listener ui;
  private Observer observer;
  private byte[] pendingPassword;
  private boolean pendingEnroll, pending;
  private TerminalService service;

  private TerminalRuntime(Context owner, ServerProfile profile) {
    app = owner.getApplicationContext();
    this.profile = profile;
    context = new MutableContextWrapper(app);
    surface = new TerminalSurface(context, this);
    status = "未连接 · " + profile.address();
  }

  void attach(
    Context owner,
    TerminalSurface.Listener listener,
    Observer observer
  ) {
    if (surface.getParent() instanceof ViewGroup) (
      (ViewGroup) surface.getParent()
    ).removeView(surface);
    context.setBaseContext(owner);
    ui = listener;
    this.observer = observer;
  }

  void detach(Observer owner) {
    if (observer != owner) return;
    ui = null;
    observer = null;
    if (surface.getParent() instanceof ViewGroup) (
      (ViewGroup) surface.getParent()
    ).removeView(surface);
    context.setBaseContext(app);
    if (!busy) dispose();
  }

  void begin(byte[] password, boolean enroll) {
    if (busy) {
      wipe(password);
      return;
    }
    attempted = busy = pending = true;
    pendingPassword = password;
    pendingEnroll = enroll;
    status = enroll
      ? "正在登录并登记终端密钥…"
      : "正在连接 " + profile.address();
    surface.call("newSession", "");
    final TerminalSession[] next = new TerminalSession[1];
    connection = next[0] = new TerminalSession(
      new TerminalSession.Listener() {
        public void authorized() {
          try {
            ShellIdentity.remember(app, profile);
          } catch (Exception e) {
            main.post(() -> {
              if (connection != next[0]) return;
              status = "设备授权未能保存";
              changed();
            });
          }
        }

        public void connected(boolean registered) {
          main.post(() -> {
            if (connection == next[0] && busy) {
              status = "已连接 · " + profile.address();
              changed();
            }
          });
        }

        public void output(byte[] data) throws Exception {
          if (connection == next[0]) surface.writeBlocking(
            data,
            () -> connection == next[0]
          );
        }

        public void ended(String reason) {
          main.post(() -> {
            if (connection != next[0]) return;
            finish(reason);
          });
        }
      }
    );
    connection.resize(surface.columns, surface.rows);
    changed();
    try {
      app.startForegroundService(new Intent(app, TerminalService.class));
    } catch (RuntimeException e) {
      disconnect("无法启动后台终端，请返回应用后重试");
    }
  }

  void serviceReady(TerminalService owner) {
    service = owner;
    if (!busy) {
      owner.finishSession();
      return;
    }
    if (!pending) return;
    byte[] password = pendingPassword;
    pendingPassword = null;
    pending = false;
    connection.start(profile, password, pendingEnroll);
  }

  void serviceStopped(TerminalService owner) {
    if (service != owner) return;
    service = null;
    if (busy) disconnect("后台终端已停止，请重新连接");
  }

  void disconnect(String message) {
    pending = false;
    wipe(pendingPassword);
    pendingPassword = null;
    TerminalSession previous = connection;
    connection = null;
    if (previous != null) previous.close();
    finish(message);
  }

  private void finish(String message) {
    busy = false;
    status = message;
    surface.call("resetModifiers", "");
    TerminalService owner = service;
    service = null;
    if (owner != null) owner.finishSession();
    changed();
    if (ui == null) dispose();
  }

  private void changed() {
    if (observer != null) observer.changed();
  }

  private static void wipe(byte[] value) {
    if (value != null) java.util.Arrays.fill(value, (byte) 0);
  }

  private void dispose() {
    if (current == this) current = null;
    surface.dispose();
    context.setBaseContext(app);
  }

  public void ready() {
    if (ui != null) ui.ready();
  }

  public void input(byte[] bytes) {
    TerminalSession active = connection;
    if (active == null || !active.send(bytes)) {
      wipe(bytes);
      main.post(() -> {
        if (busy) {
          status = "输入未发送，请检查连接";
          changed();
        }
      });
    }
  }

  public void resize(int cols, int rows) {
    if (connection != null) connection.resize(cols, rows);
  }

  public void modifiersChanged(int control, int alt) {
    if (ui != null) ui.modifiersChanged(control, alt);
  }

  public void selectionChanged(boolean selected) {
    if (ui != null) ui.selectionChanged(selected);
  }

  public void fontStep(int step) {
    if (ui != null) ui.fontStep(step);
  }

  public void failed() {
    disconnect("终端组件已停止，请重新打开终端");
    if (ui != null) ui.failed();
  }
}
