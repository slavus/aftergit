package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.aftergit.merge.jdt.JavaPanes;

/** The Java result in the file's shared buffer: refusals, Cancel, Save and failure paths. */
class JavaSharingTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaPanes.setForceDegradedForTests(false);
    repos.dispose();
  }

  private IFile conflict(String base, String ours, String theirs) throws Exception {
    repos.conflict(JavaSources.PATH, base, ours, theirs);
    return repos.javaProject("share-it", JavaSources.PATH);
  }

  private IFile conflict() throws Exception {
    return conflict(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
  }

  private static MergeEditorInput open(IFile file, SaveDecision.SaveChoice choice) {
    return ConflictRepos.open(file, ConflictRepos.answer(choice, false));
  }

  private static ITextEditor textEditor(IFile file) throws PartInitException {
    return (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, EditorsUI.DEFAULT_TEXT_EDITOR_ID);
  }

  private static IDocument documentOf(ITextEditor e) {
    return e.getDocumentProvider().getDocument(e.getEditorInput());
  }

  private static ITextFileBuffer buffer(IFile file) {
    return FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
  }

  private static void close(MergeEditorInput input) {
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
  }

  private static void assertPrivateJavaResult(MergeEditorInput input, String reason) {
    MergeViewer v = input.viewer();
    assertNull(v.sharedBuffer(), "not shared");
    assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
    assertEquals(NLS.bind(Messages.noticeNotShared, reason), v.noticeText());
    IDocument d = v.pane(PaneKind.RESULT).viewer().getDocument();
    assertArrayEquals(new String[] {"\n"}, d.getLegalLineDelimiters(), "a private '\\n' document");
  }

  @Test
  void aByteOrderMarkKeepsTheJavaResultPrivate() throws Exception {
    String bom = "﻿";
    IFile file = conflict(bom + JavaSources.BASE, bom + JavaSources.OURS, bom + JavaSources.THEIRS);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertPrivateJavaResult(input, Messages.reasonByteOrderMark);
    close(input);
    assertNull(buffer(file), "no connection left behind");
  }

  @Test
  void aLoneCarriageReturnKeepsTheJavaResultPrivate() throws Exception {
    String base = JavaSources.BASE.replace("int count;", "int count; // a\rb");
    IFile file = conflict(base, base.replace("return 0;", "return count;"),
        base.replace("return 0;", "return 1;"));
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertPrivateJavaResult(input, Messages.reasonCarriageReturn);
    close(input);
    assertNull(buffer(file), "no connection left behind");
  }

  @Test
  void anEditorWithUnsavedChangesKeepsTheJavaResultPrivate() throws Exception {
    IFile file = conflict();
    ITextEditor editor = textEditor(file);
    IDocument doc = documentOf(editor);
    doc.replace(0, 0, "// edited\n");
    String edited = doc.get();
    assertTrue(editor.isDirty());
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertPrivateJavaResult(input, Messages.reasonUnsavedEditor);
    assertNotSame(doc, input.viewer().pane(PaneKind.RESULT).viewer().getDocument());
    input.viewer().nextConflict();
    input.viewer().takeRight();
    close(input);
    assertEquals(edited, doc.get(), "the other editor's text is untouched");
    assertTrue(editor.isDirty(), "and still unsaved");
    assertNotNull(buffer(file), "the editor's own connection remains");
    ConflictRepos.page().closeEditor(editor, false);
    assertNull(buffer(file), "only the editor's connection was left");
  }

  @Test
  void aJavaEditorWithUnsavedChangesKeepsTheJavaResultPrivate() throws Exception {
    IFile file = conflict();
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file,
        org.eclipse.jdt.ui.JavaUI.ID_CU_EDITOR);
    assertTrue(editor instanceof org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor, editor.getClass()
        .getName());
    IDocument doc = documentOf(editor);
    doc.replace(0, 0, "// edited\n");
    String edited = doc.get();
    assertTrue(editor.isDirty());
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertPrivateJavaResult(input, Messages.reasonUnsavedEditor);
    assertNotSame(doc, input.viewer().pane(PaneKind.RESULT).viewer().getDocument());
    input.viewer().nextConflict();
    input.viewer().takeRight();
    close(input);
    assertEquals(edited, doc.get(), "the Java editor's text is untouched");
    assertTrue(editor.isDirty(), "and still unsaved");
    assertNotNull(buffer(file), "the editor's own connection remains");
    ConflictRepos.page().closeEditor(editor, false);
    assertNull(buffer(file), "only the editor's connection was left");
  }

  @Test
  void cancelRevertsTheSharedBufferAndLeavesTheFile() throws Exception {
    JavaPanes.setForceDegradedForTests(true); // the degraded pane; JavaEditorIntegrationTest cancels the full editor
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertEquals(ResultKind.JAVA_DEGRADED, input.viewer().resultKind());
    SourceViewer result = input.viewer().pane(PaneKind.RESULT).viewer();
    assertSame(documentOf(editor), result.getDocument(), "the Java result is the file's buffer");
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertTrue(editor.isDirty());
    close(input);
    assertNull(result.getDocument(), "the closed result lets go of the buffer before it is reverted");
    assertEquals(new String(before, StandardCharsets.UTF_8), documentOf(editor).get());
    assertFalse(editor.isDirty());
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));
  }

  @Test
  void saveGoesThroughTheSharedBuffer() throws Exception {
    IFile file = conflict();
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.CURRENT_TEXT);
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertEquals(JavaSources.OURS, repos.read(JavaSources.PATH));
    assertFalse(buffer(file).isDirty());
    assertFalse(editor.isDirty());
    assertEquals(JavaSources.OURS, documentOf(editor).get());
  }

  @Test
  void undoInAnotherEditorDoesNotBringBackTheSavedMarkers() throws Exception {
    IFile file = conflict();
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    String shown = documentOf(editor).get();
    input.saveResult(new NullProgressMonitor());
    assertTrue(repos.read(JavaSources.PATH).contains("<<<<<<<"), "markers saved");
    assertEquals(shown, documentOf(editor).get());
    ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
    assertTrue(target.canDoOperation(ITextOperationTarget.UNDO));
    target.doOperation(ITextOperationTarget.UNDO);
    assertFalse(documentOf(editor).get().contains("<<<<<<<"), "the marker save is one no-op step");
  }

  /** A setup that fails while the resolver shows its result, after the shared buffer was filled. */
  private static final class FailingSetup implements PaneSetup {
    private final PaneSetup real;

    FailingSetup(PaneSetup real) {
      this.real = real;
    }

    @Override
    public SourceViewer createViewer(PaneKind kind, Composite parent, Supplier<CompositeRuler> rulers, int styles,
        PaneServices services) {
      return real.createViewer(kind, parent, rulers, styles, services);
    }

    @Override
    public IDocument sideDocument(String text) {
      return real.sideDocument(text);
    }

    @Override
    public IDocument resultDocument(String modelChunk) {
      return real.resultDocument(modelChunk);
    }

    @Override
    public String resultDelimiter() {
      return real.resultDelimiter();
    }

    @Override
    public void showResult(SourceViewer viewer, IDocument document) {
      throw new IllegalStateException("forced failure");
    }

    @Override
    public boolean presentationReconciled(PaneKind kind) {
      return real.presentationReconciled(kind);
    }

    @Override
    public void resultFocusChanged(boolean focused) {
      real.resultFocusChanged(focused);
    }

    @Override
    public String resultContextId() {
      return real.resultContextId();
    }

    @Override
    public String notice() {
      return real.notice();
    }

    @Override
    public ResultKind resultKind() {
      return real.resultKind();
    }

    @Override
    public SharedBuffer sharedBuffer() {
      return real.sharedBuffer();
    }

    @Override
    public void dispose() {
      real.dispose();
    }
  }

  @Test
  void aFailingViewerReleasesTheSharedBuffer() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor editor = textEditor(file);
    String text = documentOf(editor).get();
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.setPaneSetupFactory((f, site, s) -> {
      PaneSetup real = JavaBridge.create(f, site, s);
      assertNotNull(real.sharedBuffer(), "the failure happens while the buffer is shared");
      return new FailingSetup(real);
    });
    input.run(new NullProgressMonitor());
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      assertThrows(IllegalStateException.class, () -> input.findContentViewer(null, null, shell));
      assertEquals(0, shell.getChildren().length, "the half-built viewer is gone");
    } finally {
      shell.dispose();
    }
    assertEquals(text, documentOf(editor).get(), "the buffer shows the file again");
    assertFalse(editor.isDirty());
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));
    ConflictRepos.page().closeEditor(editor, false);
    assertNull(buffer(file), "the resolver's connection was released");
  }
}
