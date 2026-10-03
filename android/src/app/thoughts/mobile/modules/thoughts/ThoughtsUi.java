package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.Ui;
import app.thoughts.mobile.core.connection.DeviceAccount;

/** Shared thoughts UI services; the generic Ui layer has no note storage dependency. */
public class ThoughtsUi extends Ui {

  protected final ThoughtsHost host;
  protected final Store store;
  protected final DeviceAccount account;

  protected ThoughtsUi(ThoughtsHost host) {
    super(host);
    this.host = host;
    store = host.store();
    account = host.account();
  }

  protected void sync() {
    host.sync();
  }
}
