package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.git.agents.ProposalSource;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Agents design §1 and §5: this test runtime runs without the MCP bundle, so the whole UI suite proves that AfterGit
 * works without it. (The runtime with it is net.aftergit.merge.mcp.ui.tests.)
 */
class NoAgentsBundleTest {
  @Test
  void theUiTestRuntimeHasNoAgentsBundle() {
    assertNull(Platform.getBundle("net.aftergit.merge.mcp"));
  }

  @Test
  void withoutItThereIsNoProposalSource() {
    assertNull(FrameworkUtil.getBundle(NoAgentsBundleTest.class).getBundleContext()
        .getServiceReference(ProposalSource.class));
    assertNull(Agents.source());
  }

  @Test
  void withoutItThereIsNoAgentsPreferencePage() {
    assertNull(PlatformUI.getWorkbench().getPreferenceManager()
        .find("net.aftergit.merge.preferences/" + ProposalSource.PREFERENCE_PAGE_ID));
  }
}
