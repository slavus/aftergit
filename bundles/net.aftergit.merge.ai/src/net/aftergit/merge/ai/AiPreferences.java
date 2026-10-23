package net.aftergit.merge.ai;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Preferences > AfterGit > Resolve with AI in the workspace preferences (design §4): one switch, off by default; the
 * chosen provider; endpoint, model and "a key is set" per provider (Ruling 13); context lines and timeout.
 */
public final class AiPreferences {
  public static final String NODE = "net.aftergit.merge.ai";
  static final String ENABLED = "enabled";
  static final String PROVIDER = "provider";
  static final String ENDPOINT = ".endpoint";
  static final String MODEL = ".model";
  static final String KEY_SET = ".keySet";
  static final String CONTEXT_LINES = "contextLines";
  static final String TIMEOUT = "timeoutSeconds";

  private AiPreferences() {}

  private static IEclipsePreferences node() {
    return InstanceScope.INSTANCE.getNode(NODE);
  }

  /** The settings of the chosen provider. */
  public static Settings settings() {
    return settings(Provider.of(node().get(PROVIDER, Provider.ANTHROPIC.key())));
  }

  /** The settings of {@code p}, chosen or not. */
  public static Settings settings(Provider p) {
    IEclipsePreferences n = node();
    return new Settings(n.getBoolean(ENABLED, false), p, n.get(p.key() + ENDPOINT, p.defaultEndpoint()),
        n.get(p.key() + MODEL, p.defaultModel()), n.getBoolean(p.key() + KEY_SET, false),
        n.getInt(CONTEXT_LINES, Settings.DEFAULT_CONTEXT_LINES),
        n.getInt(TIMEOUT, Settings.DEFAULT_TIMEOUT_SECONDS));
  }

  /** Saves {@code s} and makes its provider the chosen one; "a key is set" is kept by {@link #setKeySet}. */
  public static void save(Settings s) {
    IEclipsePreferences n = node();
    n.putBoolean(ENABLED, s.enabled());
    n.put(PROVIDER, s.provider().key());
    n.put(s.provider().key() + ENDPOINT, s.endpoint());
    n.put(s.provider().key() + MODEL, s.model());
    n.putInt(CONTEXT_LINES, s.contextLines());
    n.putInt(TIMEOUT, s.timeoutSeconds());
    flush();
  }

  public static void setKeySet(Provider p, boolean set) {
    node().putBoolean(p.key() + KEY_SET, set);
    flush();
  }

  /** Back to the defaults (tests). */
  public static void reset() {
    try {
      node().clear();
      node().flush();
    } catch (BackingStoreException e) {
      // the in-memory values are cleared anyway
    }
  }

  private static void flush() {
    try {
      node().flush();
    } catch (BackingStoreException e) {
      // the values hold for this session
    }
  }
}
