package app.thoughts.mobile.core.connection;

/** An actionable connection or remote service failure shared by transports. */
public final class ConnectionFailure extends Exception {

  public final int code;

  public ConnectionFailure(int code, String message) {
    super(message);
    this.code = code;
  }
}
