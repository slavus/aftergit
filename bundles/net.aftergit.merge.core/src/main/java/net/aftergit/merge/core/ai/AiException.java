package net.aftergit.merge.core.ai;

/**
 * No resolution, with a message for the user (shown as it is, without a stack trace; design §2). What goes into the
 * log may be shorter ({@link #logMessage()}: never a server's own words, which may quote the conflict), and a
 * failure before anything left this machine says so ({@link #sent()}).
 */
public class AiException extends Exception {
  private static final long serialVersionUID = 1L;

  private final String logMessage;
  private final boolean sent;

  public AiException(String message) {
    this(message, null, true);
  }

  public AiException(String message, Throwable cause) {
    super(message == null ? "" : message, cause);
    this.logMessage = getMessage();
    this.sent = true;
  }

  /**
   * {@code message} for the user, {@code logMessage} for the log (the message when null); {@code sent} false when
   * the request never left this machine.
   */
  public AiException(String message, String logMessage, boolean sent) {
    super(message == null ? "" : message);
    this.logMessage = logMessage == null ? getMessage() : logMessage;
    this.sent = sent;
  }

  /** A request refused before it was sent: nothing left this machine. */
  public static AiException notSent(String message) {
    return new AiException(message, null, false);
  }

  /** What the log says of this failure. */
  public String logMessage() {
    return logMessage;
  }

  /** False when the request was refused before it was sent; true when it was, or when that is not known. */
  public boolean sent() {
    return sent;
  }
}
