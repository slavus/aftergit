package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.jdt.JavaPanes;
import net.aftergit.merge.jdt.JavaResultPane;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IWidgetTokenKeeper;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.ITextEditorExtension3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaEditorIntegrationTest {
  static final String JAVA_SCOPE = "org.eclipse.jdt.ui.javaEditorScope";
  static final String FOLDING_TOGGLE = "org.eclipse.ui.edit.text.folding.toggle";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaPanes.setFullEditorFailureForTests(null);
    JavaPanes.setFullEditorFailureAfterConnectForTests(null);
    repos.dispose();
  }

  private MergeEditorInput open(SaveDecision decision) throws Exception {
    return JavaSources.open(repos, "editor-it", JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS, decision);
  }

  private MergeEditorInput open() throws Exception {
    return open(ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static JavaResultPane pane(MergeEditorInput input) {
    return ((JavaPaneSetup) input.viewer().paneSetup()).resultPane();
  }

  private static IEditorPart activate(MergeEditorInput input) {
    IEditorPart part = ConflictRepos.page().findEditor(input);
    ConflictRepos.page().activate(part);
    return part;
  }

  private static IDocument result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
  }

  private static IFile file() {
    return ResourcesPlugin.getWorkspace().getRoot().getProject("editor-it").getFile(JavaSources.PATH);
  }

  private static ITextFileBuffer buffer(IFile file) {
    return FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
  }

  private static void close(MergeEditorInput input) {
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
  }

  /** Takes a side and types into the result, the way a user works before cancelling. */
  private static void editTheResult(MergeEditorInput input) {
    input.viewer().nextConflict();
    input.viewer().takeRight();
    var widget = input.viewer().pane(PaneKind.RESULT).text();
    widget.setCaretOffset(0);
    widget.insert("// typed\n");
  }

  /** Warnings logged by the Java integration (a swallowed failure while releasing the editor shows here). */
  private static List<IStatus> jdtWarnings(List<IStatus> logged) {
    return logged.stream().filter(s -> "net.aftergit.merge.jdt".equals(s.getPlugin())).toList();
  }

  /** Takes theirs ("count + undefinedName") and waits for the compile error; returns its offset. */
  private static int takeTheError(MergeEditorInput input) {
    input.viewer().nextConflict();
    input.viewer().takeRight();
    int error = result(input).get().indexOf("undefinedName");
    ConflictRepos.waitUntil(() -> input.viewer().paneSetup().problems().stream()
        .anyMatch(p -> p.error() && p.offset() == error), 20, "the compile error");
    return error;
  }

  @Test
  void resultIsTheFullJavaEditorOverTheSharedBuffer() throws Exception {
    MergeViewer v = open().viewer();
    assertEquals(ResultKind.JAVA_EDITOR, v.resultKind());
    assertEquals("", v.noticeText());
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    assertSame(v.sharedBuffer().document(), rv.getDocument());
    assertFalse(((ProjectionViewer) rv).isProjectionMode(), "no folding");
    assertNull(v.pane(PaneKind.RESULT).text().getVerticalBar(), "the shared slider scrolls the result");
    assertEquals(rv.getDocument().getNumberOfLines(), v.pane(PaneKind.RESULT).text().getLineCount());
  }

  @Test
  void semanticHighlightingColorsFields() throws Exception {
    MergePane result = open().viewer().pane(PaneKind.RESULT);
    RGB field = PreferenceConverter.getColor(PreferenceConstants.getPreferenceStore(), "semanticHighlighting.field.color");
    int offset = result.viewer().getDocument().get().indexOf("count;");
    ConflictRepos.waitUntil(() -> {
      StyleRange r = result.text().getStyleRangeAtOffset(offset);
      return r != null && r.foreground != null && field.equals(r.foreground.getRGB());
    }, 20, "the semantic field color");
  }

  @Test
  void compileErrorOffersQuickFixAndContentAssist() throws Exception {
    MergeEditorInput input = open();
    int error = takeTheError(input);
    ICompletionProposal[] fixes = pane(input).quickAssistProposals(error + 1);
    assertTrue(Arrays.stream(fixes).anyMatch(f -> f.getDisplayString().contains("undefinedName")),
        "Ctrl+1 proposals: " + Arrays.toString(Arrays.stream(fixes).map(ICompletionProposal::getDisplayString).toArray()));
    result(input).replace(error, "undefinedName".length(), "coun");
    ICompletionProposal[] completions = pane(input).completionProposals(error + "coun".length());
    assertTrue(Arrays.stream(completions).anyMatch(c -> c.getDisplayString().startsWith("count")),
        "Ctrl+Space proposals");
  }

  @Test
  void saveUndoAndJavaKeysReachTheRightHandlers() throws Exception {
    int[] asked = {0};
    MergeEditorInput input = open(new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        asked[0]++;
        return SaveDecision.SaveChoice.CANCEL;
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        return SaveDecision.FinishChoice.CANCEL;
      }
    });
    activate(input);
    MergeViewer v = input.viewer();
    ConflictRepos.focusIn(v.pane(PaneKind.RESULT).text());
    v.nextConflict();
    v.takeRight();
    IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IHandlerService hs = w.getService(IHandlerService.class);
    hs.executeCommand(IWorkbenchCommandConstants.EDIT_UNDO, null);
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state(), "Ctrl+Z is the resolver's undo");
    hs.executeCommand(IWorkbenchCommandConstants.FILE_SAVE, null);
    assertEquals(1, asked[0], "Ctrl+S asks the merge editor's question");
    ICommandService cs = w.getService(ICommandService.class);
    assertTrue(cs.getCommand(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS).isHandled(), "Ctrl+Space");
    assertTrue(cs.getCommand(ITextEditorActionDefinitionIds.QUICK_ASSIST).isHandled(), "Ctrl+1");
    assertTrue(w.getService(IContextService.class).getActiveContextIds().contains(JAVA_SCOPE));
    assertFalse(cs.getCommand(FOLDING_TOGGLE).isHandled(), "the result cannot flip JDT's folding preference");
    assertTrue(cs.getCommand(IWorkbenchCommandConstants.NAVIGATE_NEXT).isHandled(),
        "the compare editor's next/previous survive the editor's activation");
    ConflictRepos.focusOut(v.pane(PaneKind.RESULT).text());
    assertFalse(w.getService(IContextService.class).getActiveContextIds().contains(JAVA_SCOPE));
    assertTrue(cs.getCommand(IWorkbenchCommandConstants.NAVIGATE_NEXT).isHandled(),
        "the compare editor's next/previous survive the editor's deactivation");
  }

  @Test
  void cancelLeavesFileBufferAndWorkingCopyAsTheyWere() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> logged.add(status);
    Platform.addLogListener(listener);
    try {
      MergeEditorInput input = open();
      IFile file = file();
      byte[] before = repos.bytes(JavaSources.PATH);
      assertEquals(ResultKind.JAVA_EDITOR, input.viewer().resultKind());
      activate(input);
      ConflictRepos.focusIn(input.viewer().pane(PaneKind.RESULT).text()); // dispose with the actions active
      editTheResult(input);
      assertTrue(buffer(file).isDirty());
      close(input);
      assertArrayEquals(before, repos.bytes(JavaSources.PATH), "the file is untouched");
      assertNull(buffer(file), "the editor's and the resolver's buffer connections are released");
      assertNull(JavaUI.getWorkingCopyManager().getWorkingCopy(new FileEditorInput(file)), "no working copy left");
      assertEquals(List.of(), jdtWarnings(logged));
    } finally {
      Platform.removeLogListener(listener);
    }
  }

  @Test
  void cancelRestoresTheBufferAnOpenJavaEditorShares() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.javaProject("editor-it", JavaSources.PATH);
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor java = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertEquals(ResultKind.JAVA_EDITOR, input.viewer().resultKind());
    IDocument shared = java.getDocumentProvider().getDocument(java.getEditorInput());
    assertSame(shared, result(input));
    editTheResult(input);
    assertTrue(java.isDirty());
    close(input);
    assertFalse(java.isDirty());
    assertFalse(buffer(file).isDirty());
    assertEquals(new String(before, StandardCharsets.UTF_8), shared.get());
    assertSame(shared, buffer(file).getDocument());
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));
    assertNotNull(JavaUI.getWorkingCopyManager().getWorkingCopy(java.getEditorInput()), "the Java editor keeps its own");
  }

  @Test
  void takeClosesAnOpenContentAssistPopup() throws Exception {
    MergeEditorInput input = open();
    activate(input);
    MergeViewer v = input.viewer();
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    rv.getTextWidget().setFocus();
    ConflictRepos.focusIn(rv.getTextWidget());
    int offset = result(input).get().indexOf("return ") + "return ".length();
    rv.setSelectedRange(offset, 0);
    IWidgetTokenKeeper probe = owner -> false;
    assertTrue(rv.canDoOperation(ISourceViewer.CONTENTASSIST_PROPOSALS));
    rv.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
    ConflictRepos.waitUntil(() -> {
      boolean free = rv.requestWidgetToken(probe, 0);
      if (free) {
        rv.releaseWidgetToken(probe);
      }
      return !free;
    }, 20, "the content assist popup");
    v.nextConflict();
    v.takeRight();
    assertTrue(rv.requestWidgetToken(probe, 0), "Take closed the popup");
    rv.releaseWidgetToken(probe);
    MergeModel m = input.session().model();
    assertEquals(m.sideText(0, ThreeSide.RIGHT), m.resultText(0), "the popup inserted nothing");
  }

  @Test
  void failureAfterTheProviderConnectedReleasesEverything() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> logged.add(status);
    Platform.addLogListener(listener);
    JavaPanes.setFullEditorFailureAfterConnectForTests(new IllegalStateException("late JDT change"));
    try {
      MergeEditorInput input = open();
      MergeViewer v = input.viewer();
      IFile file = file();
      byte[] before = repos.bytes(JavaSources.PATH);
      assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
      assertEquals(Messages.noticeJavaDegraded, v.noticeText());
      assertNull(JavaUI.getWorkingCopyManager().getWorkingCopy(new FileEditorInput(file)),
          "the failed editor's provider connection is gone");
      Composite body = v.pane(PaneKind.RESULT).viewer().getControl().getParent();
      assertEquals(1, Arrays.stream(body.getChildren()).filter(c -> !(c instanceof ChangeStripe)).count());
      assertSame(v.sharedBuffer().document(), v.pane(PaneKind.RESULT).viewer().getDocument());
      editTheResult(input);
      close(input);
      assertArrayEquals(before, repos.bytes(JavaSources.PATH));
      assertNull(buffer(file), "connections balanced");
      List<IStatus> warnings = jdtWarnings(logged);
      assertEquals(1, warnings.size(), "only the fallback warning: " + warnings);
      assertEquals("late JDT change", warnings.get(0).getException().getMessage());
    } finally {
      Platform.removeLogListener(listener);
    }
  }

  @Test
  void smartInsertReachesJdtAndTakeStaysVerbatim() throws Exception {
    MergeEditorInput input = open();
    IEditorPart part = activate(input);
    ITextEditorExtension3 ext = part.getAdapter(ITextEditorExtension3.class);
    assertNotNull(ext, "the compare editor adapts to the result's Java editor");
    assertEquals(ITextEditorExtension3.SMART_INSERT, ext.getInsertMode());
    input.viewer().nextConflict();
    input.viewer().takeRight();
    MergeModel m = input.session().model();
    assertEquals(m.sideText(0, ThreeSide.RIGHT), m.resultText(0));
  }

  @Test
  void adapterFailureFallsBackToTheDegradedPane() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> logged.add(status);
    Platform.addLogListener(listener);
    JavaPanes.setFullEditorFailureForTests(new IllegalStateException("simulated JDT change"));
    try {
      MergeViewer v = open().viewer();
      assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
      assertEquals(Messages.noticeJavaDegraded, v.noticeText());
      assertTrue(logged.stream().anyMatch(s -> "net.aftergit.merge.jdt".equals(s.getPlugin())
          && s.getException() != null && "simulated JDT change".equals(s.getException().getMessage())));
      Composite body = v.pane(PaneKind.RESULT).viewer().getControl().getParent();
      long viewers = Arrays.stream(body.getChildren()).filter(c -> !(c instanceof ChangeStripe)).count();
      assertEquals(1, viewers, "the failed editor's viewer is gone");
      assertSame(v.sharedBuffer().document(), v.pane(PaneKind.RESULT).viewer().getDocument());
    } finally {
      Platform.removeLogListener(listener);
    }
  }

  @Test
  void nextErrorMovesToTheCompileErrorAndTheStatusCountsIt() throws Exception {
    MergeEditorInput input = open();
    activate(input);
    int error = takeTheError(input);
    MergeViewer v = input.viewer();
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    rv.setSelectedRange(0, 0);
    v.nextError();
    assertEquals(error, rv.getSelectedRange().x);
    assertEquals("undefinedName".length(), rv.getSelectedRange().y);
    assertTrue(v.statusText().endsWith("\u00B7 1 error"), v.statusText());
    v.nextError(); // the only error: stays
    assertEquals(error, rv.getSelectedRange().x);
    rv.setSelectedRange(rv.getDocument().getLength(), 0);
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class)
        .executeCommand(SiteServices.PREV_ERROR, null);
    assertEquals(error, rv.getSelectedRange().x, "the command works too");
    assertEquals(1, v.errorLines().length);
  }
}
