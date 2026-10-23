package net.aftergit.merge.mcp;

import java.security.SecureRandom;
import java.util.Base64;

/** Where the bearer token lives (agents design §4); it exists only while agents are allowed. */
public interface TokenStore {
  /** The token, or null. */
  String get();

  /** A new token replacing any earlier one. */
  String create();

  /** No token any more. */
  void revoke();

  /** 32 random bytes, Base64URL without padding (43 characters). */
  static String newToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A store in memory only (tests). */
  static TokenStore memory() {
    return new TokenStore() {
      private String token;

      @Override
      public synchronized String get() {
        return token;
      }

      @Override
      public synchronized String create() {
        token = newToken();
        return token;
      }

      @Override
      public synchronized void revoke() {
        token = null;
      }
    };
  }
}
