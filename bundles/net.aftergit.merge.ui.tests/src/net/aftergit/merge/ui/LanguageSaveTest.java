package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Languages design, Part 2: Save &amp; Mark Resolved waits up to 2 s for the language server's answer to the current
 * text, then decides with what it has and says so; blocks that do not compile are named, as unreviewed ones are.
 */
class LanguageSaveTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  final List<String> status = new ArrayList<>();

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    MergePreferences.reset();
    MergePreferences.setCloseAfterFinish(false); // the message goes to the status reporter, the editor stays
    StandInLanguageServer.reset();
    LspTestHooks.setSideTimes(new long[] {-1, 5000, 50, 0, 5000});
  }

  @AfterEach
  void tearDown() throws Exception {
    LspTestHooks.setSideTimes(null);
    JavaBridge.setAvailableForTests(null);
    MergePreferences.reset();
    repos.dispose();
    StandInLanguageServer.reset();
  }

  private MergeEditorInput open(IFile file) {
    return open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private MergeEditorInput open(IFile file, SaveDecision decision) {
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(decision);
    input.setStatusReporter(status::add);
    return ConflictRepos.open(input);
  }

  private MergeEditorInput openNewError() throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", LanguageErrorsTest.BASE, LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    return open(repos.plainProject("languages-save", "a.agdiag"));
  }

  private static long millisOf(Runnable r) {
    long t0 = System.nanoTime();
    r.run();
    return (System.nanoTime() - t0) / 1_000_000;
  }

  @Test
  void theSaveWaitsForTheAnswerToTheCurrentTextAndNamesTheBlock() throws Exception {
    StandInLanguageServer.delayMillis = 300; // lsp4e's answer to the result: the save waits for it
    StandInLanguageServer.sideDelayMillis = 50; // ours, theirs and the result in AfterGit's instance: well within 2 s
    MergeEditorInput input = openNewError();
    assertTrue(input.viewer().paneSetup().diagnosticsPending(), "asked right after the open");
    MergeEditorInput.FinishOutcome[] outcome = new MergeEditorInput.FinishOutcome[1];
    long took = millisOf(() -> outcome[0] = input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, outcome[0]);
    assertTrue(took < MergeEditorInput.DIAGNOSTICS_WAIT_MILLIS + 500, "waited " + took + " ms");
    assertEquals(1, status.size(), status.toString());
    assertTrue(status.get(0).contains(MessageFormat.format(Messages.finishNotCompiling, 1)), status.get(0));
    assertFalse(status.get(0).contains(MergeEditorInput.notCheckedNotice()), status.get(0));
    assertTrue(repos.read("a.agdiag").contains(LanguageErrorsTest.MERGED), "saved and staged anyway");
  }

  @Test
  void anUnansweredSaveDecidesAfterTwoSecondsAndSaysSo() throws Exception {
    StandInLanguageServer.delayMillis = 6000;
    MergeEditorInput input = openNewError();
    MergeEditorInput.FinishOutcome[] outcome = new MergeEditorInput.FinishOutcome[1];
    long took = millisOf(() -> outcome[0] = input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, outcome[0]);
    assertTrue(took >= MergeEditorInput.DIAGNOSTICS_WAIT_MILLIS - 100, "waited " + took + " ms");
    assertTrue(took < MergeEditorInput.DIAGNOSTICS_WAIT_MILLIS + 1500, "waited " + took + " ms");
    assertTrue(status.get(0).contains(MergeEditorInput.notCheckedNotice()), status.get(0));
    assertFalse(status.get(0).contains(MessageFormat.format(Messages.finishNotCompiling, 1)),
        "nothing was known: no block is named");
  }

  @Test
  void anAnsweredTextIsSavedWithoutWaiting() throws Exception {
    MergeEditorInput input = openNewError();
    ConflictRepos.waitUntil(() -> !input.viewer().paneSetup().diagnosticsPending(), 30, "the answers");
    long took = millisOf(() -> input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(took < 1000, "waited " + took + " ms");
    assertTrue(status.get(0).contains(MessageFormat.format(Messages.finishNotCompiling, 1)), status.get(0));
  }

  @Test
  void plainTextNeverWaits() throws Exception {
    StandInLanguageServer.delayMillis = 6000;
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(repos.plainProject("languages-save", "a.txt"));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    long took = millisOf(() -> input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(took < 1000, "waited " + took + " ms");
    assertEquals(List.of("a.txt resolved and staged"), status);
  }

  @Test
  void javaNamesItsBlocksThatDoNotCompileToo() throws Exception {
    String ours = JavaCompileMarksTest.BASE.replace("int first;", "int one;")
        .replace("return first + second;", "return one + second;");
    String theirs = JavaCompileMarksTest.BASE.replace("return first + second;", "return first + first * 2;");
    repos.conflict(JavaCompileMarksTest.PATH, JavaCompileMarksTest.BASE, ours, theirs);
    MergeEditorInput input = open(repos.javaProject("languages-save-java", JavaCompileMarksTest.PATH));
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> input.viewer().statusLabelText().contains(mark), 30, "the Java mark");
    long took = millisOf(() -> input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(took < 1000, "Java does not wait: waited " + took + " ms");
    assertTrue(status.get(0).contains(MessageFormat.format(Messages.finishNotCompiling, 1)), status.get(0));
    assertFalse(status.get(0).contains(MergeEditorInput.notCheckedNotice()), status.get(0));
  }

  private boolean unmerged(String path) throws Exception {
    try (Git git = Git.open(dir)) {
      return git.status().call().getConflicting().contains(path);
    }
  }

  @Test
  void aResultEqualToTheSavedFileIsAnsweredByItsMarkers() throws Exception {
    MergeEditorInput first = openNewError();
    IFile file = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject("languages-save")
        .getFile("a.agdiag");
    ConflictRepos.waitUntil(() -> !first.viewer().paneSetup().diagnosticsPending(), 30, "the answers");
    first.saveChanges(new NullProgressMonitor()); // the resolved text on disk, the file still unmerged
    assertTrue(repos.read("a.agdiag").contains(LanguageErrorsTest.MERGED));
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(first), false);
    assertTrue(unmerged("a.agdiag"));

    // reopened: the result is the text on disk, which the server answered; the late answer of a new open waits
    StandInLanguageServer.delayMillis = 6000;
    LspTestHooks.setSideTimes(new long[] {-1, 1000, 50, 0, 1000}); // the sides give up after 1 s
    MergeEditorInput input = open(file);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    ConflictRepos.waitUntil(() -> setup.sides().finished(), 30, "the checks of ours and theirs");
    assertTrue(setup.diagnostics().settled(), "the file's markers answer the unchanged text");
    // AfterGit's check of the result gives up after 1 s as well
    ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 30, "the check of the result");
    long took = millisOf(() -> input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(took < 1000, "waited " + took + " ms");
    assertEquals(1, status.size(), status.toString());
    assertFalse(status.get(0).contains(MergeEditorInput.notCheckedNotice()), status.get(0));
  }

  @Test
  void aSecondClickWhileTheFirstWaitsIsCancelled() throws Exception {
    StandInLanguageServer.delayMillis = 6000;
    MergeEditorInput input = openNewError();
    MergeEditorInput.FinishOutcome[] second = new MergeEditorInput.FinishOutcome[1];
    Display.getCurrent().asyncExec(() -> second[0] = input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(MergeEditorInput.FinishOutcome.CANCELLED, second[0], "the click during the wait");
    assertEquals(1, status.size(), status.toString());
    assertFalse(unmerged("a.agdiag"));
  }

  @Test
  void closingTheResolverWhileItWaitsCancelsAndStagesNothing() throws Exception {
    StandInLanguageServer.delayMillis = 6000;
    MergeEditorInput input = openNewError();
    Display.getCurrent().asyncExec(
        () -> ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false));
    assertEquals(MergeEditorInput.FinishOutcome.CANCELLED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(List.of(), status);
    assertTrue(unmerged("a.agdiag"), "nothing staged");
  }

  @Test
  void aBlockRevertedWhileItWaitsAsksAboutTheOpenConflict() throws Exception {
    StandInLanguageServer.delayMillis = 6000;
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", LanguageErrorsTest.BASE, LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    AtomicInteger asked = new AtomicInteger();
    MergeEditorInput input = open(repos.plainProject("languages-save", "a.agdiag"), new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        return SaveDecision.SaveChoice.CANCEL;
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        asked.incrementAndGet();
        return SaveDecision.FinishChoice.CANCEL;
      }
    });
    MergeViewer v = input.viewer();
    int block = BlockLocator.nextAutoResolved(input.session().model(), -1, true);
    assertEquals(0, input.session().model().unresolvedConflicts());
    Display.getCurrent().asyncExec(() -> v.revertBlock(block));
    assertEquals(MergeEditorInput.FinishOutcome.CANCELLED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(1, asked.get(), "the open conflict after the wait is asked about");
    assertEquals(List.of(), status);
    assertTrue(unmerged("a.agdiag"));
  }
}
