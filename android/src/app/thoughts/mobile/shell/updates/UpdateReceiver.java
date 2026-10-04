package app.thoughts.mobile.shell.updates;

import android.app.DownloadManager;
import android.content.*;

public final class UpdateReceiver extends BroadcastReceiver {

  public void onReceive(Context context, Intent intent) {
    if (
      !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()) ||
      intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) !=
        UpdateManager.preferences(context).getLong("download_id", -1)
    ) return;
    PendingResult result = goAsync();
    UpdateManager.IO.execute(() -> {
      try {
        UpdateManager.verifyCompleted(context);
      } finally {
        result.finish();
      }
    });
  }
}
