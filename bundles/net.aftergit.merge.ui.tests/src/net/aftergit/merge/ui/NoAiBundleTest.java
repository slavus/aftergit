package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

/**
 * Resolve with AI design §1 and §6: this UI runtime has no net.aftergit.merge.ai, so the whole UI suite proves that
 * AfterGit works without it; the only AI resolver here is the stand-in of the test fragment, not ready by default.
 */
class NoAiBundleTest {
  @Test
  void theUiTestRuntimeHasNoAiBundle() {
    assertNull(Platform.getBundle("net.aftergit.merge.ai"));
  }

  @Test
  void withoutItNoResolverIsReady() {
    StandInAiResolver.reset();
    assertTrue(AiResolverContributions.ready().isEmpty());
    assertTrue(AiResolverContributions.all().stream().allMatch(c -> c.id().equals(StandInAiResolver.ID)),
        "only the stand-in of the test fragment");
  }

  @Test
  void withoutItThereIsNoResolveWithAiPage() {
    assertNull(PlatformUI.getWorkbench().getPreferenceManager()
        .find("net.aftergit.merge.preferences/net.aftergit.merge.ai.page"));
  }
}
