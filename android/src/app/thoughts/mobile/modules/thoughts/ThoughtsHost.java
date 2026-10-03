package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.Feature;

/** Data and synchronization actions owned by the thoughts module. */
public interface ThoughtsHost extends Feature.Host {
  public Store store();
  public void sync();
  public boolean automaticSync();
  public void setAutomaticSync(boolean enabled);
  public void autoSync();
  public void export(boolean remote);
  public void disconnect();
}
