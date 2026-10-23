package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Saving the file from another editor while the resolver holds its shared buffer goes through the resolver. */
class ForeignSaveGuardTest {
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

  /** Answers like {@link ConflictRepos#answer} and counts the save questions. */
  private static class Answer implements SaveDecision {
    final SaveDecision.SaveChoice choice;
    final AtomicInteger asked = new AtomicInteger();

    Answer(SaveDecision.SaveChoice choice) {
      this.choice = choice;
    }

    @Override
    public SaveChoice onUnresolved(int unresolved) {
      asked.incrementAndGet();
      return choice;
    }

    @Override
    public FinishChoice onFinishWithConflicts(int unresolved) {
      return FinishChoice.CANCEL;
    }
  }

  private IFile conflict() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    return repos.javaProject("foreign-save-it", JavaSources.PATH);
  }

  private static ITextEditor javaEditor(IFile file) throws Exception {
    return (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
  }

  private static IDocument documentOf(ITextEditor e) {
    return e.getDocumentProvider().getDocument(e.getEditorInput());
  }

  private static ITextFileBuffer buffer(IFile file) {
    return FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
  }

  private static MergeEditorInput open(IFile file, SaveDecision decision) {
    MergeEditorInput input = ConflictRepos.open(file, decision);
    assertEquals(ResultKind.JAVA_EDITOR, input.viewer().resultKind());
    return input;
  }

  private static void close(MergeEditorInput input) {
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
  }

  private static IEditorPart resolverPart(MergeEditorInput input) {
    return ConflictRepos.page().findEditor(input);
  }

  /** Runs a workbench command with {@code part} active, as its key binding would. */
  private static void execute(IEditorPart part, String commandId) throws Exception {
    ConflictRepos.page().activate(part);
    IHandlerService handlers = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class);
    handlers.executeCommand(commandId, null);
    settle();
  }

  /** Runs pending events until every resolver has finished the save that another editor started. */
  private static void settle() {
    Display d = Display.getCurrent();
    while (d.readAndDispatch()) {
      // the save's follow-up work runs here
    }
    for (IEditorReference ref : ConflictRepos.page().getEditorReferences()) {
      IEditorPart e = ref.getEditor(false);
      if (e != null && e.getEditorInput() instanceof MergeEditorInput m) {
        ConflictRepos.waitUntil(() -> !m.foreignSaveInProgress(), 10, "the end of the foreign save");
      }
    }
  }

  /** Changes the shared document from the Java editor, so it has something to save. */
  private static void typeInJava(ITextEditor java) throws Exception {
    IDocument doc = documentOf(java);
    int at = doc.get().indexOf("int count;");
    doc.replace(at, 0, "// note\n  ");
  }

  @Test
  void saveInJavaEditorAsksTheResolverQuestion() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    assertTrue(java.isDirty());
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(1, answer.asked.get(), "the resolver's question was asked once");
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertTrue(disk.contains("// note"), "the typed text is saved too");
    assertFalse(java.isDirty(), "the Java editor is saved");
    assertFalse(input.isDirty(), "the resolver is saved");
    assertFalse(documentOf(java).get().contains("<<<<<<<"), "the shared document keeps the merged text");
    assertEquals(1, input.session().model().unresolvedConflicts(), "the block stays unresolved");
  }

  @Test
  void cancelledForeignSaveWritesNothing() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    String shown = documentOf(java).get();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(1, answer.asked.get());
    assertArrayEquals(before, repos.bytes(JavaSources.PATH), "the file is byte-identical to W");
    assertEquals(shown, documentOf(java).get(), "the shared document is unchanged");
    assertTrue(java.isDirty(), "the Java editor keeps its unsaved changes");
    assertTrue(buffer(file).isDirty());
    assertEquals(1, input.session().model().unresolvedConflicts());
  }

  @Test
  void cancelOfAFileThatDoesNotDecodeLosslesslyWritesMarkersNotAChangedFile() throws Exception {
    IFile file = conflict();
    file.getProject().setDefaultCharset("UTF-8", null);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    IDocument shared = buffer(file).getDocument();
    shared.replace(shared.get().indexOf("int count;"), 0, "// note\n  ");
    // meanwhile the file got a byte that UTF-8 has no use for: decoded it reads U+FFFD, encoded again EF BF BD
    byte[] w = repos.bytes(JavaSources.PATH);
    String first = "package p;\n";
    var broken = new java.io.ByteArrayOutputStream();
    broken.write(w, 0, first.length());
    broken.write(new byte[] {'/', '/', ' ', (byte) 0xFF, '\n'});
    broken.write(w, first.length(), w.length - first.length());
    java.nio.file.Files.write(new File(dir, JavaSources.PATH).toPath(), broken.toByteArray());
    java.util.List<org.eclipse.core.runtime.IStatus> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    org.eclipse.core.runtime.ILogListener l = (status, plugin) -> logged.add(status);
    org.eclipse.core.runtime.Platform.addLogListener(l);
    try {
      buffer(file).commit(null, true); // a save from elsewhere (as an editor's, with overwrite)
      settle();
      assertEquals(1, answer.asked.get());
      byte[] disk = repos.bytes(JavaSources.PATH);
      String text = new String(disk, java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(text.contains("\uFFFD"), "the byte did not come back as U+FFFD: the cancel never rewrote it");
      assertTrue(text.contains("<<<<<<< ") && text.contains("// note"), "markers of the current result: " + text);
      assertTrue(logged.stream().anyMatch(st -> st.getSeverity() == org.eclipse.core.runtime.IStatus.ERROR
          && st.getMessage().contains(file.getFullPath().toString())), "the fallback is logged: " + logged);
      assertEquals(1, input.session().model().unresolvedConflicts());
    } finally {
      org.eclipse.core.runtime.Platform.removeLogListener(l);
    }
  }

  @Test
  void aCommitThatNeverSignalsDoesNotHoldTheResultForever() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    IDocument doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    String shown = doc.get();
    ForeignSaveGuard guard = input.foreignSaveGuard();
    guard.announceCommitForTests(); // a commit announced, then an Error escaped it: no write, no failure signal
    assertTrue(input.foreignSaveInProgress());
    assertEquals(1, guard.inFlight());
    assertTrue(doc.get().contains("<<<<<<< "), "the text to write is in place");
    ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the end of the save that never landed");
    assertEquals(0, guard.inFlight());
    assertEquals(shown, doc.get(), "the result shows its own text again");
    assertTrue(input.isDirty(), "nothing was saved");
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));

    guard.announceCommitForTests(); // the next save is asked and routed as usual
    assertEquals(2, answer.asked.get());
    ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the end of the second stuck save");
    assertEquals(shown, doc.get());
  }

  @Test
  void closingTheResolverEndsACommitThatNeverSignals() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    MergeEditorInput input = open(file, new Answer(SaveDecision.SaveChoice.MARKERS));
    ForeignSaveGuard guard = input.foreignSaveGuard();
    guard.announceCommitForTests();
    assertEquals(1, guard.inFlight());
    close(input);
    assertEquals(0, guard.inFlight(), "dispose ends the save");
    assertFalse(input.foreignSaveInProgress());
    assertNull(buffer(file), "the buffer connection is released");
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));
  }

  @Test
  void foreignSaveWhenAllResolvedWritesCurrentText() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertTrue(java.isDirty());
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(0, answer.asked.get(), "nothing to ask");
    assertEquals(JavaSources.OURS, repos.read(JavaSources.PATH));
    assertFalse(java.isDirty());
    assertFalse(input.isDirty());
  }

  @Test
  void saveAllRoutesTheJavaEditorThroughTheResolver() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    execute(java, "org.eclipse.ui.file.saveAll");
    assertEquals(1, answer.asked.get(), "Save All asks once for the Java editor and the resolver");
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertTrue(disk.contains("// note"), disk);
    assertFalse(java.isDirty());
    assertFalse(input.isDirty());
  }

  @Test
  void saveOnCloseOfTheJavaEditorRoutesThroughTheResolver() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    ConflictRepos.page().activate(java);
    pressFirstButtonOfNextMessageDialog(); // "Save" in the workbench's save-on-close prompt
    assertTrue(ConflictRepos.page().closeEditor(java, true));
    settle();
    assertFalse(dialogTimedOut, "the save-on-close prompt was answered");
    assertEquals(1, answer.asked.get());
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertTrue(disk.contains("// note"), disk);
    assertNotNull(resolverPart(input), "the resolver stays open");
    assertFalse(input.isDirty());
  }

  private static final String[] SAVE_ACTIONS = {
      "editor_save_participant_org.eclipse.jdt.ui.postsavelistener.cleanup", "true",
      "sp_cleanup.on_save_use_additional_actions", "false",
      "sp_cleanup.format_source_code", "true",
      "sp_cleanup.format_source_code_changes_only", "false",
      "sp_cleanup.organize_imports", "true"};

  /** Turns on JDT's save actions (format and organize imports); the sources' 2-space indents get reformatted. */
  private static void enableSaveActions() {
    var node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
    for (int i = 0; i < SAVE_ACTIONS.length; i += 2) {
      node.put(SAVE_ACTIONS[i], SAVE_ACTIONS[i + 1]);
    }
  }

  @AfterEach
  void disableSaveActions() {
    var node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
    for (int i = 0; i < SAVE_ACTIONS.length; i += 2) {
      node.remove(SAVE_ACTIONS[i]);
    }
  }

  @Test
  void saveActionsAreAskedOnceAndLeaveTheResultAlone() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    String shown = documentOf(java).get();
    enableSaveActions();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(1, answer.asked.get(), "one question for the save and its save actions");
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertEquals(shown, documentOf(java).get(), "the save actions do not change the result");
    assertEquals(1, input.session().model().unresolvedConflicts(), "the block stays unresolved");
    assertFalse(java.isDirty());
    assertFalse(input.isDirty());
  }

  @Test
  void cancelStopsTheSaveActionsToo() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    String shown = documentOf(java).get();
    enableSaveActions();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(1, answer.asked.get(), "one question for the save and its save actions");
    assertArrayEquals(before, repos.bytes(JavaSources.PATH), "the file is byte-identical to W");
    assertEquals(shown, documentOf(java).get(), "the save actions do not change the result");
    assertEquals(1, input.session().model().unresolvedConflicts(), "the block stays unresolved");
    assertTrue(java.isDirty());
  }

  @Test
  void saveActionsApplyWhenEverythingIsResolved() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    IDocument doc = documentOf(java);
    doc.replace(doc.get().indexOf("int count;"), 0, "int a;int b;\n  "); // the formatter splits it in two lines
    enableSaveActions();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(0, answer.asked.get(), "nothing to ask");
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("\tint a;\n\tint b;\n\tint count;"), "the save actions formatted the file: " + disk);
    assertEquals(disk, doc.get(), "and the result");
    MergeModel m = input.session().model();
    // the model saw the formatter's edits like any other edit; held, the block would still point one line up
    assertNotEquals("\tint value() {\n", m.resultText(0), "the block follows the formatting");
    assertFalse(java.isDirty());
    assertFalse(input.isDirty());
  }

  /** Commits the shared buffer from a job, as a background save would; a slow listener delays the write. */
  private static void commitFromAJob(IFile file) throws Exception {
    ITextFileBuffer b = buffer(file);
    org.eclipse.core.filebuffers.IFileBufferListener slow = new org.eclipse.core.filebuffers.IFileBufferListener() {
      @Override
      public void stateChanging(org.eclipse.core.filebuffers.IFileBuffer buffer) {
        if (buffer == b && Display.getCurrent() == null) {
          try {
            Thread.sleep(500); // the UI thread runs its events meanwhile
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }

      @Override
      public void bufferCreated(org.eclipse.core.filebuffers.IFileBuffer buffer) {}

      @Override
      public void bufferDisposed(org.eclipse.core.filebuffers.IFileBuffer buffer) {}

      @Override
      public void bufferContentAboutToBeReplaced(org.eclipse.core.filebuffers.IFileBuffer buffer) {}

      @Override
      public void bufferContentReplaced(org.eclipse.core.filebuffers.IFileBuffer buffer) {}

      @Override
      public void dirtyStateChanged(org.eclipse.core.filebuffers.IFileBuffer buffer, boolean isDirty) {}

      @Override
      public void stateValidationChanged(org.eclipse.core.filebuffers.IFileBuffer buffer, boolean validated) {}

      @Override
      public void underlyingFileMoved(org.eclipse.core.filebuffers.IFileBuffer buffer,
          org.eclipse.core.runtime.IPath path) {}

      @Override
      public void underlyingFileDeleted(org.eclipse.core.filebuffers.IFileBuffer buffer) {}

      @Override
      public void stateChangeFailed(org.eclipse.core.filebuffers.IFileBuffer buffer) {}
    };
    FileBuffers.getTextFileBufferManager().addFileBufferListener(slow);
    try {
      org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create("commit", monitor -> {
        try {
          b.commit(monitor, true);
        } catch (org.eclipse.core.runtime.CoreException e) {
          return e.getStatus();
        }
        return org.eclipse.core.runtime.Status.OK_STATUS;
      });
      job.schedule();
      ConflictRepos.waitUntil(() -> job.getResult() != null, 20, "the commit job");
      assertTrue(job.getResult().isOK(), job.getResult().toString());
    } finally {
      FileBuffers.getTextFileBufferManager().removeFileBufferListener(slow);
    }
    settle();
  }

  @Test
  void aCommitFromAnotherThreadWritesMarkers() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    String shown = documentOf(java).get();
    commitFromAJob(file);
    assertEquals(1, answer.asked.get());
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertTrue(disk.contains("// note"), disk);
    assertEquals(shown, documentOf(java).get());
    assertEquals(1, input.session().model().unresolvedConflicts());
  }

  @Test
  void aCancelledCommitFromAnotherThreadWritesNothing() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    MergeEditorInput input = open(file, answer);
    typeInJava(java);
    String shown = documentOf(java).get();
    commitFromAJob(file);
    assertEquals(1, answer.asked.get());
    assertArrayEquals(before, repos.bytes(JavaSources.PATH), "the file is byte-identical to W");
    assertEquals(shown, documentOf(java).get());
    assertTrue(java.isDirty());
    assertEquals(1, input.session().model().unresolvedConflicts());
  }

  @Test
  void aSecondSaveAsksAgain() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.CANCEL);
    open(file, answer);
    typeInJava(java);
    execute(java, "org.eclipse.ui.file.save");
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(2, answer.asked.get(), "each save asks");
  }

  @Test
  void guardIsRemovedWhenTheResolverCloses() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = open(file, answer);
    close(input);
    typeInJava(java);
    String typed = documentOf(java).get();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(0, answer.asked.get(), "the closed resolver is not asked");
    assertEquals(typed, repos.read(JavaSources.PATH), "the editor saves as usual");
    assertFalse(java.isDirty());
  }

  @Test
  void aPrivateResultLeavesSavingInTheJavaEditorAlone() throws Exception {
    String ours = JavaSources.OURS.replace("int count;", "int count; // a\rb");
    repos.conflict(JavaSources.PATH, JavaSources.BASE, ours, JavaSources.THEIRS);
    IFile file = repos.javaProject("foreign-save-private", JavaSources.PATH);
    ITextEditor java = javaEditor(file);
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS);
    MergeEditorInput input = ConflictRepos.open(file, answer);
    assertNull(input.viewer().sharedBuffer(), "the result is private");
    typeInJava(java);
    String typed = documentOf(java).get();
    execute(java, "org.eclipse.ui.file.save");
    assertEquals(0, answer.asked.get());
    assertEquals(typed, repos.read(JavaSources.PATH));
    assertFalse(java.isDirty());
  }

  @Test
  void markersSavedByTheResolverLeaveTheJavaEditorClean() throws Exception {
    IFile file = conflict();
    ITextEditor java = javaEditor(file);
    MergeEditorInput input = open(file, new Answer(SaveDecision.SaveChoice.MARKERS));
    input.saveResult(new org.eclipse.core.runtime.NullProgressMonitor());
    assertTrue(repos.read(JavaSources.PATH).contains("<<<<<<< "));
    assertFalse(java.isDirty(), "nothing is left to save in the other editor");
    close(input);
    assertEquals(repos.read(JavaSources.PATH), documentOf(java).get(), "closing shows the saved file");
    assertFalse(java.isDirty());
  }

  /**
   * Clicks the first button of the next message dialog that opens (no window manager on Xvfb). At the deadline
   * it closes any message dialog still open, so the test fails instead of hanging; {@link #stopDialogPoller}
   * ends it with the test.
   */
  private void pressFirstButtonOfNextMessageDialog() {
    Display d = Display.getCurrent();
    long deadline = System.currentTimeMillis() + 30_000;
    Runnable[] poll = new Runnable[1];
    poll[0] = () -> {
      if (poller != poll[0]) {
        return; // stopped
      }
      for (Shell s : d.getShells()) {
        Button b = s.getData() instanceof MessageDialog ? buttonWithId(s, 0) : null;
        if (b != null) {
          poller = null;
          b.notifyListeners(SWT.Selection, new Event());
          return;
        }
      }
      if (System.currentTimeMillis() < deadline) {
        d.timerExec(50, poll[0]);
        return;
      }
      poller = null;
      dialogTimedOut = true;
      for (Shell s : d.getShells()) {
        if (s.getData() instanceof org.eclipse.jface.dialogs.Dialog dialog) {
          dialog.close();
        }
      }
    };
    poller = poll[0];
    d.timerExec(50, poll[0]);
  }

  private Runnable poller;
  private boolean dialogTimedOut;

  @AfterEach
  void stopDialogPoller() {
    poller = null;
    assertFalse(dialogTimedOut, "the expected dialog did not open");
  }

  /** The dialog button created with {@code id} (JFace stores it as the button's data). */
  private static Button buttonWithId(Composite parent, int id) {
    for (Control c : parent.getChildren()) {
      if (c instanceof Button b && Integer.valueOf(id).equals(b.getData())) {
        return b;
      }
      if (c instanceof Composite inner) {
        Button found = buttonWithId(inner, id);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  void aCommitThatLandsAfterTheResolverStoppedWaitingMakesCloseRevert() throws Exception {
    IFile file = conflict();
    byte[] before = repos.bytes(JavaSources.PATH);
    MergeEditorInput input = open(file, new Answer(SaveDecision.SaveChoice.MARKERS));
    ForeignSaveGuard guard = input.foreignSaveGuard();
    SharedBuffer shared = input.viewer().sharedBuffer();
    ForeignSaveGuard.setOffUiTimeoutForTests(300);
    try {
      Thread commit = new Thread(guard::announceCommitForTests, "commit elsewhere"); // stateChanging off the UI thread
      commit.start();
      ConflictRepos.waitUntil(() -> !commit.isAlive(), 10, "the announced commit");
      ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the resolver giving up on it");
      assertEquals(0, guard.inFlight());
      assertFalse(shared.isDiverged());
      guard.dirtyStateChanged(buffer(file), false); // the commit lands after all
      assertTrue(shared.isDiverged(), "the buffer may show other text than the file");
      assertTrue(input.isDirty(), "nothing the user chose was saved");
    } finally {
      ForeignSaveGuard.setOffUiTimeoutForTests(-1);
    }
    close(input);
    assertNull(buffer(file), "the buffer connection is released");
    assertArrayEquals(before, repos.bytes(JavaSources.PATH));
  }

  @Test
  void aFailureThatLandsLateLeavesTheBufferAsItIs() throws Exception {
    IFile file = conflict();
    MergeEditorInput input = open(file, new Answer(SaveDecision.SaveChoice.MARKERS));
    ForeignSaveGuard guard = input.foreignSaveGuard();
    ForeignSaveGuard.setOffUiTimeoutForTests(300);
    try {
      Thread commit = new Thread(guard::announceCommitForTests, "commit elsewhere");
      commit.start();
      ConflictRepos.waitUntil(() -> !commit.isAlive(), 10, "the announced commit");
      ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the resolver giving up on it");
      guard.stateChangeFailed(buffer(file));
      guard.dirtyStateChanged(buffer(file), false); // a later clean report is not that commit any more
      assertFalse(input.viewer().sharedBuffer().isDiverged());
    } finally {
      ForeignSaveGuard.setOffUiTimeoutForTests(-1);
    }
  }

  /** Runs events until {@code millis} have passed. */
  private static void pumpFor(long millis) {
    long end = System.nanoTime() + millis * 1_000_000;
    ConflictRepos.waitUntil(() -> System.nanoTime() - end > 0, 30, "the time to pass");
  }

  @Test
  void aCommitOnAnotherThreadGetsItsFullTimeWhateverTheSaveTookBefore() throws Exception {
    IFile file = conflict();
    MergeEditorInput input = open(file, new Answer(SaveDecision.SaveChoice.MARKERS));
    ForeignSaveGuard guard = input.foreignSaveGuard();
    ForeignSaveGuard.setOffUiTimeoutForTests(1500);
    try {
      Thread first = new Thread(guard::announceCommitForTests, "first commit elsewhere");
      first.start();
      ConflictRepos.waitUntil(() -> !first.isAlive(), 10, "the first commit");
      pumpFor(1000); // the save has been going on for a while
      Thread second = new Thread(guard::announceCommitForTests, "second commit elsewhere");
      second.start();
      ConflictRepos.waitUntil(() -> !second.isAlive(), 10, "the second commit");
      Thread landed = new Thread(() -> guard.dirtyStateChanged(buffer(file), false), "first commit lands");
      landed.start();
      ConflictRepos.waitUntil(() -> !landed.isAlive(), 10, "the first commit's write");
      assertEquals(1, guard.inFlight());
      pumpFor(800); // 1.8 s into the save, 0.8 s after the second commit was announced
      assertTrue(input.foreignSaveInProgress(), "the second commit has time left");
      assertEquals(1, guard.inFlight());
      ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the resolver giving up on it");
      assertEquals(0, guard.inFlight());
    } finally {
      ForeignSaveGuard.setOffUiTimeoutForTests(-1);
    }
  }

  @Test
  void anErrorWhileAskingLeavesNoCommitInFlight() throws Exception {
    IFile file = conflict();
    Answer answer = new Answer(SaveDecision.SaveChoice.MARKERS) {
      @Override
      public SaveChoice onUnresolved(int unresolved) {
        if (asked.incrementAndGet() == 1) {
          throw new LinkageError("a half-installed dialog");
        }
        return choice;
      }
    };
    MergeEditorInput input = open(file, answer);
    IDocument doc = input.viewer().pane(PaneKind.RESULT).viewer().getDocument();
    String shown = doc.get();
    ForeignSaveGuard guard = input.foreignSaveGuard();
    assertThrows(LinkageError.class, guard::announceCommitForTests);
    assertEquals(0, guard.inFlight(), "nothing would ever end that commit");
    assertFalse(input.foreignSaveInProgress());
    assertEquals(shown, doc.get());
    guard.announceCommitForTests(); // the next save is asked and routed as usual
    assertEquals(2, answer.asked.get());
    assertTrue(doc.get().contains("<<<<<<< "), "the text to write is in place");
    ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the end of the second save");
    assertEquals(shown, doc.get());
    close(input);
    assertNull(buffer(file), "the buffer connection is released");
  }

  @Test
  void theTimeTheUserTakesToAnswerDoesNotCountAgainstACommitOnAnotherThread() throws Exception {
    IFile file = conflict();
    Answer slow = new Answer(SaveDecision.SaveChoice.MARKERS) {
      @Override
      public SaveChoice onUnresolved(int unresolved) {
        asked.incrementAndGet();
        pumpFor(2500); // longer than the commit may take
        return choice;
      }
    };
    MergeEditorInput input = open(file, slow);
    ForeignSaveGuard guard = input.foreignSaveGuard();
    java.util.List<org.eclipse.core.runtime.IStatus> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    org.eclipse.core.runtime.ILogListener l = (status, plugin) -> logged.add(status);
    org.eclipse.core.runtime.Platform.addLogListener(l);
    ForeignSaveGuard.setOffUiTimeoutForTests(1500);
    try {
      Thread commit = new Thread(guard::announceCommitForTests, "commit elsewhere");
      commit.start();
      ConflictRepos.waitUntil(() -> !commit.isAlive(), 10, "the answered commit");
      pumpFor(500);
      assertTrue(input.foreignSaveInProgress(), "the commit was answered just now: it is still waited for");
      assertEquals(1, guard.inFlight());
      Thread landed = new Thread(() -> guard.dirtyStateChanged(buffer(file), false), "the commit lands");
      landed.start();
      ConflictRepos.waitUntil(() -> !landed.isAlive(), 10, "the commit's write");
      ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the end of the save");
      assertEquals(0, guard.inFlight());
      assertFalse(input.isDirty(), "the markers the user chose were saved");
      assertTrue(logged.stream().noneMatch(st -> st.getSeverity() == org.eclipse.core.runtime.IStatus.WARNING),
          "neither given up on nor late: " + logged);
      assertEquals(1, slow.asked.get());
    } finally {
      org.eclipse.core.runtime.Platform.removeLogListener(l);
      ForeignSaveGuard.setOffUiTimeoutForTests(-1);
    }
  }
}
