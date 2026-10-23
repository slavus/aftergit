package net.aftergit.merge.mcp.service;

/** What the agent asked cannot be done; the message tells it why (never file contents). */
public final class AgentException extends Exception {
  private static final long serialVersionUID = 1L;

  public AgentException(String message) {
    super(message);
  }
}
