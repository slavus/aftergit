package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.git.GitIndex;
import net.aftergit.merge.git.RepositoryLocator;
import net.aftergit.merge.git.ResolveAllReport;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/INSTALL.md, "Files that still need attention": files AfterGit saved that still need attention carry a warning marker. */
class AttentionMarkersTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    MergePreferences.setMarkAttention(true);
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 60, "the end of the last run");
    ResolveAllDialog.closeCurrent();
    repos.dispose();
  }

  static List<String> messages(IFile file) {
    try {
      return Arrays.stream(file.findMarkers(AttentionMarkers.TYPE, false, IResource.DEPTH_ZERO))
          .map(m -> m.getAttribute(IMarker.MESSAGE, "")).sorted().toList();
    } catch (CoreException e) {
      return List.of("gone: " + e.getMessage());
    }
  }

  static void waitForMessages(IFile file, List<String> expected) {
    try {
      ConflictRepos.waitUntil(() -> messages(file).equals(expected), 10, "markers " + expected);
    } catch (AssertionError e) {
      assertEquals(expected, messages(file), e.getMessage());
    }
  }

  static String conflicts(int n) {
    return MessageFormat.format(Messages.attentionConflicts, n);
  }

  static String aiBlocks(int n) {
    return MessageFormat.format(Messages.attentionAiNotReviewed, n);
  }

  IFile twoConflicts(String project) throws Exception {
    repos.conflict("a.txt", "a\nb\nc\nd\ne\n", "a\nL1\nc\nL2\ne\n", "a\nR1\nc\nR2\ne\n");
    return repos.plainProject(project, "a.txt");
  }

  @Test
  void theTypeIsAWarningProblemNamedAfterGitAndNotPersistent() throws Exception {
    IExtension ext = Platform.getExtensionRegistry().getExtension("org.eclipse.core.resources.markers",
        AttentionMarkers.TYPE);
    assertNotNull(ext, "declared as " + AttentionMarkers.TYPE);
    assertEquals("AfterGit", ext.getLabel());
    IFile file = twoConflicts("attention-type");
    IMarker m = file.createMarker(AttentionMarkers.TYPE);
    try {
      assertTrue(m.isSubtypeOf(IMarker.PROBLEM));
    } finally {
      m.delete();
    }
  }

  @Test
  void savingWithOpenConflictsMarksTheFileUntilASaveResolvesThem() throws Exception {
    IFile file = twoConflicts("attention-save");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    waitForMessages(file, List.of(conflicts(1)));
    IMarker m = file.findMarkers(AttentionMarkers.TYPE, false, IResource.DEPTH_ZERO)[0];
    assertEquals(IMarker.SEVERITY_WARNING, m.getAttribute(IMarker.SEVERITY, -1));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    waitForMessages(file, List.of());
  }

  @Test
  void twoOpenConflictsAreCountedInThePlural() throws Exception {
    IFile file = twoConflicts("attention-plural");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS,
        SaveDecision.FinishChoice.SAVE_WITH_MARKERS));
    assertEquals(2, input.session().model().unresolvedConflicts());
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    waitForMessages(file, List.of(conflicts(2)));
    assertEquals("2 conflicts not resolved", conflicts(2));
    assertEquals("1 conflict not resolved", conflicts(1));
  }

  @Test
  void stagingTheFileElsewhereRemovesTheConflictsMarker() throws Exception {
    IFile file = twoConflicts("attention-staged");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    waitForMessages(file, List.of(conflicts(1)));
    ConflictRepos.page().closeAllEditors(false);
    var rf = RepositoryLocator.locate(file).orElseThrow();
    GitIndex.markResolved(rf.repository(), rf.path()); // as EGit's Add to Index would
    waitForMessages(file, List.of());
  }

  @Test
  void unreviewedAiBlocksStayMarkedAfterStaging() throws Exception {
    IFile file = twoConflicts("attention-ai");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    var model = input.session().model();
    input.viewer().setAiActive(true);
    assertEquals(net.aftergit.merge.git.agents.Offer.APPLIED, input.viewer().offerAi(new Proposal(
        BlockId.of(model, 0), "L1\nR1\n", "both", "Stand-in model", java.time.Instant.parse("2026-10-16T10:00:00Z")),
        false));
    input.viewer().selectBlock(1);
    input.viewer().takeRight();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    waitForMessages(file, List.of(aiBlocks(1)));
    assertEquals("1 AI-resolved block not reviewed yet", aiBlocks(1));
    assertEquals("2 AI-resolved blocks not reviewed yet", aiBlocks(2));
  }

  @Test
  void switchingTheMarkersOffRemovesThemAndAddsNone() throws Exception {
    IFile file = twoConflicts("attention-off");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    waitForMessages(file, List.of(conflicts(1)));
    IMarker foreign = file.createMarker(IMarker.PROBLEM); // another plug-in's marker stays
    MergePreferences.setMarkAttention(false);
    AttentionMarkers.preferenceChanged();
    waitForMessages(file, List.of());
    assertTrue(foreign.exists());
    input.viewer().takeLeft(); // still one open conflict, as before
    assertTrue(input.saveResult(new NullProgressMonitor()));
    ConflictRepos.waitUntil(() -> org.eclipse.core.runtime.jobs.Job.getJobManager().find(AttentionMarkers.FAMILY)
        .length == 0, 10, "the marker jobs");
    assertEquals(List.of(), messages(file));
  }

  @Test
  void resolveAllMarksWhatItLeftOpen() throws Exception {
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("full.txt", ResolveAllFlowTest.FULL);
    files.put("part.txt", ResolveAllFlowTest.PART);
    repos.conflicts(files);
    var project = repos.plainProject("attention-all", "full.txt").getProject();
    AtomicReference<ResolveAllReport> got = new AtomicReference<>();
    ResolveAllHandler.start(List.of(project), false, got::set);
    ConflictRepos.waitUntil(() -> got.get() != null && !ResolveAllHandler.isRunning(), 60, "the Resolve All job");
    waitForMessages(project.getFile("part.txt"), List.of(conflicts(1)));
    assertEquals(List.of(), messages(project.getFile("full.txt")));
    ResolveAllDialog d = ResolveAllDialog.show(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
        got.get());
    d.press(ResolveAllDialog.UNDO_ID);
    waitForMessages(project.getFile("part.txt"), List.of()); // back as git left it
  }

  static void waitForMarkerJobs() {
    ConflictRepos.waitUntil(() -> org.eclipse.core.runtime.jobs.Job.getJobManager().find(AttentionMarkers.FAMILY)
        .length == 0, 20, "the marker jobs");
  }

  @Test
  void quickSuccessiveUpdatesLeaveOneMarkerWithTheLatestCount() throws Exception {
    IFile file = twoConflicts("attention-burst");
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < 40; i++) {
        AttentionMarkers.update(file, 1 + i % 3, 1 + i % 2);
      }
      AttentionMarkers.update(file, 2, 1);
      waitForMarkerJobs();
      assertEquals(List.of(aiBlocks(1), conflicts(2)), messages(file), "round " + round);
    }
  }

  @Test
  void anUpdateArrivingAfterTheFileWasStagedLeavesNoConflictsMarker() throws Exception {
    IFile file = twoConflicts("attention-late");
    var rf = RepositoryLocator.locate(file).orElseThrow();
    GitIndex.markResolved(rf.repository(), rf.path()); // staged before the update of an earlier save runs
    long until = System.nanoTime() + 1_000_000_000L; // any check the stage caused is over
    ConflictRepos.waitUntil(() -> System.nanoTime() > until, 5, "a moment");
    waitForMarkerJobs();
    AttentionMarkers.update(file, 1, 0);
    waitForMarkerJobs();
    assertEquals(List.of(), messages(file));
  }

  @Test
  void aFailingItemDoesNotHoldUpTheItemsAfterIt() throws Exception {
    IFile file = twoConflicts("attention-failing");
    var jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
    jobs.beginRule(AttentionMarkers.RULE, null); // both items wait in the queue for one run of the worker
    try {
      AttentionMarkers.enqueue(monitor -> {
        throw new IllegalStateException("a broken item");
      }, 0);
      AttentionMarkers.update(file, 1, 0);
    } finally {
      jobs.endRule(AttentionMarkers.RULE);
    }
    waitForMarkerJobs();
    assertEquals(List.of(conflicts(1)), messages(file));
  }
}
