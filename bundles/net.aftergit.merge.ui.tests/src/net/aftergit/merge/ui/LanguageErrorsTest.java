package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.lsp.ResultDiagnostics;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: the language server's errors in the result, through lsp4e. */
@SuppressWarnings("restriction") // LanguageServerPlugin.FAMILY_UPDATE_MARKERS: lsp4e names its friends only
class LanguageErrorsTest {
  /** Ours renames "first", theirs uses it again in the same line: the word-level merge has an unknown name. */
  static final String BASE = "import ./lib.agdiag\ndef first\ndef second\n\ncalc use first use second\nwarn\n";
  static final String OURS = BASE.replace("def first", "def one").replace("calc use first", "calc use one");
  static final String THEIRS = BASE.replace("use second", "use first");
  /** The merged line of that block. */
  static final String MERGED = "calc use one use first";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    MergePreferences.reset();
    StandInLanguageServer.reset();
    LspTestHooks.setSideTimes(new long[] {-1, 5000, 100, 0, 5000});
  }

  @AfterEach
  void tearDown() throws Exception {
    LspTestHooks.setSideTimes(null);
    LspTestHooks.setServerFilter(null);
    LspBridge.setAvailableForTests(null);
    JavaBridge.setAvailableForTests(null);
    MergePreferences.reset();
    repos.dispose();
    StandInLanguageServer.reset();
  }

  /** A conflict of {@code path} next to "lib.agdiag", opened in the resolver. */
  static MergeEditorInput open(ConflictRepos repos, String path, String base, String ours, String theirs)
      throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict(path, base, ours, theirs);
    return ConflictRepos.open(repos.plainProject("languages-errors", path),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  static List<Annotation> annotations(SourceViewer viewer, String type) {
    List<Annotation> out = new ArrayList<>();
    IAnnotationModel model = viewer.getAnnotationModel();
    if (model == null) {
      return out;
    }
    for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
      Annotation a = it.next();
      if (type.equals(a.getType())) {
        out.add(a);
      }
    }
    return out;
  }

  @Test
  void theResultShowsTheServersErrorsWithSquigglesRulerAndNextError() throws Exception {
    MergeEditorInput input = open(repos, "a.agdiag", BASE, OURS, THEIRS);
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    assertTrue(v.paneSetup() instanceof LspPaneSetup);
    assertNotNull(v.paneSetup().sharedBuffer(), "the result is the file's shared buffer, as for Java");
    String oneError = MessageFormat.format(Messages.statusErrors, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 30, "the server's error in the result");
    SourceViewer rv = v.pane(PaneKind.RESULT).viewer();
    IDocument doc = rv.getDocument();
    assertEquals(MERGED + "\n", m.resultText(BlockLocator.nextAutoResolved(m, -1, true)), doc.get());
    int at = doc.get().indexOf(MERGED) + "calc use one use ".length();
    List<Annotation> errors = annotations(rv, ResultDiagnostics.ERROR_TYPE);
    assertEquals(1, errors.size());
    assertEquals("Unknown name first", errors.get(0).getText(), "the server's words, not the marker's");
    assertEquals(at, rv.getAnnotationModel().getPosition(errors.get(0)).getOffset());
    assertEquals(1, annotations(rv, ResultDiagnostics.WARNING_TYPE).size(), "the warning is shown");
    assertEquals(1, v.errorCount(), "and not counted as an error");
    assertEquals(1, v.errorLines().length);
    assertEquals(doc.getLineOfOffset(at), v.errorLines()[0], "the overview stripe marks the line");
    // GTK paints later: wait for the squiggle in the widget's styles, never for a redraw
    ConflictRepos.waitUntil(() -> squiggled(rv, at), 10, "the squiggle under the unknown name");

    v.nextError();
    assertEquals(at, rv.getSelectedRange().x, "Next Error goes to it");
    assertEquals("first".length(), rv.getSelectedRange().y);
    rv.setSelectedRange(doc.getLength(), 0);
    v.previousError();
    assertEquals(at, rv.getSelectedRange().x, "Previous Error goes back to it");
    assertEquals("first".length(), rv.getSelectedRange().y);
  }

  static boolean squiggled(SourceViewer rv, int offset) {
    for (StyleRange r : rv.getTextWidget().getStyleRanges()) {
      if (r.start <= offset && offset < r.start + r.length && r.underline
          && (r.underlineStyle == SWT.UNDERLINE_SQUIGGLE || r.underlineStyle == SWT.UNDERLINE_ERROR)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void anEditIsCheckedAgain() throws Exception {
    MergeEditorInput input = open(repos, "a.agdiag", BASE, OURS, THEIRS);
    MergeViewer v = input.viewer();
    String oneError = MessageFormat.format(Messages.statusErrors, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 30, "the server's error");
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.get().indexOf("def one"), 0, "def first\n");
    ConflictRepos.waitUntil(() -> v.errorCount() == 0, 30, "the error to go");
    assertTrue(annotations(v.pane(PaneKind.RESULT).viewer(), ResultDiagnostics.ERROR_TYPE).isEmpty());
  }

  @Test
  void aReportOfAnotherFileIsNoAnswerForTheResult() throws Exception {
    StandInLanguageServer.delayMillis = 8000;
    StandInLanguageServer.slowSuffix = "/a.agdiag"; // only the answers for the conflicted file are late
    MergeEditorInput input = open(repos, "a.agdiag", BASE, OURS, THEIRS);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    repos.write("other.agdiag", "use zz\n");
    IFile other = input.file().getProject().getFile("other.agdiag");
    other.refreshLocal(IResource.DEPTH_ZERO, null);
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), other,
        "org.eclipse.ui.genericeditor.GenericEditor");
    try {
      ConflictRepos.waitUntil(() -> {
        try {
          return java.util.Arrays.stream(other.findMarkers(null, true, IResource.DEPTH_ZERO))
              .anyMatch(m -> m.getAttribute("languageServerId", null) != null);
        } catch (org.eclipse.core.runtime.CoreException e) {
          return false;
        }
      }, 30, "lsp4e's report of the other file");
      Job.getJobManager().join(org.eclipse.lsp4e.LanguageServerPlugin.FAMILY_UPDATE_MARKERS, null); // its job ended
      UiDriver.flush();
      assertFalse(setup.diagnostics().settled(), "the report of another open file does not answer the result");
    } finally {
      ConflictRepos.page().closeEditor(editor, false);
    }
  }

  @Test
  void closingTheResolverReleasesTheDiagnostics() throws Exception {
    MergeEditorInput input = open(repos, "a.agdiag", BASE, OURS, THEIRS);
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    ResultDiagnostics d = setup.diagnostics();
    assertNotNull(d);
    IFile file = input.file();
    assertNotNull(FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE));
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
    UiDriver.flush();
    assertTrue(d.disposed());
    assertNull(FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE),
        "the file's buffer is released: no editor has it");
    assertTrue(repos.read("a.agdiag").contains("<<<<<<< "), "Cancel left the file as git left it");
  }

  @Test
  void anUnsavedEditorKeepsTheResultPrivateAndSaysSo() throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("languages-errors", "a.agdiag");
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, EditorsUI.DEFAULT_TEXT_EDITOR_ID);
    IDocument edited = editor.getDocumentProvider().getDocument(editor.getEditorInput());
    edited.replace(0, 0, "# typed\n");
    assertTrue(editor.isDirty());
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    LspPaneSetup setup = (LspPaneSetup) input.viewer().paneSetup();
    assertNull(setup.sharedBuffer(), "the editor's unsaved changes are neither overwritten nor reverted");
    assertNull(setup.diagnostics(), "no errors of the server without the shared result");
    assertEquals(NLS.bind(Messages.noticeNoLanguageErrors, Messages.reasonUnsavedEditor), setup.notice());
    UiDriver.flush();
    assertTrue(setup.problems().isEmpty());
    assertTrue(edited.get().startsWith("# typed\n"), "the editor's text is untouched");
  }

  @Test
  void noServerMeansNoFeatureNoDialogAndNoLog() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    int shells = Display.getCurrent().getShells().length;
    try {
      for (String path : List.of("a.kt", "a.py")) {
        repos.dispose();
        repos = new ConflictRepos(new File(dir, path.replace('.', '-')));
        MergeEditorInput input = open(repos, path, "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
        MergeViewer v = input.viewer();
        UiDriver.flush();
        assertNull(v.paneSetup().sharedBuffer(), path + ": the result stays private without a server");
        assertTrue(v.paneSetup().problems().isEmpty());
        assertNull(v.paneSetup().sideErrors(net.aftergit.merge.core.merge.ThreeSide.LEFT));
        assertNull(v.paneSetup().notice());
      }
    } finally {
      Platform.removeLogListener(l);
    }
    assertEquals(shells, Display.getCurrent().getShells().length, "no dialog");
    assertTrue(StandInLanguageServer.INSTANCES.isEmpty());
    assertEquals(List.of(), logged.stream()
        .filter(s -> s.getPlugin() != null && s.getPlugin().startsWith("net.aftergit")).toList());
  }

  @Test
  void javaFilesNeverReachALanguageServer() throws Exception {
    JavaBridge.setAvailableForTests(false); // even without the JDT, Java does not go through the languages
    LspTestHooks.setServerFilter(id -> true);
    MergeEditorInput input = open(repos, "A.java", "class A {\n  int a;\n}\n", "class A {\n  int b;\n}\n",
        "class A {\n  int c;\n}\n");
    assertEquals(PlainPaneSetup.class, input.viewer().paneSetup().getClass());
    UiDriver.flush();
    assertTrue(StandInLanguageServer.INSTANCES.isEmpty());
  }

  @Test
  void withoutTheBundleTheFileIsPlainText() throws Exception {
    LspBridge.setAvailableForTests(false);
    MergeEditorInput input = open(repos, "a.agdiag", BASE, OURS, THEIRS);
    assertEquals(PlainPaneSetup.class, input.viewer().paneSetup().getClass());
    assertNull(input.viewer().paneSetup().sharedBuffer());
    assertTrue(StandInLanguageServer.INSTANCES.isEmpty());
  }
}
