package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.aftergit.merge.jdt.JavaPanes;
import net.aftergit.merge.jdt.JavaResultPane;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadPositionCategoryException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaDiagnosticsTest {
  /** The debounce delay of the result's diagnostics (spec §7.2), in ms. */
  private static final long DELAY = 500;

  @TempDir
  File dir;
  ConflictRepos repos;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener logListener = (status, plugin) -> {
    if (status.matches(IStatus.ERROR)) {
      logged.add(status);
    }
  };

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    JavaPanes.setForceDegradedForTests(true); // from Task 8 on the full editor would answer otherwise
    Platform.addLogListener(logListener);
  }

  @AfterEach
  void tearDown() throws Exception {
    Platform.removeLogListener(logListener);
    JavaPanes.setForceDegradedForTests(false);
    repos.dispose();
  }

  private static boolean hasError(PaneSetup s, int offset) {
    return s.problems().stream().anyMatch(p -> p.error() && p.offset() == offset);
  }

  private static List<Integer> errorOffsets(PaneSetup s) {
    return s.problems().stream().filter(ResultProblem::error).map(ResultProblem::offset).toList();
  }

  /** Pumps events for {@code millis}. */
  private static void pump(long millis) {
    Display d = Display.getCurrent();
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < end) {
      if (!d.readAndDispatch()) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private MergeViewer openWithError(String base, String ours, String theirs) throws Exception {
    MergeEditorInput input = JavaSources.open(repos, "diag-it", base, ours, theirs,
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeViewer v = input.viewer();
    v.nextConflict();
    v.takeRight();
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    int error = doc.get().indexOf("undefinedName");
    ConflictRepos.waitUntil(() -> hasError(v.paneSetup(), error), 20, "the compile error");
    return v;
  }

  @Test
  void compileErrorAppearsInDegradedMode() throws Exception {
    MergeEditorInput input = JavaSources.open(repos, "diag-it", JavaSources.BASE, JavaSources.OURS,
        JavaSources.THEIRS, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeViewer v = input.viewer();
    assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
    assertEquals(Messages.noticeJavaDegraded, v.noticeText());
    v.nextConflict();
    v.takeRight();
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    int error = doc.get().indexOf("undefinedName");
    ConflictRepos.waitUntil(() -> hasError(v.paneSetup(), error), 20, "the compile error");
    JavaResultPane pane = ((JavaPaneSetup) v.paneSetup()).resultPane();
    assertEquals(0, pane.quickAssistProposals(error).length, "no quick fix without the editor");
    assertEquals(0, pane.completionProposals(error).length, "no content assist without the editor");

    // declaring the name fixes the old error without touching its range; the initializer is a new error
    String field = "int count;";
    doc.replace(doc.get().indexOf(field) + field.length(), 0, "\n  int undefinedName = missingName;");
    int fresh = doc.get().indexOf("missingName");
    ConflictRepos.waitUntil(() -> errorOffsets(v.paneSetup()).equals(List.of(fresh)), 20,
        "only the new error, found by a new reconcile");
  }

  @Test
  void statusAndStripesFollowAReconcileWithoutAnEdit() throws Exception {
    MergeViewer v = openWithError(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    String oneError = java.text.MessageFormat.format(Messages.statusErrors, 1);
    int refreshes = v.refreshCount();
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 5, "the error count in the status line");
    assertEquals(refreshes, v.refreshCount(), "the reconcile updated the status line, not an edit's refresh");
    assertTrue(v.problemRedraws() > 0, "the stripes repaint their error ticks");

    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    String field = "int count;";
    doc.replace(doc.get().indexOf(field) + field.length(), 0, "\n  int undefinedName;");
    pump(100); // the edit's own refresh runs long before the reconcile
    assertTrue(v.statusLabelText().contains(oneError), "the old count stands until the reconcile");
    int redraws = v.problemRedraws();
    refreshes = v.refreshCount();
    ConflictRepos.waitUntil(() -> errorOffsets(v.paneSetup()).isEmpty(), 20, "the reconcile of the fix");
    ConflictRepos.waitUntil(() -> !v.statusLabelText().contains(oneError), 5, "the status line without the error");
    assertEquals(refreshes, v.refreshCount(), "no edit refreshed the status line");
    assertTrue(v.problemRedraws() > redraws, "the stripes repaint without the error tick");
  }

  @Test
  void errorsWaitForTheLastEditOfABurst() throws Exception {
    MergeViewer v = openWithError(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    String field = "int count;";
    doc.replace(doc.get().indexOf(field) + field.length(), 0, "\n  int other = missingName;");
    int fresh = doc.get().indexOf("missingName");
    for (int i = 0; i < 12; i++) { // typing for well over the delay, never pausing for it
      pump(120);
      doc.replace(doc.getLength(), 0, " ");
      assertFalse(hasError(v.paneSetup(), fresh), "no reconcile while the user is still typing");
    }
    ConflictRepos.waitUntil(() -> hasError(v.paneSetup(), fresh), 20, "the error after the burst");
  }

  @Test
  void crlfOffsetsAreDocumentOffsets() throws Exception {
    MergeViewer v = openWithError(JavaSources.crlf(JavaSources.BASE), JavaSources.crlf(JavaSources.OURS),
        JavaSources.crlf(JavaSources.THEIRS));
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    assertTrue(doc.get().contains("\r\n"), "the shared result keeps CRLF");
    assertEquals(List.of(doc.get().indexOf("undefinedName")), errorOffsets(v.paneSetup()));
  }

  @Test
  void reattachReplacesTheOldAnnotations() throws Exception {
    MergeViewer v = openWithError(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    int error = doc.get().indexOf("undefinedName");
    JavaResultPane pane = ((JavaPaneSetup) v.paneSetup()).resultPane();
    pane.attach(doc);
    pump(DELAY * 4);
    ConflictRepos.waitUntil(() -> hasError(v.paneSetup(), error), 20, "the error after re-attaching");
    pump(DELAY * 2);
    assertEquals(List.of(error), errorOffsets(v.paneSetup()), "one annotation per problem");

    pane.dispose();
    assertEquals(List.of(), v.paneSetup().problems(), "dispose removes its annotations");
  }

  @Test
  void closingLeavesNoPositionsInTheSharedDocument() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.javaProject("diag-it", JavaSources.PATH);
    ITextFileBufferManager m = FileBuffers.getTextFileBufferManager();
    m.connect(file.getFullPath(), LocationKind.IFILE, null); // keeps the document alive across the resolver
    try {
      IDocument shared = m.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).getDocument();
      List<String> categories = Arrays.asList(shared.getPositionCategories());
      int positions = positionCount(shared);
      MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      MergeViewer v = input.viewer();
      v.nextConflict();
      v.takeRight();
      int error = v.pane(PaneKind.RESULT).viewer().getDocument().get().indexOf("undefinedName");
      ConflictRepos.waitUntil(() -> hasError(v.paneSetup(), error), 20, "the compile error");
      ConflictRepos.page().closeAllEditors(false);
      pump(DELAY * 2);
      assertEquals(categories, Arrays.asList(shared.getPositionCategories()), "no position category left behind");
      assertEquals(positions, positionCount(shared), "no annotation positions left behind");
    } finally {
      m.disconnect(file.getFullPath(), LocationKind.IFILE, null);
    }
  }

  private static int positionCount(IDocument d) throws BadPositionCategoryException {
    int n = 0;
    for (String c : d.getPositionCategories()) {
      n += d.getPositions(c).length;
    }
    return n;
  }

  @Test
  void closingWhileReconcilingLogsNoError() throws Exception {
    MergeViewer v = openWithError(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    for (int i = 0; i < 5; i++) {
      doc.replace(doc.getLength(), 0, "\n// " + i);
      pump(DELAY + 20 * i); // lands around the start of a reconcile
    }
    ConflictRepos.page().closeAllEditors(false);
    pump(DELAY * 4);
    assertEquals(List.of(), logged, "closing while reconciling is quiet");
  }

  @Test
  void javaFileOutsideAJavaProjectOpensWithoutErrors() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.plainProject("diag-plain", JavaSources.PATH);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeViewer v = input.viewer();
    assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
    v.nextConflict();
    v.takeRight();
    pump(DELAY * 4);
    assertEquals(List.of(), errorOffsets(v.paneSetup()), "no Java model, no errors");
    assertEquals(List.of(), logged, "nothing goes wrong");
  }
}
