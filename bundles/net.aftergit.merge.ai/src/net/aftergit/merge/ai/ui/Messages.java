package net.aftergit.merge.ai.ui;

import org.eclipse.osgi.util.NLS;

/** Externalized strings of Preferences > AfterGit > Resolve with AI. */
public final class Messages extends NLS {
  private static final String BUNDLE_NAME = "net.aftergit.merge.ai.ui.messages";

  public static String description;
  public static String allow;
  public static String provider;
  public static String providerAnthropic;
  public static String providerOpenAi;
  public static String endpoint;
  public static String model;
  public static String key;
  public static String keyIsSet;
  public static String keyNeeded;
  public static String keyOptional;
  public static String removeKey;
  public static String contextLines;
  public static String timeout;
  public static String testConnection;
  public static String testJob;
  public static String testing;
  public static String testOk;
  public static String testFailed;
  public static String badEndpoint;
  public static String noModel;
  public static String noKey;
  public static String badContextLines;
  public static String badTimeout;
  public static String keyFailed;
  public static String plainHttp;
  public static String consentNote;
  public static String forgetConsents;
  public static String consentsForgotten;
  public static String forgetFailed;

  static {
    NLS.initializeMessages(BUNDLE_NAME, Messages.class);
  }

  private Messages() {}
}
