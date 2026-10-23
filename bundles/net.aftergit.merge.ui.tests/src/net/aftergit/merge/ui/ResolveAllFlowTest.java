package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.RepositoryLocator;
import net.aftergit.merge.git.ResolveAllReport;
import net.aftergit.merge.git.ResolveAllUndo;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve All from the command to the summary dialog (spec §5.5). */
class ResolveAllFlowTest {
  static final String[] FULL = {"x = 0; y = 0;\n", "x = 1; y = 0;\n", "x = 0; y = 2;\n"};
  static final String[] NONE = {"a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n"};
  static final String K = "k1\nk2\nk3\nk4\nk5\n";
  static final String[] PART = {"x = 0; y = 0;\n" + K + "b\n", "x = 1; y = 0;\n" + K + "L\n",
      "x = 0; y = 2;\n" + K + "R\n"};

  @TempDir
  File dir;
  ConflictRepos repos;
  IProject project;

  @BeforeEach
  void setUp() throws Exception {
    repos = new ConflictRepos(dir);
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("full.txt", FULL);
    files.put("none.txt", NONE);
    files.put("part.txt", PART);
    repos.conflicts(files);
    project = repos.plainProject("resolve-all-it", "full.txt").getProject();
  }

  @AfterEach
  void tearDown() throws Exception {
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 60, "the end of the last run");
    ResolveAllDialog.closeCurrent();
    repos.dispose();
  }

  private static Shell shell() {
    return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
  }

  private ResolveAllReport runOn(List<IResource> resources, boolean wholeRepository) throws Exception {
    AtomicReference<ResolveAllReport> got = new AtomicReference<>();
    ResolveAllHandler.start(resources, wholeRepository, got::set);
    // pump events instead of join(): resource listeners of the workbench may need the UI thread meanwhile
    // a run counts as running until its report is delivered: wait for that too, so the next run may start
    ConflictRepos.waitUntil(() -> got.get() != null && !ResolveAllHandler.isRunning(), 60, "the Resolve All job");
    return got.get();
  }

  private boolean conflicted(String path) throws Exception {
    var rf = RepositoryLocator.locate(project.getFile(path)).orElseThrow();
    return ConflictState.isConflicted(rf.repository(), rf.path());
  }

  @Test
  void summaryStageAndUndo() throws Exception {
    byte[] full = repos.bytes("full.txt");
    byte[] part = repos.bytes("part.txt");
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    assertEquals(MessageFormat.format(Messages.resolveAllSummary, 1, 1, 1), d.summaryText());
    assertTrue(d.isEnabled(ResolveAllDialog.STAGE_ID));

    d.press(ResolveAllDialog.STAGE_ID);
    assertFalse(conflicted("full.txt"));
    assertTrue(conflicted("part.txt"), "a partly resolved file is not staged");
    assertFalse(d.isEnabled(ResolveAllDialog.STAGE_ID), "staged once");

    d.press(ResolveAllDialog.UNDO_ID);
    assertArrayEquals(full, repos.bytes("full.txt"));
    assertArrayEquals(part, repos.bytes("part.txt"));
    assertTrue(conflicted("full.txt"), "undo brings the conflict back into the index");
    assertEquals(Messages.resolveAllUndone, d.summaryText());
    assertFalse(d.isEnabled(ResolveAllDialog.UNDO_ID));
    assertFalse(d.isEnabled(ResolveAllDialog.OPEN_ID));
  }

  @Test
  void anIncompleteUndoSaysWhichFilesAndCanBeRetried() throws Exception {
    byte[] full = repos.bytes("full.txt");
    byte[] part = repos.bytes("part.txt");
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    d.press(ResolveAllDialog.STAGE_ID);
    File lock = new File(dir, ".git/index.lock"); // the staged file cannot get its conflict stages back
    assertTrue(lock.createNewFile());
    try {
      d.press(ResolveAllDialog.UNDO_ID);
      String path = project.getFile("full.txt").getFullPath().toString();
      assertEquals(NLS.bind(Messages.resolveAllUndoIncomplete, path), d.summaryText());
      assertArrayEquals(part, repos.bytes("part.txt"), "the other file is undone");
      assertTrue(d.isEnabled(ResolveAllDialog.UNDO_ID), "Undo stays for a retry");
      assertFalse(d.isEnabled(ResolveAllDialog.STAGE_ID));
      assertFalse(d.isEnabled(ResolveAllDialog.OPEN_ID));
    } finally {
      assertTrue(lock.delete());
    }
    d.press(ResolveAllDialog.UNDO_ID);
    assertEquals(Messages.resolveAllUndone, d.summaryText());
    assertArrayEquals(full, repos.bytes("full.txt"));
    assertTrue(conflicted("full.txt"));
    assertFalse(d.isEnabled(ResolveAllDialog.UNDO_ID));
  }

  @Test
  void anUndoNamesTheFilesChangedSince() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    repos.write("full.txt", "edited by hand\n");
    project.getFile("full.txt").refreshLocal(0, null);
    d.press(ResolveAllDialog.UNDO_ID);
    assertEquals(NLS.bind(Messages.resolveAllUndoneExcept, project.getFile("full.txt").getFullPath().toString()),
        d.summaryText());
    assertEquals("edited by hand\n", repos.read("full.txt"));
  }

  @Test
  void undoLeavesFilesOpenInTheResolverForALaterUndo() throws Exception {
    byte[] full = repos.bytes("full.txt");
    byte[] part = repos.bytes("part.txt");
    ResolveAllReport r = runOn(List.of(project), false);
    byte[] partResolved = repos.bytes("part.txt");
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    d.press(ResolveAllDialog.OPEN_ID);
    ConflictRepos.waitUntil(() -> ConflictRepos.page().getEditorReferences().length == 2, 10, "two resolvers");
    d.press(ResolveAllDialog.UNDO_ID);
    IFile partFile = project.getFile("part.txt");
    assertArrayEquals(partResolved, repos.bytes("part.txt"), "not written under its open resolver");
    assertArrayEquals(full, repos.bytes("full.txt"));
    assertEquals(NLS.bind(Messages.resolveAllUndoBusy, partFile.getFullPath().toString()), d.summaryText());
    assertEquals(Messages.rowBusy, d.detailsOf(partFile));
    assertTrue(d.isEnabled(ResolveAllDialog.UNDO_ID), "Undo again once the editor is closed");

    ConflictRepos.page().closeAllEditors(false);
    d.press(ResolveAllDialog.UNDO_ID);
    assertEquals(Messages.resolveAllUndone, d.summaryText());
    assertArrayEquals(part, repos.bytes("part.txt"));
    assertFalse(d.isEnabled(ResolveAllDialog.UNDO_ID));
  }

  @Test
  void stagingSkipsAFileChangedSinceTheRun() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    repos.write("full.txt", "<<<<<<< saved back by hand\n");
    IFile full = project.getFile("full.txt");
    full.refreshLocal(0, null);
    String before = d.summaryText();
    d.press(ResolveAllDialog.STAGE_ID);
    assertTrue(conflicted("full.txt"), "not staged");
    assertEquals(NLS.bind(Messages.resolveAllLines, before,
        NLS.bind(Messages.resolveAllStageSkipped, full.getFullPath().toString())), d.summaryText());
    assertEquals(Messages.rowNotStaged, d.detailsOf(full));
  }

  @Test
  void stagingLeavesFilesOpenInTheResolverForALaterStage() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    IFile full = project.getFile("full.txt");
    ConflictRepos.open(full, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    String before = d.summaryText();
    d.press(ResolveAllDialog.STAGE_ID);
    assertTrue(conflicted("full.txt"), "not staged under its open resolver");
    assertEquals(NLS.bind(Messages.resolveAllLines, before,
        NLS.bind(Messages.resolveAllStageBusy, full.getFullPath().toString())), d.summaryText());
    assertEquals(Messages.rowStageBusy, d.detailsOf(full));
    assertTrue(d.isEnabled(ResolveAllDialog.STAGE_ID), "Stage again once the editor is closed");

    ConflictRepos.page().closeAllEditors(false);
    d.press(ResolveAllDialog.STAGE_ID);
    assertFalse(conflicted("full.txt"));
    assertFalse(d.isEnabled(ResolveAllDialog.STAGE_ID));
  }

  @Test
  void aRunCountsAsRunningUntilItsReportIsDelivered() throws Exception {
    AtomicReference<ResolveAllReport> a = new AtomicReference<>();
    ResolveAllJob jobA = ResolveAllHandler.start(List.of(project.getFile("full.txt")), false, a::set);
    ConflictRepos.waitUntil(() -> a.get() != null, 60, "run A's report");
    // the job ends, but what it posted to the UI thread has not run yet (no events are dispatched here)
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (jobA.getState() != Job.NONE) {
      assertTrue(System.nanoTime() < deadline, "run A ends");
      Thread.sleep(10);
    }
    assertTrue(ResolveAllHandler.isRunning(), "not delivered yet");
    assertNull(ResolveAllHandler.start(List.of(project.getFile("part.txt")), false, r -> fail("started")));
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 10, "the end of run A");
    assertFalse(a.get().undo().isEmpty(), "run A's undo is kept");
  }

  @Test
  void aRunCancelledBeforeItStartedIsNotRunning() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
    Job.getJobManager().suspend(); // the job stays waiting
    try {
      ResolveAllJob job = ResolveAllHandler.start(List.of(project), false, r -> delivered.set(true));
      assertNotNull(job);
      assertTrue(job.cancel(), "cancelled before it ran");
    } finally {
      Job.getJobManager().resume();
    }
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 10, "the cancelled run to end");
    assertFalse(delivered.get());
  }

  @Test
  void aRunningResolveAllRefusesANewOne() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Job holder = Job.create("holds the project", m -> {
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    holder.setRule(project); // the run waits for the rule of its first file
    holder.schedule();
    AtomicReference<ResolveAllReport> first = new AtomicReference<>();
    try {
      ResolveAllJob job = ResolveAllHandler.start(List.of(project), false, first::set);
      assertNotNull(job);
      assertTrue(ResolveAllHandler.isRunning());
      assertNull(ResolveAllHandler.start(List.of(project), false, r -> fail("a second run started")));
    } finally {
      release.countDown();
    }
    ConflictRepos.waitUntil(() -> first.get() != null, 60, "the first run");
    holder.join();
    assertEquals(MessageFormat.format(Messages.resolveAllSummary, 1, 1, 1), ResolveAllDialog.summaryOf(first.get()));
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 10, "the end of the first run");
    assertNotNull(ResolveAllHandler.start(List.of(project), false, r -> {}), "a new run once it has finished");
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 60, "the second run");
  }

  @Test
  void aSupersededRunNeverClosesTheNewerDialog() throws Exception {
    AtomicReference<ResolveAllReport> a = new AtomicReference<>();
    ResolveAllJob jobA = ResolveAllHandler.start(List.of(project.getFile("full.txt")), false, a::set);
    ConflictRepos.waitUntil(() -> a.get() != null && !ResolveAllHandler.isRunning(), 60, "run A");
    AtomicReference<ResolveAllReport> b = new AtomicReference<>();
    ResolveAllJob jobB = ResolveAllHandler.start(List.of(project.getFile("part.txt")), false, b::set);
    ConflictRepos.waitUntil(() -> b.get() != null && !ResolveAllHandler.isRunning(), 60, "run B");
    ResolveAllHandler.deliver(jobB, b.get(), shell());
    ResolveAllDialog newer = ResolveAllDialog.current();
    assertNotNull(newer);
    ResolveAllHandler.deliver(jobA, a.get(), shell()); // A's report arrives late
    assertSame(newer, ResolveAllDialog.current(), "the newer dialog stays");
    assertNotNull(newer.getShell());
    assertFalse(b.get().undo().isEmpty(), "with its undo");
    assertTrue(a.get().undo().isEmpty(), "the superseded run's undo is dropped");
  }

  @Test
  void disposingTheDialogWithItsWindowDropsTheUndo() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    d.getShell().dispose(); // as when the parent window closes
    assertTrue(r.undo().isEmpty());
    assertNull(ResolveAllDialog.current());
  }

  @Test
  void longPathListsAreCut() {
    List<IFile> files = new java.util.ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      files.add(project.getFile("f" + i));
    }
    String first = "/resolve-all-it/f1, /resolve-all-it/f2, /resolve-all-it/f3, /resolve-all-it/f4, /resolve-all-it/f5";
    assertEquals(MessageFormat.format(Messages.resolveAllPathsMore, first, 2), ResolveAllDialog.paths(files));
    assertEquals("/resolve-all-it/f1", ResolveAllDialog.paths(files.subList(0, 1)));
  }

  @Test
  void aNewRunClosesTheOpenDialogAndDropsItsUndo() throws Exception {
    ResolveAllReport first = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), first);
    assertFalse(first.undo().isEmpty());
    runOn(List.of(project), false);
    assertNull(d.getShell(), "the dialog of the previous run closed");
    assertTrue(first.undo().isEmpty(), "its undo is gone");
    assertNull(ResolveAllDialog.current());
  }

  @Test
  void closingTheDialogDropsTheUndo() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    d.close();
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void openRemainingOpensTheResolverForEachRemainingFile() throws Exception {
    ResolveAllReport r = runOn(List.of(project), false);
    assertEquals(2, r.remaining().size());
    ResolveAllDialog d = ResolveAllDialog.show(shell(), r);
    d.press(ResolveAllDialog.OPEN_ID);
    ConflictRepos.waitUntil(() -> ConflictRepos.page().getEditorReferences().length == 2, 10, "two resolvers");
  }

  @Test
  void filesOpenInTheResolverAreLeftAlone() throws Exception {
    IFile full = project.getFile("full.txt");
    ConflictRepos.open(full, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertTrue(ResolveAllHandler.busyFiles().contains(full));
    byte[] before = repos.bytes("full.txt");
    ResolveAllReport r = runOn(List.of(project), false);
    FileOutcome o = r.files().stream().filter(f -> full.equals(f.file())).findFirst().orElseThrow();
    assertEquals(FileOutcome.Reason.BUSY, o.reason());
    assertArrayEquals(before, repos.bytes("full.txt"));
  }

  @Test
  void filesWithUnsavedEditorsAreLeftAlone() throws Exception {
    IFile full = project.getFile("full.txt");
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), full, "org.eclipse.ui.DefaultTextEditor");
    editor.getDocumentProvider().getDocument(editor.getEditorInput()).replace(0, 0, "x");
    assertTrue(ResolveAllHandler.busyFiles().contains(full));
    byte[] before = repos.bytes("full.txt");
    ResolveAllReport r = runOn(List.of(full), false);
    assertEquals(FileOutcome.Reason.BUSY, r.files().get(0).reason());
    assertArrayEquals(before, repos.bytes("full.txt"));
  }

  @Test
  void resourcesComeFromTheSelectionOrTheActiveEditor() {
    IFile full = project.getFile("full.txt");
    assertEquals(List.of(full, project), ResolveAllHandler.resources(new StructuredSelection(List.of(full, project)),
        null));
    assertEquals(List.of(full), ResolveAllHandler.resources(StructuredSelection.EMPTY, new FileEditorInput(full)));
    assertEquals(List.of(), ResolveAllHandler.resources(StructuredSelection.EMPTY, null));
  }

  @Test
  void theRepositoryScopeTakesEveryConflictOfTheRepository() throws Exception {
    ResolveAllReport r = runOn(List.of(project.getFile("none.txt")), true);
    assertEquals(3, r.files().size());
    ResolveAllReport single = runOn(List.of(project.getFile("full.txt")), false);
    assertEquals(1, single.files().size());
  }

  @Test
  void theJobHoldsNoSchedulingRule() {
    // ResolveAll takes each file's rule itself; an unrelated rule of the job would fail every file
    assertNull(new ResolveAllJob(List.of(project), false, Set::of, r -> {}).getRule());
  }

  @Test
  void aCancelledJobWritesNothingAndSaysSo() throws Exception {
    byte[] full = repos.bytes("full.txt");
    AtomicReference<ResolveAllReport> got = new AtomicReference<>();
    org.eclipse.core.runtime.NullProgressMonitor cancelled = new org.eclipse.core.runtime.NullProgressMonitor();
    cancelled.setCanceled(true);
    var status = new ResolveAllJob(List.of(project), false, Set::of, got::set).run(cancelled);
    assertEquals(org.eclipse.core.runtime.IStatus.CANCEL, status.getSeverity());
    assertTrue(got.get().cancelled());
    assertArrayEquals(full, repos.bytes("full.txt"));
    assertEquals(MessageFormat.format(Messages.resolveAllSummary, 0, 0, 0) + "\n"
        + MessageFormat.format(Messages.resolveAllCancelled, 0, 3), ResolveAllDialog.summaryOf(got.get()));
  }

  @Test
  void aCancelledRunSaysHowFarItGot() {
    IFile full = project.getFile("full.txt");
    FileOutcome done = new FileOutcome(full, full.getFullPath().toString(), FileOutcome.Result.FULL,
        ConflictKind.TEXT, 1, 1, 0, FileOutcome.Reason.NONE);
    ResolveAllReport r = new ResolveAllReport(List.of(done), true, 4, new ResolveAllUndo())
        .withOutside(List.of("other/a.txt"));
    assertEquals(MessageFormat.format(Messages.resolveAllSummary, 1, 0, 1) + "\n"
        + MessageFormat.format(Messages.resolveAllCancelled, 1, 4), ResolveAllDialog.summaryOf(r));
  }

  @Test
  void everyReasonHasAText() {
    IFile full = project.getFile("full.txt");
    for (FileOutcome.Reason reason : FileOutcome.Reason.values()) {
      FileOutcome o = new FileOutcome(full, full.getFullPath().toString(), FileOutcome.Result.UNTOUCHED,
          ConflictKind.BINARY, 0, 2, 0, reason);
      String text = ResolveAllDialog.details(o);
      assertNotNull(text, reason.name());
      assertFalse(text.isBlank(), reason.name());
    }
    FileOutcome changed = new FileOutcome(full, full.getFullPath().toString(), FileOutcome.Result.UNTOUCHED,
        ConflictKind.TEXT, 0, 2, 0, FileOutcome.Reason.CHANGED_DURING_RUN);
    assertEquals(Messages.reasonChangedDuringRun, ResolveAllDialog.details(changed));
  }
}
