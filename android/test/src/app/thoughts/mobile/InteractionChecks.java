package app.thoughts.mobile;

import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import app.thoughts.mobile.modules.terminal.TerminalChecks;
import app.thoughts.mobile.modules.thoughts.Store;
import org.json.JSONArray;

/** Exercise the visible dialogs/buttons, not just Store methods. Uses only disposable test data. */
public final class InteractionChecks {

  public static View find(View view, String label) {
    if (
      label.equals(view.getContentDescription()) ||
      (view instanceof TextView &&
        label.contentEquals(((TextView) view).getText()))
    ) return view;
    if (view instanceof ViewGroup) for (
      int i = 0;
      i < ((ViewGroup) view).getChildCount();
      i++
    ) {
      View result = find(((ViewGroup) view).getChildAt(i), label);
      if (result != null) return result;
    }
    return null;
  }

  static AccessibilityNodeInfo node(Instrumentation test, String label)
    throws Exception {
    long until = System.nanoTime() + 5_000_000_000L;
    do {
      AccessibilityNodeInfo root = test
        .getUiAutomation()
        .getRootInActiveWindow();
      if (
        root != null
      ) for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(
        label
      ))
        if (label.contentEquals(n.getText())) return n;
      Thread.sleep(50);
    } while (System.nanoTime() < until);
    throw new AssertionError("Missing visible action: " + label);
  }

  static void click(Instrumentation test, String label) throws Exception {
    AccessibilityNodeInfo n = node(test, label);
    while (n != null && !n.isClickable()) n = n.getParent();
    TerminalChecks.check(
      n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK),
      "Action did not click: " + label
    );
    test.waitForIdleSync();
    Thread.sleep(150);
  }

  static void run(Instrumentation test, MainActivity screen) throws Exception {
    Store store = screen.store();
    test.runOnMainSync(() -> {
      screen.navigate("settings");
      screen.account().clear();
      screen.setAutomaticSync(false);
      test
        .getTargetContext()
        .getSharedPreferences("draft", 0)
        .edit()
        .clear()
        .commit();
    });
    store.clear();
    store.save(null, "Offline interaction", new JSONArray(), 0);
    test.runOnMainSync(() -> {
      screen.navigate("notes");
      (
        (View) find(
          screen.getWindow().getDecorView(),
          "Offline interaction"
        ).getParent()
      ).performLongClick();
    });
    click(test, "编辑");
    test.runOnMainSync(() -> {
      ((EditText) find(screen.getWindow().getDecorView(), "想法内容")).setText(
        "Offline edited"
      );
      find(screen.getWindow().getDecorView(), "保存").performClick();
    });
    TerminalChecks.check(
      screen.activeFeature().equals("thoughts"),
      "Saving an edit must return to the list"
    );
    TerminalChecks.check(
      store.entries().size() == 1 &&
        store
          .entries()
          .get(0)
          .note.getString("content")
          .equals("Offline edited"),
      "Edit must update the original record"
    );
    test.runOnMainSync(() ->
      (
        (View) find(
          screen.getWindow().getDecorView(),
          "Offline edited"
        ).getParent()
      ).performLongClick()
    );
    click(test, "移到回收站");
    click(test, "确定");
    TerminalChecks.check(
      "local-trash".equals(store.entries().get(0).pending),
      "Offline delete must work through the UI"
    );
    test.runOnMainSync(() ->
      find(screen.getWindow().getDecorView(), "回收站").performClick()
    );
    test.runOnMainSync(() ->
      (
        (View) find(
          screen.getWindow().getDecorView(),
          "Offline edited"
        ).getParent()
      ).performLongClick()
    );
    click(test, "恢复");
    click(test, "确定");
    TerminalChecks.check(
      "create".equals(store.entries().get(0).pending),
      "Offline restore must work through the UI"
    );
    test.runOnMainSync(() -> {
      screen.navigate("capture");
      ((EditText) find(screen.getWindow().getDecorView(), "想法内容")).setText(
        "Keep this unsent draft"
      );
      screen.navigate("settings");
      screen.disconnect();
    });
    TerminalChecks.check(
      !node(test, "清除").isEnabled(),
      "Clearing unsynced data must require explicit acknowledgement"
    );
    click(test, "取消");
    TerminalChecks.check(
      store.entries().size() == 1 &&
        test
          .getTargetContext()
          .getSharedPreferences("draft", 0)
          .getString("content", "")
          .equals("Keep this unsent draft"),
      "Cancelling clear must preserve records and drafts"
    );
    test.runOnMainSync(screen::disconnect);
    click(test, "我确认清除尚未同步的本机内容");
    TerminalChecks.check(
      node(test, "清除").isEnabled(),
      "Acknowledgement must provide a working clear path"
    );
    click(test, "清除");
    TerminalChecks.check(
      store.entries().isEmpty() &&
        test
          .getTargetContext()
          .getSharedPreferences("draft", 0)
          .getString("content", "")
          .isEmpty(),
      "Confirmed clear must clear the data without a stale editor restoring it"
    );
  }
}
