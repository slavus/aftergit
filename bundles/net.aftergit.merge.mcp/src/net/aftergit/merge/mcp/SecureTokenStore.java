package net.aftergit.merge.mcp;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IProviderHints;

/** The token in Eclipse secure storage, encrypted (agents design §4). Opened only when the token is needed. */
public final class SecureTokenStore implements TokenStore {
  static final String NODE = "net.aftergit.merge.mcp";
  static final String KEY = "token";
  /** How many explicit user actions on this thread allow a password prompt now (see {@link #allowPromptWhile}). */
  private static final ThreadLocal<int[]> PROMPTING = ThreadLocal.withInitial(() -> new int[1]);
  private final Supplier<ISecurePreferences> root;

  /** {@code root}: e.g. {@code SecurePreferencesFactory::getDefault}; asked only when the token is used. */
  public SecureTokenStore(Supplier<ISecurePreferences> root) {
    this.root = root;
  }

  /**
   * The default Eclipse secure storage, opened so that it asks the user for a password only inside
   * {@link #allowPromptWhile} on the same thread (by default never): a locked storage then fails, and the server
   * reports it through {@code AgentServer.failure()} instead of waiting for a dialog.
   */
  public static ISecurePreferences openDefault() {
    if (promptAllowed()) {
      return SecurePreferencesFactory.getDefault();
    }
    try {
      return SecurePreferencesFactory.open(null, Map.of(IProviderHints.PROMPT_USER, Boolean.FALSE));
    } catch (IOException e) {
      ILog.of(SecureTokenStore.class).log(Status.warning("Eclipse secure storage could not be opened for the MCP "
          + "token: " + e.getMessage()));
      return null;
    }
  }

  /**
   * Runs {@code action} so that secure storage may ask for its password while it runs, on this thread only. Only an
   * explicit user action on the UI thread may call it (OK or Apply on the preference page): startup, preference
   * events and the HTTP threads never prompt, so nothing waits for a dialog nobody asked for.
   */
  public static void allowPromptWhile(Runnable action) {
    int[] depth = PROMPTING.get();
    depth[0]++;
    try {
      action.run();
    } finally {
      depth[0]--;
    }
  }

  /** True inside {@link #allowPromptWhile} on this thread. */
  public static boolean promptAllowed() {
    return PROMPTING.get()[0] > 0;
  }

  @Override
  public String get() {
    try {
      ISecurePreferences r = root.get();
      return r == null || !r.nodeExists(NODE) ? null : r.node(NODE).get(KEY, null);
    } catch (StorageException e) {
      ILog.of(SecureTokenStore.class).log(Status.warning("The MCP token could not be read from Eclipse secure "
          + "storage: " + e.getMessage()));
      return null;
    }
  }

  @Override
  public String create() {
    String token = TokenStore.newToken();
    ISecurePreferences r = root.get();
    if (r == null) {
      throw new IllegalStateException("Eclipse secure storage is not available for the MCP token");
    }
    try {
      r.node(NODE).put(KEY, token, true);
      r.flush();
    } catch (StorageException | IOException e) {
      throw new IllegalStateException("The MCP token could not be kept in Eclipse secure storage: " + e.getMessage(),
          e);
    }
    return token;
  }

  @Override
  public void revoke() {
    ISecurePreferences r = root.get();
    if (r == null || !r.nodeExists(NODE)) {
      return;
    }
    r.node(NODE).remove(KEY);
    try {
      r.flush();
    } catch (IOException e) {
      ILog.of(SecureTokenStore.class).log(Status.warning("Eclipse secure storage could not be saved after the MCP "
          + "token was removed: " + e.getMessage()));
    }
  }
}
