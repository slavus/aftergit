package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Map;
import javax.crypto.spec.PBEKeySpec;
import net.aftergit.merge.core.ai.AiException;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve with AI design §5: the API key lives in Eclipse secure storage, encrypted, one per provider. */
class SecureKeyStoreTest {
  @TempDir
  File dir;
  ISecurePreferences root;
  SecureKeyStore store;

  @BeforeEach
  void open() throws Exception {
    root = SecurePreferencesFactory.open(new File(dir, "secure_storage").toURI().toURL(), Map.of(
        IProviderHints.PROMPT_USER, Boolean.FALSE,
        IProviderHints.DEFAULT_PASSWORD, new PBEKeySpec("test-password".toCharArray())));
    store = new SecureKeyStore(() -> root);
  }

  @Test
  void thereIsNoKeyUntilOneIsSet() throws AiException {
    assertNull(store.get(Provider.ANTHROPIC));
  }

  @Test
  void aKeyIsKeptEncryptedPerProvider() throws Exception {
    store.set(Provider.ANTHROPIC, "test-key-a");
    store.set(Provider.OPENAI_COMPATIBLE, "test-key-o");
    assertEquals("test-key-a", store.get(Provider.ANTHROPIC));
    assertEquals("test-key-o", store.get(Provider.OPENAI_COMPATIBLE));
    assertTrue(root.node(SecureKeyStore.NODE).isEncrypted(SecureKeyStore.keyOf(Provider.ANTHROPIC)));
    store.remove(Provider.ANTHROPIC);
    assertNull(store.get(Provider.ANTHROPIC));
    assertEquals("test-key-o", store.get(Provider.OPENAI_COMPATIBLE));
    store.remove(Provider.ANTHROPIC); // nothing left: no failure
  }

  @Test
  void withoutSecureStorageReadingFailsWithAMessageThatNamesThePage() {
    SecureKeyStore none = new SecureKeyStore(() -> null);
    AiException e = assertThrows(AiException.class, () -> none.get(Provider.ANTHROPIC));
    assertTrue(e.getMessage().contains("Preferences > AfterGit > Resolve with AI"), e.getMessage());
    assertThrows(AiException.class, () -> none.set(Provider.ANTHROPIC, "test-key"));
  }

  @Test
  void withoutSecureStorageRemovingFailsToo() {
    assertThrows(AiException.class, () -> new SecureKeyStore(() -> null).remove(Provider.ANTHROPIC));
  }

  @Test
  void aKeyWithAnInvisibleOrNonAsciiCharacterIsRefusedWithoutEchoingIt() throws Exception {
    for (KeyStore s : new KeyStore[] {store, KeyStore.memory()}) {
      for (String bad : new String[] {"test\nkey", "test\r\nkey", "test key", "test\tkey", "test\u0000key",
          "tëst-key", "test key"}) {
        AiException e = assertThrows(AiException.class, () -> s.set(Provider.ANTHROPIC, bad), bad);
        assertEquals(KeyStore.INVALID, e.getMessage());
        assertFalse(e.getMessage().contains("test"), e.getMessage());
        assertNull(s.get(Provider.ANTHROPIC), "nothing kept");
      }
      s.set(Provider.ANTHROPIC, " \ttest-key~!\r\n");
      assertEquals("test-key~!", s.get(Provider.ANTHROPIC), "the ends are trimmed");
    }
  }

  @Test
  void anEmptyKeyIsNoKey() throws Exception {
    store.set(Provider.OPENAI_COMPATIBLE, "  ");
    assertNull(store.get(Provider.OPENAI_COMPATIBLE));
  }

  @Test
  void promptsAreAllowedOnlyDuringAnExplicitActionOnItsThread() throws Exception {
    assertFalse(SecureKeyStore.promptAllowed());
    boolean[] seen = new boolean[2];
    SecureKeyStore.allowPromptWhile(() -> {
      seen[0] = SecureKeyStore.promptAllowed();
      seen[1] = java.util.concurrent.CompletableFuture.supplyAsync(SecureKeyStore::promptAllowed).join();
    });
    assertTrue(seen[0]);
    assertFalse(seen[1]);
    assertFalse(SecureKeyStore.promptAllowed());
  }

  @Test
  void theMemoryStoreForTestsBehavesTheSame() throws Exception {
    KeyStore m = KeyStore.memory();
    assertNull(m.get(Provider.ANTHROPIC));
    m.set(Provider.ANTHROPIC, "test-key");
    assertEquals("test-key", m.get(Provider.ANTHROPIC));
    m.remove(Provider.ANTHROPIC);
    assertNull(m.get(Provider.ANTHROPIC));
  }
}
