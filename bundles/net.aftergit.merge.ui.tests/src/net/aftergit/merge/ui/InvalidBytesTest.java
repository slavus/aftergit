package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files whose bytes are not valid in their encoding open without loss (Plan 4a, Task 7). */
class InvalidBytesTest {
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

  private static byte[] latin1(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void invalidBytesOpenAsLatin1AndSaveByteForByte() throws Exception {
    byte[] ours = latin1("a\nLé\nc\n"); // 0xE9 alone is not valid UTF-8
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), ours, latin1("a\nRü\nc\n"));
    IFile file = repos.plainProject("invalid-bytes", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    assertEquals(StandardCharsets.ISO_8859_1, input.session().charset());
    assertTrue(input.viewer().noticeText().contains(NLS.bind(Messages.noticeInvalidBytes, "UTF-8")),
        input.viewer().noticeText());
    assertEquals("a\nLé\nc\n", input.viewer().pane(PaneKind.LEFT).viewer().getDocument().get());
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertArrayEquals(ours, repos.bytes("a.txt"));
  }

  @Test
  void aJavaFileWithInvalidBytesStaysPrivate() throws Exception {
    String path = "src/p/A.java";
    repos.conflictBytes(path, latin1("package p;\n// b\nclass A {}\n"),
        latin1("package p;\n// Lé\nclass A {}\n"), latin1("package p;\n// R\nclass A {}\n"));
    IFile file = repos.javaProject("invalid-java", path);
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertNull(input.viewer().sharedBuffer(), "the shared buffer would decode the bytes as UTF-8");
    assertTrue(input.viewer().noticeText().contains(NLS.bind(Messages.noticeNotShared, Messages.reasonInvalidBytes)),
        input.viewer().noticeText());
    assertNull(input.session().imports(), "Latin-1 text of a Java file is not parsed");
  }

  @Test
  void aCharacterTheFallbackCannotHoldStopsTheSave() throws Exception {
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nLé\nc\n"), latin1("a\nR\nc\n"));
    IFile file = repos.plainProject("invalid-euro", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    byte[] w = repos.bytes("a.txt");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    var doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(0, 0, "€");
    var e = assertThrows(org.eclipse.core.runtime.CoreException.class,
        () -> input.saveResult(new NullProgressMonitor()));
    assertTrue(e.getMessage().contains("\u20ac (U+20AC) in line 1, column 1"), e.getMessage());
    assertFalse(e.getMessage().contains("encoding of the file"), "changing the encoding does not help here");
    assertTrue(input.isDirty(), "the editor keeps the unsaved result");
    assertArrayEquals(w, repos.bytes("a.txt"), "nothing was written");
  }

  @Test
  void aCharacterTheFilesOwnCharsetCannotHoldNamesThePlaceAndTheRemedy() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("own-latin1", "a.txt");
    file.getProject().setDefaultCharset("ISO-8859-1", null);
    byte[] w = repos.bytes("a.txt");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    input.viewer().pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "a\n\u2019");
    var e = assertThrows(org.eclipse.core.runtime.CoreException.class,
        () -> input.saveResult(new NullProgressMonitor()));
    assertTrue(e.getMessage().contains("(U+2019) in line 2, column 1"), e.getMessage());
    assertTrue(e.getMessage().contains("change the encoding"), e.getMessage());
    assertTrue(input.isDirty());
    assertArrayEquals(w, repos.bytes("a.txt"));
  }

  @Test
  void markerLabelsNeverStopASaveWithMarkers() throws Exception {
    // the branch name has characters ISO-8859-1 cannot hold; git writes its UTF-8 bytes
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nL\u00e9\nc\n"), latin1("a\nR\nc\n"));
    IFile file = repos.plainProject("invalid-label", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    String left = input.session().labels().left();
    String right = input.session().labels().right();
    assertEquals(StandardCharsets.ISO_8859_1, input.session().charset());
    String branchName = "zna\u010dajka";
    assertArrayEquals(branchName.getBytes(StandardCharsets.UTF_8),
        MergeEditorInput.markerLabel(branchName, StandardCharsets.ISO_8859_1)
            .getBytes(StandardCharsets.ISO_8859_1), "fallback: the UTF-8 bytes git writes");
    assertEquals("zna?ajka", MergeEditorInput.markerLabel(branchName, java.nio.charset.Charset.forName("windows-1252")));
    assertEquals("same", MergeEditorInput.markerLabel("same", StandardCharsets.US_ASCII));
    assertArrayEquals("caf\u00e9".getBytes(StandardCharsets.UTF_8),
        MergeEditorInput.markerLabel("caf\u00e9", StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1),
        "fallback: UTF-8 bytes also when ISO-8859-1 could hold the label");
    input.saveResult(new NullProgressMonitor());
    String saved = new String(repos.bytes("a.txt"), StandardCharsets.ISO_8859_1);
    assertTrue(saved.contains("<<<<<<< " + MergeEditorInput.markerLabel(left, StandardCharsets.ISO_8859_1)), saved);
    assertTrue(saved.contains(">>>>>>> " + MergeEditorInput.markerLabel(right, StandardCharsets.ISO_8859_1)), saved);
  }

  @Test
  void aFailedSaveOnCloseKeepsTheEditorOpenAndDirty() throws Exception {
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nL\u00e9\nc\n"), latin1("a\nR\nc\n"));
    IFile file = repos.plainProject("close-fails", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    java.util.List<org.eclipse.core.runtime.IStatus> shown = new java.util.ArrayList<>();
    input.setErrorReporter(shown::add);
    ConflictRepos.open(input);
    input.viewer().pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "\u20ac");
    var page = ConflictRepos.page();
    // the save of a save-on-close runs through the same workbench code, without the question
    assertFalse(page.saveEditor(page.getActiveEditor(), false), "the save, and with it a close, is stopped");
    assertEquals(1, shown.size(), "the error is shown once");
    assertEquals(1, page.getEditorReferences().length, "the editor stays open");
    assertTrue(input.isDirty());
  }
}
