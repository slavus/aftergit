package net.aftergit.merge.ai;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import net.aftergit.merge.core.ai.AiException;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IProviderHints;

/**
 * The API keys in Eclipse secure storage, encrypted (design §5), opened only when a key is needed. As the MCP token
 * of AI Agents: a password prompt only inside {@link #allowPromptWhile} on the same thread (an explicit action on the
 * preference page); a request in the background never waits for a dialog (Ruling 14 of plan 6b).
 */
public final class SecureKeyStore implements KeyStore {
  static final String NODE = "net.aftergit.merge.ai";
  private static final String LOCKED = "The API key could not be read from Eclipse secure storage; open "
      + "Preferences > AfterGit > Resolve with AI and press Apply";
  private static final ThreadLocal<int[]> PROMPTING = ThreadLocal.withInitial(() -> new int[1]);
  private final Supplier<ISecurePreferences> root;

  /** {@code root}: e.g. {@code SecureKeyStore::openDefault}; asked only when a key is used. */
  public SecureKeyStore(Supplier<ISecurePreferences> root) {
    this.root = root;
  }

  /** The key's name in the node: "anthropic.key", "openai.key". */
  static String keyOf(Provider p) {
    return p.key() + ".key";
  }

  /** The default secure storage, opened so that it prompts only inside {@link #allowPromptWhile}. */
  public static ISecurePreferences openDefault() {
    if (promptAllowed()) {
      return SecurePreferencesFactory.getDefault();
    }
    try {
      return SecurePreferencesFactory.open(null, Map.of(IProviderHints.PROMPT_USER, Boolean.FALSE));
    } catch (IOException e) {
      AiLog.warning("Eclipse secure storage could not be opened for the API key: " + e.getMessage());
      return null;
    }
  }

  /** Runs {@code action} so that secure storage may ask for its password, on this thread only. */
  public static void allowPromptWhile(Runnable action) {
    int[] depth = PROMPTING.get();
    depth[0]++;
    try {
      action.run();
    } finally {
      depth[0]--;
    }
  }

  public static boolean promptAllowed() {
    return PROMPTING.get()[0] > 0;
  }

  @Override
  public String get(Provider p) throws AiException {
    ISecurePreferences r = root.get();
    if (r == null) {
      throw new AiException(LOCKED);
    }
    try {
      if (!r.nodeExists(NODE)) {
        return null;
      }
      String key = r.node(NODE).get(keyOf(p), null);
      return key == null || key.isBlank() ? null : key;
    } catch (StorageException e) {
      throw new AiException(LOCKED, e); // the message of the storage, never the key
    }
  }

  @Override
  public void set(Provider p, String key) throws AiException {
    String k = KeyStore.checked(key);
    if (k == null) {
      remove(p);
      return;
    }
    ISecurePreferences r = root.get();
    if (r == null) {
      throw new AiException("Eclipse secure storage is not available for the API key");
    }
    try {
      r.node(NODE).put(keyOf(p), k, true);
      r.flush();
    } catch (StorageException | IOException e) {
      throw new AiException("The API key could not be kept in Eclipse secure storage: " + e.getMessage(), e);
    }
  }

  @Override
  public void remove(Provider p) throws AiException {
    ISecurePreferences r = root.get();
    if (r == null) {
      throw new AiException("Eclipse secure storage is not available, so the API key could not be removed");
    }
    if (!r.nodeExists(NODE)) {
      return;
    }
    r.node(NODE).remove(keyOf(p));
    try {
      r.flush();
    } catch (IOException e) {
      throw new AiException("Eclipse secure storage could not be saved after the key was removed: "
          + e.getMessage(), e);
    }
  }
}
