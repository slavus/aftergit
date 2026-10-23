package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.jdt.SideAnalysis;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.SourceViewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Auto-resolved blocks that do not compile (spec §5.4, §6.4, §6.7) in a real Java project. */
class JavaCompileMarksTest {
  static final String PATH = "src/p/M.java";
  static final String BASE = "package p;\n\npublic class M {\n  int first;\n  int second;\n\n  int value() {\n"
      + "    return first + second;\n  }\n}\n";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    repos.dispose();
  }

  private MergeEditorInput open(String ours, String theirs) throws Exception {
    repos.conflict(PATH, BASE, ours, theirs);
    return ConflictRepos.open(repos.javaProject("marks-it", PATH),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  @Test
  void aNewErrorMarksTheBlockAndHoldsTheGreenBar() throws Exception {
    // ours renames the field, theirs uses the old name: the word-level merge does not compile
    String ours = BASE.replace("int first;", "int one;").replace("return first + second;", "return one + second;");
    String theirs = BASE.replace("return first + second;", "return first + first * 2;");
    MergeEditorInput input = open(ours, theirs);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals("    return one + first * 2;\n", m.resultText(block));
    assertEquals(0, m.unresolvedConflicts());
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(mark), 30, "the not-compiling mark");
    assertTrue(v.notCompiling(block));
    assertFalse(v.allResolvedBarShown());
    assertEquals(1, v.pane(PaneKind.RESULT).style().errorFrames().size());
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    int offset = rv.getDocument().getLineOffset(m.blocks().get(block).resultStart());
    assertEquals(Messages.notCompilingHover, new BaseTextHover(v, PaneKind.RESULT).getHoverInfo(rv, new Region(offset, 0)));

    // declaring the old name fixes it: the mark and the frame go, the bar turns green
    IDocument doc = rv.getDocument();
    doc.replace(doc.get().indexOf("int one;") + "int one;".length(), 0, "\n  int first;");
    ConflictRepos.waitUntil(() -> !v.statusLabelText().contains(mark), 30, "the mark to go");
    assertEquals(0, v.notCompilingCount());
    assertTrue(v.allResolvedBarShown());
    assertTrue(v.pane(PaneKind.RESULT).style().errorFrames().isEmpty());
  }

  @Test
  void anInheritedErrorIsNoMark() throws Exception {
    String ours = BASE.replace("return first + second;", "return first + second + 1;");
    String theirs = BASE.replace("return first + second;", "return undefinedName + second;");
    MergeEditorInput input = open(ours, theirs);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    int block = BlockLocator.nextAutoResolved(m, -1, true);
    assertEquals("    return undefinedName + second + 1;\n", m.resultText(block));
    String oneError = MessageFormat.format(Messages.statusErrors, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 30, "the result's error");
    ConflictRepos.waitUntil(() -> v.paneSetup().sideErrors(ThreeSide.LEFT) != null
        && v.paneSetup().sideErrors(ThreeSide.RIGHT) != null, 30, "the analysis of ours and theirs");
    // the analysis job is over (its listeners have run); let the refresh it posted run on this thread
    Job.getJobManager().join(SideAnalysis.FAMILY, null);
    Display d = Display.getCurrent();
    while (d.readAndDispatch()) {
      // drain
    }
    assertFalse(v.notCompiling(block), "theirs has the same error in that block");
    assertTrue(v.allResolvedBarShown());
  }

  @Test
  void aSideAnalysisThatFinishesAfterDisposeIsDropped() throws Exception {
    String ours = BASE.replace("return first + second;", "return first + second + 1;");
    String theirs = BASE.replace("return first + second;", "return first + second + 2;");
    MergeEditorInput input = open(ours, theirs);
    PaneSetup setup = input.viewer().paneSetup();
    ConflictRepos.waitUntil(() -> setup.sideErrors(ThreeSide.LEFT) != null, 30, "the analysis of ours");
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    setup.addProblemListener(calls::incrementAndGet);
    int before = calls.get();
    ((JavaPaneSetup) setup).sidesAnalyzed();
    assertTrue(calls.get() > before, "while open, a finished analysis tells the listeners");

    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
    java.util.concurrent.atomic.AtomicInteger late = new java.util.concurrent.atomic.AtomicInteger();
    setup.addProblemListener(late::incrementAndGet); // registered after dispose: only the guard keeps it quiet
    ((JavaPaneSetup) setup).sidesAnalyzed();
    assertEquals(0, late.get(), "after dispose the late callback is dropped");
    assertNull(setup.sideErrors(ThreeSide.LEFT));
  }
}
