package app.thoughts.mobile.core;

import android.app.Activity;
import android.widget.LinearLayout;
import app.thoughts.mobile.core.connection.ServerProfile;
import java.util.concurrent.ExecutorService;

/** Each feature owns its view and lifecycle; the Activity owns navigation and shared services. */
public interface Feature {
  public String id();
  public String label();

  default String title() {
    return label();
  }

  public void render(LinearLayout surface);

  default String headerAction() {
    return "";
  }

  default String headerIcon() {
    return "";
  }

  default void performHeaderAction() {}

  default void renderFooter(LinearLayout footer) {}

  default void enter() {}

  default void leave() {}

  default void refresh() {}

  default void resume() {}

  default void pause() {}

  default boolean hasBack() {
    return false;
  }

  default void back() {}

  default void renderSettings(LinearLayout surface) {}

  interface Host {
    Activity activity();
    ServerProfile serverProfile();
    app.thoughts.mobile.core.connection.ServerApi api();
    app.thoughts.mobile.core.connection.DeviceAccount account();
    void authorizationChanged();
    void renderFeatureSettings(LinearLayout surface);
    void configure(ServerProfile profile);
    ExecutorService executor();
    String activeFeature();
    void navigate(String id);
    void redraw();
    void status(String message);
  }
}
