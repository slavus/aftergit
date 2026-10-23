package net.aftergit.merge.git.agents;

/** An open resolver could not take a call: it closed, or its UI thread did not answer in time. */
public final class OpenModelException extends Exception {
  private static final long serialVersionUID = 1L;

  public OpenModelException(String message) {
    super(message);
  }
}
