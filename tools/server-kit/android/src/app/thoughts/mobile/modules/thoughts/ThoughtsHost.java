package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.Feature;

/** Services requested only by thoughts and its current service/settings integration. */
public interface ThoughtsHost extends Feature.Host {
  public Store store();
  public Account account();
  public Api api();
  public void sync();
  public boolean automaticSync();
  public void setAutomaticSync(boolean enabled);
  public void autoSync();
  public void export(boolean remote);
  public void disconnect();
}
