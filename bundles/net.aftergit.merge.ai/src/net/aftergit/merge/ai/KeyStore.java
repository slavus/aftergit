package net.aftergit.merge.ai;

import java.util.EnumMap;
import java.util.Map;
import net.aftergit.merge.core.ai.AiException;

/** Where the API keys live (design §5): Eclipse secure storage, one key per provider. */
public interface KeyStore {
  /** Why a key is refused; never holds the key itself. */
  String INVALID = "The API key may only hold visible ASCII characters";

  /** The key of {@code p}, or null when none is set. */
  String get(Provider p) throws AiException;

  /**
   * Keeps {@code key} for {@code p}, its ends trimmed; a blank key removes it. A key with any other character than
   * visible ASCII is refused with {@link #INVALID}: it could not be sent in a header, and the failure would show it.
   */
  void set(Provider p, String key) throws AiException;

  void remove(Provider p) throws AiException;

  /** A store in memory only (tests). */
  static KeyStore memory() {
    return new KeyStore() {
      private final Map<Provider, String> keys = new EnumMap<>(Provider.class);

      @Override
      public synchronized String get(Provider p) {
        return keys.get(p);
      }

      @Override
      public synchronized void set(Provider p, String key) throws AiException {
        String k = checked(key);
        if (k == null) {
          keys.remove(p);
        } else {
          keys.put(p, k);
        }
      }

      @Override
      public synchronized void remove(Provider p) {
        keys.remove(p);
      }
    };
  }

  /** {@code key} with its ends trimmed, null when blank; refused with {@link #INVALID} past visible ASCII. */
  static String checked(String key) throws AiException {
    if (key == null || key.isBlank()) {
      return null;
    }
    String k = key.strip();
    for (int i = 0; i < k.length(); i++) {
      char c = k.charAt(i);
      if (c < 0x21 || c > 0x7E) {
        throw new AiException(INVALID);
      }
    }
    return k;
  }
}
