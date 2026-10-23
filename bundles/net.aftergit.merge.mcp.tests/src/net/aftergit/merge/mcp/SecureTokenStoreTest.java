package net.aftergit.merge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.PBEKeySpec;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §4: the token lives in Eclipse secure storage, encrypted, and goes when revoked. */
class SecureTokenStoreTest {
  @TempDir
  File dir;
  ISecurePreferences root;
  SecureTokenStore store;

  @BeforeEach
  void open() throws Exception {
    root = SecurePreferencesFactory.open(new File(dir, "secure_storage").toURI().toURL(), Map.of(
        IProviderHints.PROMPT_USER, Boolean.FALSE,
        IProviderHints.DEFAULT_PASSWORD, new PBEKeySpec("test-password".toCharArray())));
    store = new SecureTokenStore(() -> root);
  }

  @Test
  void thereIsNoTokenUntilOneIsCreated() {
    assertNull(store.get());
  }

  @Test
  void aCreatedTokenIs32RandomBytesKeptEncrypted() throws Exception {
    String t = store.create();
    assertEquals(32, Base64.getUrlDecoder().decode(t).length);
    assertEquals(t, store.get());
    assertTrue(root.node(SecureTokenStore.NODE).isEncrypted(SecureTokenStore.KEY));
    assertNotEquals(t, store.create());
  }

  @Test
  void revokeRemovesTheToken() {
    store.create();
    store.revoke();
    assertNull(store.get());
    store.revoke(); // nothing left: no failure
  }

  @Test
  void withoutSecureStorageThereIsNoTokenAndCreatingFailsClearly() {
    SecureTokenStore none = new SecureTokenStore(() -> null);
    assertNull(none.get());
    IllegalStateException e = assertThrows(IllegalStateException.class, none::create);
    assertTrue(e.getMessage().contains("secure storage"));
  }

  @Test
  void promptsAreAllowedOnlyDuringAnExplicitActionAndOnlyOnItsThread() throws Exception {
    assertFalse(SecureTokenStore.promptAllowed(), "never by default");
    java.util.List<Boolean> seen = new java.util.ArrayList<>();
    SecureTokenStore.allowPromptWhile(() -> {
      seen.add(SecureTokenStore.promptAllowed());
      seen.add(java.util.concurrent.CompletableFuture.supplyAsync(SecureTokenStore::promptAllowed).join());
      SecureTokenStore.allowPromptWhile(() -> seen.add(SecureTokenStore.promptAllowed()));
      seen.add(SecureTokenStore.promptAllowed());
    });
    assertEquals(java.util.List.of(true, false, true, true), seen);
    assertFalse(SecureTokenStore.promptAllowed(), "not after the action");
    assertThrows(IllegalStateException.class, () -> SecureTokenStore.allowPromptWhile(() -> {
      throw new IllegalStateException("failed");
    }));
    assertFalse(SecureTokenStore.promptAllowed(), "not after a failed action");
  }
}
