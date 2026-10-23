package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.eclipse.osgi.util.NLS;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepositoryLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeEditorIntegrationTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
  }

  private IFile conflictProject(String base, String ours, String theirs) throws Exception {
    repos.conflict("a.txt", base, ours, theirs);
    return repos.plainProject("merge-it", "a.txt");
  }

  private static MergeEditorInput open(IFile file, SaveDecision decision) {
    return ConflictRepos.open(file, decision);
  }

  private static SaveDecision answer(SaveDecision.SaveChoice choice, boolean saveWithoutStaging) {
    return ConflictRepos.answer(choice, saveWithoutStaging);
  }

  @Test
  void takeRightAndSaveAndMarkResolved() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    input.saveAndMarkResolved(new NullProgressMonitor());
    assertEquals("a\nR\nc\n", Files.readString(new File(dir, "a.txt").toPath()));
    var rf = RepositoryLocator.locate(file).orElseThrow();
    assertFalse(ConflictState.isConflicted(rf.repository(), rf.path()));
  }

  @Test
  void saveWithUnresolvedWritesMarkers() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    var l = input.session().labels();
    assertFalse(l.left().isBlank(), "left label");
    assertFalse(l.right().isBlank(), "right label");
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\n<<<<<<< " + l.left() + "\nL\n=======\nR\n>>>>>>> " + l.right() + "\nc\n",
        Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void closeWithoutSaveLeavesFileUntouched() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    byte[] before = Files.readAllBytes(new File(dir, "a.txt").toPath());
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false);
    assertArrayEquals(before, Files.readAllBytes(new File(dir, "a.txt").toPath()));
    var rf = RepositoryLocator.locate(file).orElseThrow();
    assertTrue(ConflictState.isConflicted(rf.repository(), rf.path()));
  }

  @Test
  void saveKeepsCrlf() throws Exception {
    IFile file = conflictProject("a\r\nb\r\nc\r\n", "a\r\nL\r\nc\r\n", "a\r\nR\r\nc\r\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\r\nL\r\nc\r\n", Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void compareWithBaseOpensBesideTheMergeEditor() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    assertEquals(1, page.getEditorReferences().length);
    input.viewer().compareWithBase(CompareWithBase.Pair.OURS_BASE);
    Display d = Display.getCurrent();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (page.getEditorReferences().length < 2 && System.nanoTime() < deadline) {
      if (!d.readAndDispatch()) {
        Thread.sleep(10);
      }
    }
    assertEquals(2, page.getEditorReferences().length);
  }

  @Test
  void editorIsDirtyAfterTake() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
    assertTrue(editor.isDirty());
  }

  @Test
  void saveAfterFileChangedOutsideWorkspace() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    java.nio.file.Path p = new File(dir, "a.txt").toPath();
    // a command-line merge rewrote the file after the workspace last saw it
    Files.writeString(p, Files.readString(p) + "\n");
    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
    assertFalse(file.isSynchronized(org.eclipse.core.resources.IResource.DEPTH_ZERO));
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertTrue(Files.readString(p).startsWith("a\nL\nc\n"));
  }

  @Test
  void newlineTypedIntoCrlfResultSavesWithoutDoubledCarriageReturn() throws Exception {
    IFile file = conflictProject("", "x\r\n", "y\r\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    var rv = input.viewer().pane(PaneKind.RESULT).viewer();
    assertEquals("", rv.getDocument().get());
    var widget = rv.getTextWidget();
    widget.setFocus();
    widget.insert("z");
    widget.setCaretOffset(1);
    org.eclipse.swt.widgets.Event e = new org.eclipse.swt.widgets.Event();
    e.character = org.eclipse.swt.SWT.CR;
    e.keyCode = org.eclipse.swt.SWT.CR;
    widget.notifyListeners(org.eclipse.swt.SWT.KeyDown, e);
    input.saveResult(new NullProgressMonitor());
    String text = Files.readString(new File(dir, "a.txt").toPath());
    assertFalse(text.contains("\r\r"), "no doubled CR: " + text.replace("\r", "\\r").replace("\n", "\\n"));
    assertEquals("z" + input.session().eol(), text);
  }

  @Test
  void cancelledSaveQuestionWritesNothingAndKeepsEditorDirty() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    byte[] before = Files.readAllBytes(new File(dir, "a.txt").toPath());
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CANCEL, false));
    var doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(0, 1, "A");
    IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
    assertTrue(editor.isDirty());
    editor.doSave(new NullProgressMonitor());
    assertArrayEquals(before, Files.readAllBytes(new File(dir, "a.txt").toPath()));
    assertTrue(editor.isDirty());
    assertTrue(input.isSaveNeeded());
    NullProgressMonitor monitor = new NullProgressMonitor();
    assertThrows(org.eclipse.core.runtime.OperationCanceledException.class, () -> input.saveChanges(monitor));
    assertTrue(monitor.isCanceled(), "cancellation reported to the caller's monitor");
    assertArrayEquals(before, Files.readAllBytes(new File(dir, "a.txt").toPath()));
  }

  @Test
  void declinedMarkResolvedLeavesFileAndIndexUntouched() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    byte[] before = Files.readAllBytes(new File(dir, "a.txt").toPath());
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    input.saveAndMarkResolved(new NullProgressMonitor());
    assertArrayEquals(before, Files.readAllBytes(new File(dir, "a.txt").toPath()));
    var rf = RepositoryLocator.locate(file).orElseThrow();
    assertTrue(ConflictState.isConflicted(rf.repository(), rf.path()));
  }

  @Test
  void keyBindingsAreDefined() {
    IBindingService bs = PlatformUI.getWorkbench().getService(IBindingService.class);
    assertBinding(bs, SiteServices.TAKE_RIGHT, "M1+M3+ARROW_RIGHT");
    assertBinding(bs, SiteServices.TAKE_LEFT, "M1+M3+ARROW_LEFT");
    assertBinding(bs, SiteServices.NEXT_CHANGE, "M1+.");
    assertBinding(bs, SiteServices.PREV_CHANGE, "M1+,");
    assertBinding(bs, SiteServices.NEXT_CONFLICT, "M1+M2+.");
    assertBinding(bs, SiteServices.PREV_CONFLICT, "M1+M2+,");
    assertBinding(bs, SiteServices.NEXT_ANY_CONFLICT, "M1+M3+.");
    assertBinding(bs, SiteServices.PREV_ANY_CONFLICT, "M1+M3+,");
    assertBinding(bs, SiteServices.TOGGLE_STRUCTURE, "M1+M3+S");
    assertBinding(bs, SiteServices.TAKE_BOTH, "M1+M3+B");
    assertBinding(bs, SiteServices.IGNORE, "M1+M3+I");
    assertBinding(bs, SiteServices.LAYOUT_ALL, "M1+M3+1");
    assertBinding(bs, SiteServices.LAYOUT_LEFT_RESULT, "M1+M3+2");
    assertBinding(bs, SiteServices.LAYOUT_RESULT_RIGHT, "M1+M3+3");
    assertBinding(bs, SiteServices.LAYOUT_RESULT_BELOW, "M1+M3+4");
  }

  private static void assertBinding(IBindingService bs, String commandId, String keys) {
    try {
      KeySequence expected = KeySequence.getInstance(keys);
      for (Binding b : bs.getBindings()) {
        if (b.getParameterizedCommand() != null && commandId.equals(b.getParameterizedCommand().getId())
            && SiteServices.SCOPE.equals(b.getContextId()) && expected.equals(b.getTriggerSequence())) {
          return;
        }
      }
      fail("no binding " + keys + " for " + commandId);
    } catch (ParseException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void commandsRunOnTheActiveMergeEditor() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    // the helper opens the editor without activating it; handlers only apply to the active part
    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    page.activate(page.findEditor(input));
    IHandlerService hs = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class);
    hs.executeCommand(SiteServices.NEXT_CONFLICT, null);
    hs.executeCommand(SiteServices.TAKE_RIGHT, null);
    assertEquals(BlockState.TAKEN_RIGHT, input.session().model().blocks().get(0).state());
    IContextService cs = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IContextService.class);
    assertTrue(cs.getActiveContextIds().contains(SiteServices.SCOPE));
  }

  @Test
  void autoResolvedNavigationCommandsSelectTheBlock() throws Exception {
    String base = "x = 0;\ny = 0;\na\nb\nc\nu = 0;\nv = 0;\n";
    IFile file = conflictProject(base, base.replace("x = 0", "x = 1").replace("u = 0", "u = 1"),
        base.replace("y = 0", "y = 2").replace("v = 0", "v = 2"));
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    assertEquals(BlockState.AUTO_RESOLVED, input.session().model().blocks().get(0).state());
    assertEquals(BlockState.AUTO_RESOLVED, input.session().model().blocks().get(1).state());
    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    page.activate(page.findEditor(input));
    IHandlerService hs = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class);
    hs.executeCommand(SiteServices.NEXT_AUTO, null);
    assertEquals(new StructuredSelection(0), input.viewer().getSelection());
    hs.executeCommand(SiteServices.NEXT_AUTO, null);
    assertEquals(new StructuredSelection(1), input.viewer().getSelection());
    hs.executeCommand(SiteServices.NEXT_AUTO, null); // nothing below it: no wrap to the first block
    assertEquals(new StructuredSelection(1), input.viewer().getSelection());
    hs.executeCommand(SiteServices.PREV_AUTO, null);
    assertEquals(new StructuredSelection(0), input.viewer().getSelection(), "the block above");
    hs.executeCommand(SiteServices.PREV_AUTO, null); // nothing above it: no wrap to the last block
    assertEquals(new StructuredSelection(0), input.viewer().getSelection());
  }

  @Test
  void undoCommandRestoresBlockState() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    // handlers only apply to the active part
    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    page.activate(page.findEditor(input));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    IHandlerService hs = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class);
    hs.executeCommand(org.eclipse.ui.IWorkbenchCommandConstants.EDIT_UNDO, null);
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    hs.executeCommand(org.eclipse.ui.IWorkbenchCommandConstants.EDIT_REDO, null);
    assertEquals(BlockState.TAKEN_RIGHT, input.session().model().blocks().get(0).state());
  }

  @Test
  void deletingTheFinalNewlineIsSaved() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    IDocument doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLength() - 1, 1, "");
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\nR\nc", Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void addingAFinalNewlineIsSaved() throws Exception {
    IFile file = conflictProject("a\nb\nc", "a\nL\nc", "a\nR\nc");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\nR\nc", Files.readString(new File(dir, "a.txt").toPath()));
    IDocument doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLength(), 0, "\n");
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\nR\nc\n", Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void openingTheSameFileTwiceGivesOneEditor() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    CompareUI.openCompareEditor(new MergeEditorInput(file), true);
    Display d = Display.getCurrent();
    while (d.readAndDispatch()) {
      // let the second open settle
    }
    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    assertEquals(1, page.getEditorReferences().length);
  }

  @Test
  void commandFallsBackToTheActiveEditorFile() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    assertEquals(file, ResolveConflictHandler.fileFrom(new TextSelection(0, 0), new FileEditorInput(file)));
    assertEquals(file, ResolveConflictHandler.fileFrom(new StructuredSelection(file), null));
    assertNull(ResolveConflictHandler.fileFrom(StructuredSelection.EMPTY, null));
  }

  @Test
  void autoResolveOnOpenCanBeTurnedOff() throws Exception {
    MergePreferences.setAutoResolveOnOpen(false);
    try {
      IFile file = conflictProject("x a z b y\n", "x A z b y\n", "x a z B y\n");
      MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
      assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    } finally {
      MergePreferences.reset();
    }
  }

  @Test
  void saveWritesDiff3MarkersWhenTheRepositoryAsksForThem() throws Exception {
    IFile file = conflictProject("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    var repo = RepositoryLocator.locate(file).orElseThrow().repository();
    repo.getConfig().setString("merge", null, "conflictStyle", "diff3");
    repo.getConfig().save();
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.MARKERS, false));
    var l = input.session().labels();
    input.saveResult(new NullProgressMonitor());
    assertEquals("a\n<<<<<<< " + l.left() + "\nL\n||||||| base\nb\n=======\nR\n>>>>>>> " + l.right() + "\nc\n",
        Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void saveKeepsCrlfWhenGitsMarkersEndInLf() throws Exception {
    // JGit writes the marker lines of a CRLF file with LF; here the file starts with a marker
    IFile file = conflictProject("b\r\nc\r\n", "L\r\nc\r\n", "R\r\nc\r\n");
    assertTrue(repos.read("a.txt").startsWith("<<<<<<< HEAD\n"), "the merge wrote an LF marker first");
    MergeEditorInput input = open(file, answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    input.saveResult(new NullProgressMonitor());
    assertEquals("L\r\nc\r\n", Files.readString(new File(dir, "a.txt").toPath()));
  }

  @Test
  void noticeTellsHowTheResultWasBuilt() {
    InitResult fromMarkers = HybridInitializer.initialize(
        new MergeInput(null, "a\n", "b\n", "<<<<<<< o\na\n=======\nb\n>>>>>>> t\n"), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none());
    InitResult normal = HybridInitializer.initialize(new MergeInput("a\n", "a\n", "a\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    assertEquals(Messages.noticeFromMarkers, MergeEditorInput.notice(fromMarkers, null));
    assertEquals(Messages.noticeFromMarkers + " \u00b7 " + NLS.bind(Messages.noticeInvalidBytes, "UTF-8"),
        MergeEditorInput.notice(fromMarkers, StandardCharsets.UTF_8));
    assertNull(MergeEditorInput.notice(normal, null));
  }
}
