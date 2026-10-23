package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Back to EGit from AfterGit (docs/INSTALL.md, "Back to EGit"): ⋯ More › Open in EGit Merge Tool. */
class OpenInEGitTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  String mergeModeBefore;
  final List<String> asked = new ArrayList<>();

  String autoShareBefore;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    // projects stay unshared unless a test shares them (EGit would share projects of repositories it knows)
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_CORE);
    autoShareBefore = core.get(MergeToolCommandTest.EGIT_AUTO_SHARE, null);
    core.putBoolean(MergeToolCommandTest.EGIT_AUTO_SHARE, false);
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_UI);
    mergeModeBefore = egit.get(MergeToolCommandTest.EGIT_MERGE_MODE, null);
    egit.putInt(MergeToolCommandTest.EGIT_MERGE_MODE, 1);
  }

  @AfterEach
  void tearDown() throws Exception {
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_CORE);
    if (autoShareBefore == null) {
      core.remove(MergeToolCommandTest.EGIT_AUTO_SHARE);
    } else {
      core.put(MergeToolCommandTest.EGIT_AUTO_SHARE, autoShareBefore);
    }
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_UI);
    if (mergeModeBefore == null) {
      egit.remove(MergeToolCommandTest.EGIT_MERGE_MODE);
    } else {
      egit.put(MergeToolCommandTest.EGIT_MERGE_MODE, mergeModeBefore);
    }
    repos.dispose();
  }

  MergeEditorInput open(String project, OpenInEGitDecision.Choice answer) throws Exception {
    IFile file = repos.sharedConflict(project);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.setOpenInEGitDecision(name -> {
      asked.add(name);
      return answer;
    });
    return input;
  }

  private boolean conflicted(IFile file) throws Exception {
    var rf = RepositoryLocator.locate(file).orElseThrow();
    return ConflictState.isConflicted(rf.repository(), rf.path());
  }

  @Test
  void theMoreMenuOffersEGitsMergeTool() throws Exception {
    MergeEditorInput input = open("egit-menu", OpenInEGitDecision.Choice.CANCEL);
    assertTrue(AgentVisualsTest.moreItems(input.viewer()).contains(Messages.openInEGit));
  }

  @Test
  void withoutChangesTheResolverClosesAndEGitOpensWithoutAsking() throws Exception {
    MergeEditorInput input = open("egit-clean", OpenInEGitDecision.Choice.CANCEL);
    IFile file = input.file();
    UiDriver.clickMore(input.viewer(), Messages.openInEGit);
    ConflictRepos.waitUntil(() -> !MergeToolCommandTest.afterGitOpen(file) && MergeToolCommandTest.egitOpen(), 20,
        "the resolver to close and EGit's merge editor to open");
    assertEquals(List.of(), asked);
  }

  @Test
  void cancelKeepsTheResolverOpenWithItsChanges() throws Exception {
    MergeEditorInput input = open("egit-cancel", OpenInEGitDecision.Choice.CANCEL);
    IFile file = input.file();
    input.viewer().nextConflict();
    input.viewer().takeRight();
    UiDriver.clickMore(input.viewer(), Messages.openInEGit);
    UiDriver.flush();
    assertEquals(List.of("a.txt"), asked);
    assertTrue(MergeToolCommandTest.afterGitOpen(file));
    assertTrue(input.isDirty());
    assertFalse(MergeToolCommandTest.egitOpen());
  }

  @Test
  void discardLeavesTheFileAsItWasAndOpensEGit() throws Exception {
    MergeEditorInput input = open("egit-discard", OpenInEGitDecision.Choice.DISCARD_AND_OPEN);
    IFile file = input.file();
    String before = repos.read("a.txt");
    input.viewer().nextConflict();
    input.viewer().takeRight();
    UiDriver.clickMore(input.viewer(), Messages.openInEGit);
    ConflictRepos.waitUntil(() -> !MergeToolCommandTest.afterGitOpen(file) && MergeToolCommandTest.egitOpen(), 20,
        "the resolver to close and EGit's merge editor to open");
    assertEquals(before, repos.read("a.txt"));
    assertTrue(conflicted(file));
  }

  @Test
  void saveWritesTheWorkingTreeFileWithoutStagingAndOpensEGit() throws Exception {
    MergeEditorInput input = open("egit-save", OpenInEGitDecision.Choice.SAVE_AND_OPEN);
    IFile file = input.file();
    input.viewer().nextConflict();
    input.viewer().takeRight();
    UiDriver.clickMore(input.viewer(), Messages.openInEGit);
    ConflictRepos.waitUntil(() -> !MergeToolCommandTest.afterGitOpen(file) && MergeToolCommandTest.egitOpen(), 20,
        "the resolver to close and EGit's merge editor to open");
    assertEquals("a\nR\nc\n", repos.read("a.txt"));
    assertTrue(conflicted(file), "saved, not staged");
  }

  /** A project EGit does not manage: EGit's Merge Tool refuses the file, AfterGit resolves it all the same. */
  MergeEditorInput openUnshared(String project, OpenInEGitDecision.Choice answer, List<String> reported)
      throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject(project, "a.txt");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.setOpenInEGitDecision(name -> {
      asked.add(name);
      return answer;
    });
    input.setTitledErrorReporter((title, status) -> reported.add(title + ": " + status.getMessage()));
    return input;
  }

  @Test
  void whenEGitCannotTakeTheFileDiscardDropsNothingAndTheResolverStaysOpen() throws Exception {
    List<String> reported = new ArrayList<>();
    MergeEditorInput input = openUnshared("egit-refuses", OpenInEGitDecision.Choice.DISCARD_AND_OPEN, reported);
    IFile file = input.file();
    String before = repos.read("a.txt");
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertFalse(input.openInEGitMergeTool(new org.eclipse.core.runtime.NullProgressMonitor()));
    UiDriver.flush();
    long until = System.nanoTime() + 1_000_000_000L;
    ConflictRepos.waitUntil(() -> System.nanoTime() > until, 5, "a moment");
    assertTrue(MergeToolCommandTest.afterGitOpen(file), "the resolver stays open");
    assertTrue(input.isDirty(), "with its changes");
    assertEquals(before, repos.read("a.txt"));
    assertFalse(MergeToolCommandTest.egitOpen());
    assertEquals(List.of(Messages.openInEGitTitle + ": " + org.eclipse.osgi.util.NLS.bind(
        Messages.openInEGitRefused, "a.txt")), reported);
  }

  @Test
  void whenEGitCannotTakeTheFileACleanResolverStaysOpenToo() throws Exception {
    List<String> reported = new ArrayList<>();
    MergeEditorInput input = openUnshared("egit-refuses-clean", OpenInEGitDecision.Choice.CANCEL, reported);
    IFile file = input.file();
    assertFalse(input.openInEGitMergeTool(new org.eclipse.core.runtime.NullProgressMonitor()));
    long until = System.nanoTime() + 1_000_000_000L;
    ConflictRepos.waitUntil(() -> System.nanoTime() > until, 5, "a moment");
    assertTrue(MergeToolCommandTest.afterGitOpen(file));
    assertEquals(1, reported.size(), reported.toString());
    assertEquals(List.of(), asked, "nothing to ask when EGit cannot open the file anyway");
  }

  @Test
  void theMoreMenuDoesNotOfferEGitForAProjectEGitDoesNotManage() throws Exception {
    MergeEditorInput input = openUnshared("egit-no-entry", OpenInEGitDecision.Choice.CANCEL, new ArrayList<>());
    assertFalse(AgentVisualsTest.moreItems(input.viewer()).contains(Messages.openInEGit));
  }
}
