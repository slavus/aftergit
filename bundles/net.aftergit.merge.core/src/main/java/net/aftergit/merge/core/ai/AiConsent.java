package net.aftergit.merge.core.ai;

/**
 * Where the user's "Do not ask again in this workspace" for a remote resolver is kept (resolve-with-ai design §5):
 * one boolean per destination in the workspace preferences of the resolver, so the preference page of a bundle that
 * cannot see the resolver's classes can forget them.
 */
public final class AiConsent {
  /** The preference node (instance scope = the workspace). */
  public static final String NODE = "net.aftergit.merge.ui";
  /** The prefix of each key; the rest is the URL-encoded destination. */
  public static final String PREFIX = "aiRemoteAllowed.";

  private AiConsent() {}
}
