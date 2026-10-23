package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedResultIntegrationTest {
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
    repos.conflict("a.txt", base, ours, theirs);
    return repos.plainProject("shared-it", "a.txt");
  }

  /** Opens the resolver with plain panes whose result is the file's shared buffer. */
  private static MergeEditorInput openShared(IFile file, SaveDecision decision) {
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(decision);
    input.setPaneSetupFactory((f, site, session) -> {
      SharedResult.Opened o = SharedResult.open(f, session);
      assertNull(o.refusal(), "shareable");
      return new PlainPaneSetup(o.shared());
    });
    return ConflictRepos.open(input);
  }

  private static ITextEditor textEditor(IFile file) throws PartInitException {
    return (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, EditorsUI.DEFAULT_TEXT_EDITOR_ID);
  }

  private static IDocument documentOf(ITextEditor e) {
    return e.getDocumentProvider().getDocument(e.getEditorInput());
  }

  private static IDocument result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
  }

  private static ITextFileBuffer buffer(IFile file) {
    return FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
  }

  private static void close(MergeEditorInput input) {
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
  }

  @Test
  void anOpenEditorShowsTheResultLive() throws Exception {
    IFile file = conflict("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertSame(documentOf(editor), result(input));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertEquals("a\nR\nc\n", documentOf(editor).get());
    assertTrue(editor.isDirty());
  }

  @Test
  void closingWithoutSaveRestoresBufferAndFile() throws Exception {
    IFile file = conflict("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    byte[] before = repos.bytes("a.txt");
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    close(input);
    assertEquals(new String(before, StandardCharsets.UTF_8), documentOf(editor).get());
    assertFalse(editor.isDirty());
    assertArrayEquals(before, repos.bytes("a.txt"));
  }

  @Test
  void closingReleasesTheBuffer() throws Exception {
    IFile file = conflict("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    close(input);
    assertNull(buffer(file), "no connection left behind");
  }

  @Test
  void markerSaveKeepsTheResultAndASynchronizedBuffer() throws Exception {
    IFile file = conflict("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    ITextEditor editor = textEditor(file);
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    String shown = result(input).get();
    input.saveResult(new NullProgressMonitor());
    var l = input.session().labels();
    assertEquals("a\n<<<<<<< " + l.left() + "\nL\n=======\nR\n>>>>>>> " + l.right() + "\nc\n", repos.read("a.txt"));
    assertEquals(shown, result(input).get());
    assertTrue(buffer(file).isSynchronized(), "no 'file changed on disk' question in the open editor");
    assertFalse(input.isDirty());
    close(input);
    assertEquals(repos.read("a.txt"), documentOf(editor).get());
    assertFalse(editor.isDirty());
  }

  @Test
  void currentTextSaveKeepsCrlfAndCleansTheBuffer() throws Exception {
    IFile file = conflict("a\r\nb\r\nc\r\n", "a\r\nL\r\nc\r\n", "a\r\nR\r\nc\r\n");
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    assertEquals("a\r\nb\r\nc\r\n", result(input).get(), "the buffer holds the file's line ends");
    assertFalse(input.session().model().document().chunk(0, 3).contains("\r"), "the model never sees '\\r'");
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\r\nL\r\nc\r\n", repos.read("a.txt"));
    assertFalse(buffer(file).isDirty());
  }

  @Test
  void aResolvedBlockOfACrlfResultHighlightsTheRightWordsAndMarks() throws Exception {
    IFile file = conflict("a\r\nx = 0;\r\nint total = price * qty;\r\nc\r\n",
        "a\r\nx = 1;\r\nint total = price * qty + tax;\r\nc\r\n", "a\r\nx = 2;\r\nlong total = price * qty;\r\nc\r\n");
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeViewer v = input.viewer();
    v.nextConflict();
    v.takeRight();
    UiDriver.flush();
    IDocument doc = result(input);
    assertTrue(doc.get().contains("x = 2;\r\nlong total"), "the result keeps the file's CRLF");
    var text = v.pane(PaneKind.RESULT).text();
    int line2 = doc.getLineOffset(2);
    int start = line2; // "long" on the block's second line, behind a "\r\n"
    Color taken = Palette.word(LineStyle.Kind.MODIFIED);
    ConflictRepos.waitUntil(() -> {
      StyleRange r = text.getStyleRangeAtOffset(start);
      return r != null && taken.equals(r.background);
    }, 10, "the result's long");
    StyleRange after = text.getStyleRangeAtOffset(start + "long".length());
    assertTrue(after == null || after.background == null, "the highlight ends with the word");
    StyleRange two = text.getStyleRangeAtOffset(doc.getLineOffset(1) + 4);
    assertEquals(taken, two.background, "the first line's 2");
    int semicolon = line2 + doc.get(line2, doc.getLineLength(2)).indexOf(';');
    assertEquals(List.of(new PanePainter.Insertion(semicolon, Palette.takenStrong())),
        v.pane(PaneKind.RESULT).painter().insertions(), "the mark in front of ';', counted with the CRs");
  }

  @Test
  void enterTypedIntoAnEmptyCrlfResultSavesTheFilesLineEnd() throws Exception {
    IFile file = conflict("", "x\r\n", "y\r\n");
    MergeEditorInput input = openShared(file, ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    var widget = input.viewer().pane(PaneKind.RESULT).text();
    assertEquals("", result(input).get());
    widget.insert("z");
    widget.setCaretOffset(1);
    Event e = new Event();
    e.character = SWT.CR;
    e.keyCode = SWT.CR;
    widget.notifyListeners(SWT.KeyDown, e); // an empty document gives Enter the platform's "\n"
    input.saveResult(new NullProgressMonitor());
    String text = repos.read("a.txt");
    assertFalse(text.contains("\r\r"), "no doubled CR");
    assertEquals("z" + input.session().eol(), text, "saved with W's line end, whatever Enter inserted");
  }
}
