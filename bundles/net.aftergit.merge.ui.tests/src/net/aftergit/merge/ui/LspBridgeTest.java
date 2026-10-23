package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Languages design: the optional bundle is wired here, and the runtime has the languages of the target Eclipse. */
class LspBridgeTest {
  @AfterEach
  void tearDown() {
    LspBridge.setAvailableForTests(null);
  }

  @Test
  void theLanguagesBundleIsWired() {
    assertNotNull(Platform.getBundle("net.aftergit.merge.lsp"));
    assertTrue(LspBridge.available());
    LspBridge.setAvailableForTests(false);
    assertFalse(LspBridge.available());
  }

  @Test
  void theRuntimeHasWildWebDeveloperAndLemMinX() {
    assertNotNull(Platform.getBundle("org.eclipse.wildwebdeveloper"));
    assertNotNull(Platform.getBundle("org.eclipse.wildwebdeveloper.xml"));
    assertNotNull(Platform.getBundle("org.eclipse.tm4e.language_pack"));
  }
}
