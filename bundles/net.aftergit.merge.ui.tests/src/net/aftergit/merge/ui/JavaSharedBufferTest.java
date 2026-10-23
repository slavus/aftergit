package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The full Java result and an open Java editor of the same file, end to end.
 *
 * <p>Covered elsewhere and not repeated here: Cancel with an open Java editor
 * ({@code JavaEditorIntegrationTest.cancelRestoresTheBufferAnOpenJavaEditorShares}), closing without any other
 * editor releases the buffer ({@code JavaEditorIntegrationTest.cancelLeavesFileBufferAndWorkingCopyAsTheyWere}),
 * Take closes a content assist popup ({@code JavaEditorIntegrationTest.takeClosesAnOpenContentAssistPopup}) and an
 * editor with unsaved changes keeps the result private
 * ({@code JavaSharingTest.anEditorWithUnsavedChangesKeepsTheJavaResultPrivate}).
 */
class JavaSharedBufferTest {
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

  private IFile conflict(String base, String ours, String theirs) throws Exception {
    repos.conflict(JavaSources.PATH, base, ours, theirs);
    return repos.javaProject("shared-java-it", JavaSources.PATH);
  }

  private static ITextEditor javaEditor(IFile file) throws Exception {
    return (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
  }

  private static IDocument documentOf(ITextEditor e) {
    return e.getDocumentProvider().getDocument(e.getEditorInput());
  }

  private static MergeEditorInput open(IFile file, SaveDecision.SaveChoice choice) {
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(choice, false));
    IEditorPart part = ConflictRepos.page().findEditor(input);
    ConflictRepos.page().activate(part);
    return input;
  }

  private static IDocument result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
  }

  private static void close(MergeEditorInput input) {
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
  }

  private static void typeEnter(StyledText t, int offset) {
    ConflictRepos.focusIn(t);
    t.setCaretOffset(offset);
    Event e = new Event();
    e.character = SWT.CR;
    e.keyCode = SWT.CR;
    t.notifyListeners(SWT.KeyDown, e);
  }

  private static boolean onlyCrlf(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n' && (i == 0 || s.charAt(i - 1) != '\r')) {
        return false;
      }
      if (s.charAt(i) == '\r' && (i + 1 == s.length() || s.charAt(i + 1) != '\n')) {
        return false;
      }
    }
    return true;
  }

  @Test
  void anOpenJavaEditorSeesTheResultLive() throws Exception {
    IFile file = conflict(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    ITextEditor java = javaEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    assertEquals(ResultKind.JAVA_EDITOR, input.viewer().resultKind());
    assertSame(documentOf(java), result(input));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertTrue(documentOf(java).get().contains("return count;"));
    assertFalse(documentOf(java).get().contains("<<<<<<<"));
    assertTrue(java.isDirty());
  }

  @Test
  void typingInTheJavaEditorEditsTheBlock() throws Exception {
    IFile file = conflict(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    ITextEditor java = javaEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    IDocument doc = documentOf(java);
    int at = doc.get().indexOf("return 0;") + "return ".length();
    doc.replace(at, 1, "42");
    MergeModel m = input.session().model();
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals("    return 42;\n", m.resultText(0));
  }

  @Test
  void crlfJavaTypingTakeUndoSave() throws Exception {
    IFile file = conflict(JavaSources.crlf(JavaSources.BASE), JavaSources.crlf(JavaSources.OURS),
        JavaSources.crlf(JavaSources.THEIRS));
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.CURRENT_TEXT);
    MergeViewer v = input.viewer();
    IDocument doc = result(input);
    assertTrue(onlyCrlf(doc.get()), "the buffer holds CRLF");
    v.nextConflict();
    v.takeRight();
    String afterTake = doc.get();
    typeEnter(v.pane(PaneKind.RESULT).text(), afterTake.indexOf("int value() {") + "int value() {".length());
    assertTrue(doc.get().length() > afterTake.length(), "Enter was typed");
    assertTrue(onlyCrlf(doc.get()), "Enter and the Java auto-indent insert CRLF");
    MergeModel m = input.session().model();
    assertFalse(m.document().chunk(0, m.document().lineCount()).contains("\r"), "the model never sees '\\r'");
    assertEquals(doc.getNumberOfLines() - 1, m.document().lineCount(), "pane lines are model lines");
    v.undo();
    assertEquals(afterTake, doc.get(), "one undo removes the typing");
    assertEquals(BlockState.TAKEN_RIGHT, m.blocks().get(0).state());
    input.saveResult(new NullProgressMonitor());
    assertEquals(JavaSources.crlf(JavaSources.THEIRS), repos.read(JavaSources.PATH));
  }

  @Test
  void markersSaveThroughJavaBuffer() throws Exception {
    IFile file = conflict(JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    ITextEditor java = javaEditor(file);
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.MARKERS);
    input.saveResult(new NullProgressMonitor());
    assertTrue(repos.read(JavaSources.PATH).contains("<<<<<<< "), "markers on disk");
    assertFalse(documentOf(java).get().contains("<<<<<<<"), "the open editor keeps the merged text");
    assertTrue(FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE)
        .isSynchronized());
    close(input);
    assertEquals(repos.read(JavaSources.PATH), documentOf(java).get());
    assertFalse(java.isDirty());
  }

  @Test
  void carriageReturnInsideALineKeepsThePrivateResult() throws Exception {
    String base = JavaSources.BASE;
    String ours = JavaSources.OURS.replace("int count;", "int count; // a\rb");
    IFile file = conflict(base, ours, JavaSources.THEIRS);
    ITextEditor java = javaEditor(file);
    String shownByJava = documentOf(java).get();
    MergeEditorInput input = open(file, SaveDecision.SaveChoice.CURRENT_TEXT);
    MergeViewer v = input.viewer();
    assertNull(v.sharedBuffer());
    assertEquals(ResultKind.JAVA_DEGRADED, v.resultKind());
    assertTrue(v.noticeText().contains(Messages.reasonCarriageReturn), v.noticeText());
    v.nextConflict();
    v.takeLeft();
    assertEquals(shownByJava, documentOf(java).get(), "the Java editor is not touched");
    input.saveResult(new NullProgressMonitor());
    assertTrue(repos.read(JavaSources.PATH).contains("// a\rb"), "the lone CR survives saving");
  }
}
