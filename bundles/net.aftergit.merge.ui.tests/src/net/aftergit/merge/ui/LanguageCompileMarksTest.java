package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.lsp.SideChecks;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.SourceViewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: "new errors" of other languages mark auto-resolved blocks as the Java check does. */
class LanguageCompileMarksTest {
  static final String BASE = LanguageErrorsTest.BASE;
  /** Theirs brings an unknown name into the block that ours also changes: the result inherits it. */
  static final String INHERIT_OURS = BASE.replace("use second", "use second use lib");
  static final String INHERIT_THEIRS = BASE.replace("calc use first", "calc use missing");
  static final String INHERITED = "calc use missing use second use lib";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    StandInLanguageServer.reset();
    LspTestHooks.setSideTimes(new long[] {-1, 5000, 100, 0, 5000});
  }

  @AfterEach
  void tearDown() throws Exception {
    LspTestHooks.setSideTimes(null);
    repos.dispose();
    StandInLanguageServer.reset();
  }

  private MergeEditorInput open(String ours, String theirs) throws Exception {
    return LanguageErrorsTest.open(repos, "a.agdiag", BASE, ours, theirs);
  }

  /** The checks of ours and theirs are over and the refresh they posted has run. */
  private static void sidesDone(MergeViewer v) throws Exception {
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    ConflictRepos.waitUntil(() -> setup.sides().finished(), 30, "the checks of ours and theirs");
    Job.getJobManager().join(SideChecks.FAMILY, null);
    UiDriver.flush();
  }

  @Test
  void aNewErrorMarksTheBlockAndHoldsTheGreenBar() throws Exception {
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals(LanguageErrorsTest.MERGED + "\n", m.resultText(block));
    assertEquals(0, m.unresolvedConflicts());
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(mark), 30, "the not-compiling mark");
    assertTrue(v.notCompiling(block));
    assertFalse(v.allResolvedBarShown());
    assertEquals(1, v.pane(PaneKind.RESULT).style().errorFrames().size());
    assertEquals(List.of(), v.paneSetup().sideErrors(ThreeSide.LEFT), "ours has no error");
    assertEquals(List.of(), v.paneSetup().sideErrors(ThreeSide.RIGHT), "theirs has no error, its import resolves");
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    int offset = rv.getDocument().getLineOffset(m.blocks().get(block).resultStart());
    assertEquals(Messages.notCompilingHover,
        new BaseTextHover(v, PaneKind.RESULT).getHoverInfo(rv, new Region(offset, 0)));

    // declaring the old name fixes it: the mark and the frame go, the bar turns green
    IDocument doc = rv.getDocument();
    doc.replace(doc.get().indexOf("def one"), 0, "def first\n");
    ConflictRepos.waitUntil(() -> !v.statusLabelText().contains(mark), 30, "the mark to go");
    assertEquals(0, v.notCompilingCount());
    assertTrue(v.allResolvedBarShown());
    assertTrue(v.pane(PaneKind.RESULT).style().errorFrames().isEmpty());
  }

  @Test
  void screenshotOfANewError() throws Exception {
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(mark), 30, "the not-compiling mark");
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    int at = rv.getDocument().get().indexOf(LanguageErrorsTest.MERGED) + "calc use one use ".length();
    ConflictRepos.waitUntil(() -> LanguageErrorsTest.squiggled(rv, at), 10, "the squiggle");
    rv.revealRange(at, "first".length()); // the squiggle's offset is in view, however narrow the pane is
    UiDriver.flush();
    org.eclipse.swt.graphics.Rectangle b = rv.getTextWidget().getTextBounds(at, at + "first".length() - 1);
    assertTrue(rv.getTextWidget().getClientArea().contains(b.x, b.y), "the squiggled name is visible: " + b);
    org.eclipse.swt.graphics.ImageData d = Screens.capture(v.getControl(), "languages-errors");
    assertTrue(Screens.painted(d));
  }

  @Test
  void anInheritedErrorIsNoMark() throws Exception {
    MergeEditorInput input = open(INHERIT_OURS, INHERIT_THEIRS);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals(INHERITED + "\n", m.resultText(block));
    ConflictRepos.waitUntil(() -> v.errorCount() == 2, 30, "the result's two errors");
    sidesDone(v);
    assertEquals(List.of(new LineProblem(4, "Unknown name missing")), v.paneSetup().sideErrors(ThreeSide.RIGHT));
    assertEquals(List.of(new LineProblem(4, "Unknown name lib")), v.paneSetup().sideErrors(ThreeSide.LEFT));
    assertFalse(v.notCompiling(block), "ours has one error there and theirs the other");
    assertTrue(v.allResolvedBarShown());
  }

  /** Both sides change a word of the line that the result merges; "keyed" is an error of the file's name only. */
  static final String KEYED_BASE = "def first\ndef second\ndef one\n\ncalc keyed use first use second\n";
  static final String KEYED_OURS = KEYED_BASE.replace("calc keyed use first", "calc keyed use one");
  static final String KEYED_THEIRS = KEYED_BASE.replace("use second", "use first");
  static final String KEYED_MERGED = "calc keyed use one use first";

  @Test
  void theFrameFollowsTheResultAsAfterGitsInstanceChecksItNotAsLsp4eShowsIt() throws Exception {
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", KEYED_BASE, KEYED_OURS, KEYED_THEIRS);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals(KEYED_MERGED + "\n", m.resultText(block));
    // lsp4e's instance checks the file under its own name: the schema of the name finds an error in the result
    ConflictRepos.waitUntil(() -> v.errorCount() == 1, 30, "lsp4e's error of the file's name");
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    assertEquals("Not in the schema of a.agdiag",
        LanguageErrorsTest.annotations(rv, net.aftergit.merge.lsp.ResultDiagnostics.ERROR_TYPE).get(0).getText());
    ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 30, "the checks of the sides and of the result");
    Job.getJobManager().join(SideChecks.FAMILY, null);
    UiDriver.flush();
    // ours has the word as well, but under a virtual name: so has the result there, and nothing is new
    assertEquals(List.of(), setup.sideErrors(ThreeSide.LEFT));
    assertEquals(List.of(), setup.results().errors(java.util.Set.of()));
    assertFalse(v.notCompiling(block), "an error lsp4e shows that AfterGit's instance does not find is no mark");
    assertTrue(v.allResolvedBarShown());
  }

  /** Ours brings "ambient", theirs "declare": the result has both, an error of AfterGit's virtual documents only. */
  static final String AMBIENT_BASE = "def first\ndef second\n\ncalc use first use second\n";
  static final String AMBIENT_OURS = AMBIENT_BASE.replace("calc use first", "calc ambient first");
  static final String AMBIENT_THEIRS = AMBIENT_BASE.replace("first use second", "first declare second");

  @Test
  void aNewErrorOnlyAfterGitsInstanceFindsMarksTheBlock() throws Exception {
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", AMBIENT_BASE, AMBIENT_OURS, AMBIENT_THEIRS);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals("calc ambient first declare second\n", m.resultText(block));
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.notCompiling(block) && v.statusLabelText().contains(mark), 30,
        "the mark of AfterGit's check");
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 30, "lsp4e's answer");
    assertEquals(0, v.errorCount(), "lsp4e shows no error: the file's own name has none");
    assertFalse(v.allResolvedBarShown());
    // the frame says which error it rests on, since nothing in the result is underlined for it
    assertEquals(List.of("Ambient only"), v.notUnderlinedErrors(block));
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    int offset = rv.getDocument().getLineOffset(m.blocks().get(block).resultStart());
    String hover = new BaseTextHover(v, PaneKind.RESULT).getHoverInfo(rv, new Region(offset, 0));
    assertTrue(hover.startsWith(Messages.notCompilingHover), hover);
    assertTrue(hover.endsWith("\nAmbient only"), hover);
    assertTrue(v.statusLabelText().contains(org.eclipse.osgi.util.NLS.bind(Messages.statusNotUnderlined,
        "Ambient only")), v.statusLabelText());
  }

  @Test
  void editsWhileTheInstanceIsBusyNeverPileUpChecksOfTheResult() throws Exception {
    StandInLanguageServer.sideDelayMillis = 3000; // every virtual document waits 3 s: the instance stays busy
    LspTestHooks.setSideTimes(new long[] {-1, 30_000, 100, 0, 30_000});
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    int most = 0;
    for (int i = 0; i < 6; i++) { // each pause is longer than the debounce: a check of the result each time
      doc.replace(doc.getLength(), 0, "# edit " + i + "\n");
      long until = System.nanoTime() + 500_000_000L;
      while (System.nanoTime() < until) {
        UiDriver.flush();
        most = Math.max(most, Job.getJobManager().find(SideChecks.FAMILY).length);
      }
    }
    assertTrue(most <= 2, "the sides' job and one job of the result, never more: " + most);
    long t0 = System.nanoTime();
    ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 60, "the checks to settle on the last text");
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took < 15_000, "only the last text was checked, not one check per edit: " + took + " ms");
    assertTrue(setup.results().settled());
  }

  @Test
  void aResultAboveTheSizeLimitIsNotCheckedAgainAndTheShownErrorsCount() throws Exception {
    net.aftergit.merge.lsp.TextMatePanes.setMaxCharsForTests(20);
    try {
      MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", KEYED_BASE, KEYED_OURS, KEYED_THEIRS);
      MergeViewer v = input.viewer();
      LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
      int block = BlockLocator.nextAutoResolved(input.session().model(), -1, true);
      ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 30, "lsp4e's answer and the sides");
      assertTrue(setup.results().skipped());
      assertTrue(StandInLanguageServer.virtualUris().stream().noneMatch(u -> u.contains(".aftergit-check")),
          "the result was not sent to AfterGit's instance: " + StandInLanguageServer.virtualUris());
      // "new errors" fall back to the errors the result shows: lsp4e's error of the file's name is new to the sides
      ConflictRepos.waitUntil(() -> v.notCompiling(block), 10, "the frame from the shown error");
    } finally {
      net.aftergit.merge.lsp.TextMatePanes.setMaxCharsForTests(-1);
    }
  }

  @Test
  void theFrameArrivesWithTheLateDiagnostics() throws Exception {
    StandInLanguageServer.delayMillis = 5000;
    LspTestHooks.setSideTimes(new long[] {-1, 30_000, 100, 0, 30_000});
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    int block = BlockLocator.nextAutoResolved(input.session().model(), -1, true);
    UiDriver.flush();
    assertTrue(setup.diagnosticsPending(), "nothing is known yet: the server has not answered");
    assertFalse(v.notCompiling(block), "nothing is known yet");
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(mark), 40, "the mark once the server answered");
    assertTrue(v.notCompiling(block));
    assertEquals(1, v.pane(PaneKind.RESULT).style().errorFrames().size());
  }

  @Test
  void theMarkWaitsForTheSidesWhenOnlyTheirDocumentsAreLate() throws Exception {
    StandInLanguageServer.sideDelayMillis = 4000;
    LspTestHooks.setSideTimes(new long[] {-1, 30_000, 100, 0, 30_000});
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    int block = BlockLocator.nextAutoResolved(input.session().model(), -1, true);
    ConflictRepos.waitUntil(() -> v.errorCount() == 1, 30, "the result's error, which is not delayed");
    UiDriver.flush();
    assertFalse(setup.sides().finished(), "ours and theirs are still being checked");
    assertFalse(v.notCompiling(block), "no mark while only the result has settled");
    ConflictRepos.waitUntil(() -> setup.sides().finished(), 40, "the checks of ours and theirs");
    Job.getJobManager().join(SideChecks.FAMILY, null);
    UiDriver.flush();
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.notCompiling(block) && v.statusLabelText().contains(mark), 10,
        "the mark after the sides finished");
  }

  @Test
  void aServerThatRejectsTheSidesMarksEveryErrorOfABlock() throws Exception {
    StandInLanguageServer.rejectMissingFiles = true;
    LspTestHooks.setSideTimes(new long[] {-1, 1000, 100, 0, 1000});
    MergeEditorInput input = open(INHERIT_OURS, INHERIT_THEIRS);
    MergeViewer v = input.viewer();
    int block = BlockLocator.nextAutoResolved(input.session().model(), -1, true);
    ConflictRepos.waitUntil(() -> v.errorCount() == 2, 30, "the result's errors, which still show");
    sidesDone(v);
    LspPaneSetup setup = (LspPaneSetup) v.paneSetup();
    assertTrue(setup.sides().rejected());
    assertEquals(List.of(), setup.sideErrors(ThreeSide.LEFT));
    ConflictRepos.waitUntil(() -> v.notCompiling(block), 10, "\"new errors\" falling back to \"errors\"");
    assertFalse(v.allResolvedBarShown());
  }

  @Test
  void aCheckThatFinishesAfterDisposeIsDropped() throws Exception {
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    sidesDone(input.viewer());
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    setup.addProblemListener(calls::incrementAndGet);
    setup.sidesChecked();
    assertEquals(1, calls.get(), "while open, finished checks tell the listeners");
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
    java.util.concurrent.atomic.AtomicInteger late = new java.util.concurrent.atomic.AtomicInteger();
    setup.addProblemListener(late::incrementAndGet);
    setup.sidesChecked();
    assertEquals(0, late.get(), "after dispose the late callback is dropped");
    assertNull(setup.sideErrors(ThreeSide.LEFT));
  }

  @Test
  void theEditorsServerNeverSeesTheVirtualDocuments() throws Exception {
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    ConflictRepos.waitUntil(() -> setup.sides().finished(), 30, "the checks of ours and theirs");
    // lsp4e's instance may have started in an earlier test: it is then in EVER only
    String real = "/" + dir.getName() + "/a.agdiag";
    ConflictRepos.waitUntil(() -> StandInLanguageServer.EVER.stream()
        .anyMatch(s -> s.opened.keySet().stream().anyMatch(u -> u.endsWith(real))), 30, "lsp4e's instance");
    for (StandInLanguageServer s : StandInLanguageServer.EVER) {
      boolean sawReal = s.opened.keySet().stream().anyMatch(u -> u.endsWith(real));
      boolean sawVirtual = s.opened.keySet().stream().anyMatch(u -> u.contains("/" + dir.getName() + "/a.aftergit-"));
      assertFalse(sawReal && sawVirtual, "one instance saw both: " + s.opened.keySet());
    }
    ConflictRepos.waitUntil(() -> !setup.diagnosticsPending(), 30, "the check of the result");
    List<String> names = StandInLanguageServer.virtualUris().stream().map(u -> u.substring(u.lastIndexOf('/') + 1))
        .toList();
    assertEquals(1, names.stream().filter(n -> n.matches("a\\.aftergit-ours-\\d+\\.agdiag")).count(), names.toString());
    assertEquals(1, names.stream().filter(n -> n.matches("a\\.aftergit-theirs-\\d+\\.agdiag")).count(),
        names.toString());
    assertTrue(names.stream().anyMatch(n -> n.matches("a\\.aftergit-check-\\d+\\.agdiag")),
        "the result is checked as the sides are: " + names);
    assertTrue(names.stream().allMatch(n -> n.matches("a\\.aftergit-(ours|theirs|check)-\\d+\\.agdiag")),
        names.toString());
    StandInLanguageServer.INSTANCES.forEach(s -> s.opened.forEach((uri, onDisk) -> {
      if (uri.contains(".aftergit-")) {
        assertFalse(onDisk, uri + " existed on disk");
      }
    }));
    String[] written = dir.list((d, name) -> name.contains(".aftergit-"));
    assertEquals(List.of(), List.of(written), "virtual documents written to disk");
  }

  @Test
  void closingTheResolverStopsTheSideServer() throws Exception {
    MergeEditorInput input = open(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    ConflictRepos.waitUntil(() -> setup.sides().finished(), 30, "the checks of ours and theirs");
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
    UiDriver.flush();
    // a check of the result that still runs is cancelled and ends within a tenth of a second
    ConflictRepos.waitUntil(() -> SideChecks.runningServers() == 0, 10,
        "AfterGit's own instance to stop (idle time 0 in this test)");
    assertTrue(StandInLanguageServer.INSTANCES.stream().filter(s -> s.opened.keySet().stream()
        .anyMatch(u -> u.contains(".aftergit-"))).allMatch(s -> s.stopped));
  }
}
