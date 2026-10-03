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

  default void leave() {}

  default void refresh() {}

  interface Host {
    Activity activity();
    ServerProfile serverProfile();
    void configure(ServerProfile profile);
    ExecutorService executor();
    String activeFeature();
    void navigate(String id);
    void redraw();
    void status(String message);
  }
}
