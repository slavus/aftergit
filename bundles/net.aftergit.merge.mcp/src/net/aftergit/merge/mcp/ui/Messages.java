package net.aftergit.merge.mcp.ui;

import org.eclipse.osgi.util.NLS;

/** Externalized strings of the AI agents' preference pages. */
public final class Messages extends NLS {
  private static final String BUNDLE_NAME = "net.aftergit.merge.mcp.ui.messages";

  public static String description;
  public static String allow;
  public static String port;
  public static String badPort;
  public static String running;
  public static String stopped;
  public static String failed;
  public static String client;
  public static String copyConfiguration;
  public static String regenerateToken;
  public static String deleteProposals;
  public static String copied;
  public static String regenerated;
  public static String regenerateFailed;
  public static String deleteTitle;
  public static String deleteQuestion;
  public static String deleted;
  public static String deleteFailed;

  static {
    NLS.initializeMessages(BUNDLE_NAME, Messages.class);
  }

  private Messages() {}
}
