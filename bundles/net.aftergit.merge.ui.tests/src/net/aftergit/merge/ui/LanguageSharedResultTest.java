package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.Map;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.tm4e.core.model.ITMModel;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Languages design, Part 2: the result of a file with both a TextMate grammar and a language server lives in the
 * file's shared buffer. Its colours share tm4e's model with an editor of the file, and AfterGit's save of it lets no
 * server change the text (the test grammar and the stand-in server of fragment.xml).
 */
class LanguageSharedResultTest {
  private static final String GENERIC_EDITOR = "org.eclipse.ui.genericeditor.GenericEditor";
  /** What the stand-in inserts before a save (willSaveWaitUntil). */
  private static final String SAVE_EDIT = "# inserted by the server before the save\n";
  private static final String COLOURED = "# names\n" + LanguageErrorsTest.BASE;

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
    MergePreferences.reset();
    repos.dispose();
    StandInLanguageServer.reset();
  }

  private record Shared(ITextEditor editor, IDocument document, StyledText editorText, MergeEditorInput input) {}

  /** A Generic Editor of the conflicted file, then the resolver; both coloured, on one document. */
  private Shared openWithEditor() throws Exception {
    return open(true);
  }

  /**
   * The resolver and a Generic Editor of the conflicted file, the editor first when {@code editorFirst}; both
   * coloured, on one document.
   */
  private Shared open(boolean editorFirst) throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", COLOURED, COLOURED.replace("def first", "def one").replace("calc use first",
        "calc use one"), COLOURED.replace("use second", "use first"));
    IFile file = repos.plainProject("languages-shared", "a.agdiag");
    MergeEditorInput input = null;
    if (!editorFirst) {
      input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      TextMateOracle.waitUntilColoured(input.viewer().pane(PaneKind.RESULT).text(), "a.agdiag", false,
          "the colours of the result alone");
    }
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, GENERIC_EDITOR);
    IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
    StyledText widget = (StyledText) editor.getAdapter(Control.class);
    TextMateOracle.waitUntilColoured(widget, "a.agdiag", false, "the colours of the Eclipse editor");
    if (editorFirst) {
      input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    } else {
      ConflictRepos.page().activate(ConflictRepos.page().findEditor(input));
    }
    MergeViewer v = input.viewer();
    assertInstanceOf(LspPaneSetup.class, v.paneSetup());
    assertNotNull(((LspPaneSetup) v.paneSetup()).colours(), "the grammar colours the panes");
    assertSame(doc, v.pane(PaneKind.RESULT).viewer().getDocument(), "the result is the editor's document");
    TextMateOracle.waitUntilColoured(widget, "a.agdiag", false, "the editor's colours of the result text");
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.agdiag", false, "the colours of the result");
    String oneError = MessageFormat.format(Messages.statusErrors, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 30, "the server's error in the result");
    int at = doc.get().indexOf("calc use one use first") + "calc use one use ".length();
    ConflictRepos.waitUntil(() -> LanguageErrorsTest.squiggled(v.pane(PaneKind.RESULT).viewer(), at), 10,
        "the squiggle over the colours");
    return new Shared(editor, doc, widget, input);
  }

  @Test
  void theEditorKeepsItsColoursAndModelWhenTheResolverClosesFirst() throws Exception {
    Shared s = openWithEditor();
    ITMModel model = TMUIPlugin.getTMModelManager().connect(s.document());
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(s.input()), false);
    UiDriver.flush();
    assertTrue(TMUIPlugin.getTMModelManager().isConnected(s.document()), "the editor's model after the resolver");
    assertSame(model, TMUIPlugin.getTMModelManager().connect(s.document()), "the very model the editor had");
    s.document().replace(0, 0, "# typed\ndef typed\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(s.editorText(), "a.agdiag", false, "the colours of text typed in the editor");
  }

  @Test
  void anEditorOpenedAfterTheResolverKeepsItsColoursAndModelWhenTheResolverCloses() throws Exception {
    Shared s = open(false);
    ITMModel model = TMUIPlugin.getTMModelManager().connect(s.document());
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(s.input()), false);
    UiDriver.flush();
    assertSame(model, TMUIPlugin.getTMModelManager().connect(s.document()), "the model the editor shares");
    s.document().replace(0, 0, "# typed\ndef typed\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(s.editorText(), "a.agdiag", false, "the colours of text typed in the editor");
  }

  @Test
  void theResultKeepsItsColoursWhenAnEditorOpenedAfterItCloses() throws Exception {
    resultKeepsItsColoursWhenTheEditorCloses(open(false));
  }

  @Test
  void theResultKeepsItsColoursWhenTheEditorClosesFirst() throws Exception {
    resultKeepsItsColoursWhenTheEditorCloses(openWithEditor());
  }

  private void resultKeepsItsColoursWhenTheEditorCloses(Shared s) throws Exception {
    ConflictRepos.page().closeEditor(s.editor(), false);
    UiDriver.flush();
    MergeViewer v = s.input().viewer();
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    assertSame(s.document(), doc, "the result keeps the shared document");
    doc.replace(0, 0, "# typed\ndef typed\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.agdiag", false,
        "the colours of text typed in the result");
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(s.input()), false);
    UiDriver.flush();
    assertFalse(TMUIPlugin.getTMModelManager().isConnected(doc), "the result's own model went with it");
  }

  /**
   * With its server running already (another file of the language is open), lsp4e connects the result at once, on
   * another thread: it is asked only once the result text is in, so the server opens the result, never the text
   * before it. A change of the document while lsp4e connected it never reached the server, which kept the errors of
   * the conflicted file. The result text goes in slowly here, as on a busy machine.
   */
  @Test
  void aRunningServerOpensTheResultTextNotTheConflictedFile() throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", COLOURED, COLOURED.replace("def first", "def one").replace("calc use first",
        "calc use one"), COLOURED.replace("use second", "use first"));
    IFile file = repos.plainProject("languages-shared", "a.agdiag");
    IFile lib = file.getProject().getFile("lib.agdiag");
    ITextFileBufferManager m = FileBuffers.getTextFileBufferManager();
    m.connect(lib.getFullPath(), LocationKind.IFILE, null);
    SlowChanges slowly = new SlowChanges(file);
    try {
      ConflictRepos.waitUntil(() -> openedText(lib) != null, 30, "the running server");
      m.addFileBufferListener(slowly);
      MergeEditorInput input;
      try {
        input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      } finally {
        m.removeFileBufferListener(slowly);
        slowly.opening = false;
      }
      assertTrue(slowly.slowed, "the result text went in slowly");
      MergeViewer v = input.viewer();
      IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
      awaitServer(input);
      assertEquals(doc.get(), openedText(file), "the server opened the result");
      String oneError = MessageFormat.format(Messages.statusErrors, 1);
      ConflictRepos.waitUntil(() -> v.statusLabelText().contains(oneError), 10, "the server's error in the result");
    } finally {
      m.disconnect(lib.getFullPath(), LocationKind.IFILE, null);
    }
  }

  /** Each change of the buffer of {@code file} takes a while, while the resolver opens. */
  private static final class SlowChanges implements IFileBufferListener, IDocumentListener {
    private final IFile file;
    volatile boolean opening = true;
    volatile boolean slowed;

    SlowChanges(IFile file) {
      this.file = file;
    }

    @Override
    public void bufferCreated(IFileBuffer buffer) {
      if (buffer instanceof ITextFileBuffer b && file.getFullPath().equals(b.getLocation())) {
        b.getDocument().addDocumentListener(this);
      }
    }

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
      if (opening) {
        slowed = true;
        try {
          Thread.sleep(150);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void documentChanged(DocumentEvent event) {}

    @Override
    public void bufferDisposed(IFileBuffer buffer) {}

    @Override
    public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {}

    @Override
    public void bufferContentReplaced(IFileBuffer buffer) {}

    @Override
    public void stateChanging(IFileBuffer buffer) {}

    @Override
    public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {}

    @Override
    public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {}

    @Override
    public void underlyingFileMoved(IFileBuffer buffer, IPath path) {}

    @Override
    public void underlyingFileDeleted(IFileBuffer buffer) {}

    @Override
    public void stateChangeFailed(IFileBuffer buffer) {}
  }

  /** The text {@code f} was last opened with by any stand-in since Eclipse started, or null. */
  private String openedText(IFile f) {
    for (StandInLanguageServer s : StandInLanguageServer.EVER) {
      for (Map.Entry<String, String> e : s.openedText.entrySet()) {
        if (e.getKey().contains(dir.getName()) && e.getKey().endsWith("/" + f.getName())) {
          return e.getValue();
        }
      }
    }
    return null;
  }

  /** The stand-in's edit before a save really reaches a file that lsp4e saves outside the resolver. */
  @Test
  void lsp4eAppliesTheServersEditBeforeASaveOfAnotherBuffer() throws Exception {
    StandInLanguageServer.saveEdit = SAVE_EDIT;
    repos.write("b.agdiag", "def b\n");
    IFile file = repos.plainProject("languages-shared", "b.agdiag");
    ITextFileBufferManager m = FileBuffers.getTextFileBufferManager();
    m.connect(file.getFullPath(), LocationKind.IFILE, null);
    try {
      ITextFileBuffer b = m.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
      ConflictRepos.waitUntil(() -> StandInLanguageServer.INSTANCES.stream()
          .anyMatch(i -> i.opened.keySet().stream().anyMatch(u -> u.endsWith("b.agdiag"))), 30, "lsp4e's connection");
      b.getDocument().replace(b.getDocument().getLength(), 0, "use b\n");
      b.commit(new NullProgressMonitor(), true);
      assertEquals(SAVE_EDIT + "def b\nuse b\n", repos.read("b.agdiag"));
      assertEquals(1, StandInLanguageServer.SAVE_REQUESTS.get());
    } finally {
      m.disconnect(file.getFullPath(), LocationKind.IFILE, null);
    }
  }

  /** The resolver's save tells the server (didSave) and leaves lsp4e's listener of the buffer as it was. */
  @Test
  void afterTheResolversSaveLsp4eSavesTheBufferAsBefore() throws Exception {
    StandInLanguageServer.saveEdit = SAVE_EDIT;
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", LanguageErrorsTest.BASE,
        LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    awaitServer(input);
    String result = input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    assertEquals(result, repos.read("a.agdiag"));
    ConflictRepos.waitUntil(() -> StandInLanguageServer.SAVES.get() == 1, 10, "didSave of the resolver's save");
    assertEquals(0, StandInLanguageServer.SAVE_REQUESTS.get());
    IFile file = input.file();
    ITextFileBuffer b = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(),
        LocationKind.IFILE);
    b.getDocument().replace(b.getDocument().getLength(), 0, "def more\n"); // as an editor of the file types
    b.commit(new NullProgressMonitor(), true); // and saves, outside the resolver
    assertEquals(1, StandInLanguageServer.SAVE_REQUESTS.get(), "lsp4e asks the server again");
    assertTrue(repos.read("a.agdiag").startsWith(SAVE_EDIT), "and applies its edit, as for any save");
    ConflictRepos.waitUntil(() -> StandInLanguageServer.SAVES.get() == 2, 10, "didSave of that save");
  }

  @Test
  void saveAndSaveAndMarkResolvedWriteTheResultExactly() throws Exception {
    StandInLanguageServer.saveEdit = SAVE_EDIT;
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", LanguageErrorsTest.BASE,
        LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    MergeViewer v = input.viewer();
    assertNotNull(v.paneSetup().sharedBuffer());
    awaitServer(input);
    String result = v.pane(PaneKind.RESULT).viewer().getDocument().get();
    int shells = Display.getCurrent().getShells().length;
    assertTrue(input.saveResult(new NullProgressMonitor()), "Save");
    assertEquals(result, repos.read("a.agdiag"), "Save: the resolved text, and nothing of the server");
    v.pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "# last\n");
    String last = v.pane(PaneKind.RESULT).viewer().getDocument().get();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    UiDriver.flush();
    assertEquals(last, repos.read("a.agdiag"), "Save & Mark Resolved: the resolved text, nothing of the server");
    assertEquals(0, StandInLanguageServer.SAVE_REQUESTS.get(), "lsp4e did not ask the server before the save");
    assertEquals(shells, Display.getCurrent().getShells().length, "no dialog");
  }

  @Test
  void saveAndSaveWithMarkersLeaveTheResultAndTheModelAsTheyWere() throws Exception {
    StandInLanguageServer.saveEdit = SAVE_EDIT;
    String base = "def a\nuse a\nx\n";
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", base, base.replace("x", "L"),
        base.replace("x", "R"));
    MergeViewer v = input.viewer();
    assertNotNull(v.paneSetup().sharedBuffer());
    awaitServer(input);
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    String result = doc.get();
    var model = input.session().model();
    String modelText = model.document().chunk(0, model.document().lineCount());
    int shells = Display.getCurrent().getShells().length;
    assertTrue(input.saveResult(new NullProgressMonitor())); // an open conflict: saved with markers
    UiDriver.flush();
    String saved = repos.read("a.agdiag");
    assertTrue(saved.contains("<<<<<<< ") && saved.contains("L") && saved.contains("R"), saved);
    assertFalse(saved.contains(SAVE_EDIT.trim()), "nothing of the server in the file");
    assertEquals(result, doc.get(), "the result shows its own text again");
    assertEquals(modelText, model.document().chunk(0, model.document().lineCount()), "the model is untouched");
    assertEquals(0, StandInLanguageServer.SAVE_REQUESTS.get());
    assertEquals(shells, Display.getCurrent().getShells().length, "no dialog");
  }

  @Test
  void aSaveNeverWaitsForAServerThatIsStillStarting() throws Exception {
    // lsp4e's instance of earlier tests stops a second after its last document: this test starts a new one
    ConflictRepos.waitUntil(() -> StandInLanguageServer.EVER.stream().allMatch(s -> s.stopped), 30,
        "lsp4e's instances of earlier tests to stop");
    StandInLanguageServer.initializeDelayMillis = 5000; // lsp4e waits up to 10 s for an initialization
    StandInLanguageServer.saveEdit = SAVE_EDIT;
    MergeEditorInput input = LanguageErrorsTest.open(repos, "a.agdiag", LanguageErrorsTest.BASE,
        LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    assertNotNull(input.viewer().paneSetup().sharedBuffer());
    ConflictRepos.waitUntil(() -> StandInLanguageServer.INSTANCES.stream()
        .anyMatch(s -> s.clientName != null && !"AfterGit".equals(s.clientName)), 10,
        "lsp4e to start its instance for the result");
    int instances = StandInLanguageServer.INSTANCES.size();
    String result = input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
    long t0 = System.nanoTime();
    assertTrue(input.saveResult(new NullProgressMonitor()));
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took < 1000, "the commit did not wait for the server's initialization: " + took + " ms");
    assertEquals(result, repos.read("a.agdiag"), "the resolved text, and nothing of the server");
    assertEquals(instances, StandInLanguageServer.INSTANCES.size(), "the commit started no server");
    assertEquals(0, StandInLanguageServer.SAVE_REQUESTS.get());
  }

  /** The server has seen the result: lsp4e is connected to the shared document. */
  private static void awaitServer(MergeEditorInput input) {
    ConflictRepos.waitUntil(() -> ((LspPaneSetup) input.viewer().paneSetup()).diagnostics().settled(), 30,
        "the server's answer to the result");
  }
}
