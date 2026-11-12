package ua.kpi.pocw.net;

/** Protocol or application error that maps directly to an HTTP status code. */
public final class HttpException extends RuntimeException {
  private final int status;

  public HttpException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
