package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Constants;

/**
 * AfterGit works without EGit UI: the resolver bundle does not require it, and while EGit's Merge Tool command is
 * not defined everything built on it stays out of the way (the test runtime has EGit UI, so the command id is
 * replaced by one nobody defines).
 */
class NoEGitUiTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    EGitMergeTool.setCommandForTests("net.aftergit.merge.tests.noSuchCommand");
  }

  @AfterEach
  void tearDown() throws Exception {
    EGitMergeTool.setCommandForTests(null);
    MergePreferences.setUseAsMergeTool(true);
    repos.dispose();
  }

  @Test
  void theResolverBundleRequiresNoEGitBundle() {
    var headers = Platform.getBundle("net.aftergit.merge.ui").getHeaders("");
    for (String h : new String[] {Constants.REQUIRE_BUNDLE, Constants.IMPORT_PACKAGE}) {
      String v = headers.get(h);
      assertFalse(v != null && v.contains("org.eclipse.egit"), h + ": " + v);
    }
  }

  @Test
  void withoutTheCommandNothingOffersOrRunsEGitsMergeTool() throws Exception {
    IFile file = repos.sharedConflict("no-egit-ui");
    assertFalse(EGitMergeTool.available());
    assertFalse(EGitMergeTool.open(file));
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertFalse(AgentVisualsTest.moreItems(input.viewer()).contains(Messages.openInEGit));
    MergePreferences.setUseAsMergeTool(false);
    MergeToolHandler handler = new MergeToolHandler();
    try {
      assertFalse(handler.isEnabled(), "no EGit handler to stand in for");
      assertThrows(ExecutionException.class, () -> handler.execute(new ExecutionEvent()));
    } finally {
      handler.dispose();
    }
  }
}
