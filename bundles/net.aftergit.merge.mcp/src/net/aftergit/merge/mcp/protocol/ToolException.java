package net.aftergit.merge.mcp.protocol;

/** A tool cannot do what it was asked; the message tells the agent why (never file contents). */
public final class ToolException extends Exception {
  private static final long serialVersionUID = 1L;

  public ToolException(String message) {
    super(message);
  }
}
