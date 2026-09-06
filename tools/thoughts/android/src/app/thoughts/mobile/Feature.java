package app.thoughts.mobile;

import android.app.Activity;
import android.widget.LinearLayout;
import java.util.concurrent.ExecutorService;

/** Each feature owns its view and lifecycle; the Activity owns navigation and shared services. */
interface Feature {
  String id();
  String label();

  default String title() {
    return label();
  }

  void render(LinearLayout surface);

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
    Store store();
    Account account();
    Api api();
    void configure(ServerProfile profile);
    ExecutorService executor();
    String activeFeature();
    void navigate(String id);
    void redraw();
    void status(String message);
    void sync();
    boolean automaticSync();
    void setAutomaticSync(boolean enabled);
    void autoSync();
    void export(boolean remote);
    void disconnect();
  }
}
