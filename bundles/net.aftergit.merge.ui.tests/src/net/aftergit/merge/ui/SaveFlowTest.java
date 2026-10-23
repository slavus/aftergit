package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ui-polish design §3: Save & Mark Resolved saves, stages and closes; with open conflicts it asks first. */
class SaveFlowTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  final List<String> status = new ArrayList<>();
  final List<IStatus> errors = new ArrayList<>();
  final List<String> errorTitles = new ArrayList<>();

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
    MergePreferences.reset();
  }

  private MergeEditorInput open(IFile file, SaveDecision decision) {
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(decision);
    input.setStatusReporter(status::add);
    input.setTitledErrorReporter((title, error) -> {
      errorTitles.add(title);
      errors.add(error);
    });
    ConflictRepos.open(input);
    ConflictRepos.page().activate(UiDriver.editor(input));
    UiDriver.flush();
    return input;
  }

  private IFile twoConflicts(String project) throws Exception {
    repos.conflict("a.txt", "a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n");
    return repos.plainProject(project, "a.txt");
  }

  private static boolean conflicted(IFile file) throws Exception {
    var rf = RepositoryLocator.locate(file).orElseThrow();
    return ConflictState.isConflicted(rf.repository(), rf.path());
  }

  private static boolean isOpen(MergeEditorInput input) {
    return UiDriver.editor(input) != null;
  }

  private static void waitClosed(MergeEditorInput input) {
    ConflictRepos.waitUntil(() -> !isOpen(input), 10, "the resolver to close");
  }

  private static void takeAllRight(MergeEditorInput input) {
    MergeViewer v = input.viewer();
    for (int i = 0; i < 10 && input.session().model().unresolvedConflicts() > 0; i++) {
      v.nextConflict();
      v.takeRight();
    }
    assertEquals(0, input.session().model().unresolvedConflicts());
  }

  private static byte[] latin1(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void allResolvedSavesStagesClosesAndSaysSo() throws Exception {
    IFile file = twoConflicts("finish-resolved");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    takeAllRight(input);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals("a\nR1\nc\nR3\ne\n", repos.read("a.txt"));
    assertFalse(conflicted(file), "staged");
    waitClosed(input);
    assertEquals(List.of("a.txt resolved and staged"), status);
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void unreviewedAutoResolvedBlocksAreNamedButDoNotBlock() throws Exception {
    repos.conflict("a.txt", "x a z b y\n", "x A z b y\n", "x a z B y\n");
    IFile file = repos.plainProject("finish-auto", "a.txt");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    assertEquals(0, input.session().model().unresolvedConflicts());
    assertEquals(1, input.session().model().needsReviewCount());
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertFalse(conflicted(file));
    waitClosed(input);
    assertEquals(List.of("a.txt resolved and staged · 1 auto-resolved block not reviewed"), status);
  }

  @Test
  void withOpenConflictsCancelWritesNothing() throws Exception {
    IFile file = twoConflicts("finish-cancel");
    int[] asked = {0};
    MergeEditorInput input = open(file, new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        return fail("Cancel never saves");
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        asked[0] = unresolved;
        return SaveDecision.FinishChoice.CANCEL;
      }
    });
    byte[] before = repos.bytes("a.txt");
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.CANCELLED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(1, asked[0], "the question names the open conflicts");
    assertArrayEquals(before, repos.bytes("a.txt"));
    assertTrue(conflicted(file));
    assertTrue(isOpen(input));
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void saveWithoutStagingSavesLikeCtrlSAndKeepsTheEditorOpen() throws Exception {
    IFile file = twoConflicts("finish-without-staging");
    MergeEditorInput input = open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, SaveDecision.FinishChoice.SAVE_WITHOUT_STAGING));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.SAVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    String saved = repos.read("a.txt");
    assertTrue(saved.startsWith("a\nL1\nc\n<<<<<<< "), saved);
    assertTrue(conflicted(file), "not staged");
    UiDriver.flush();
    assertTrue(isOpen(input));
    assertFalse(UiDriver.editor(input).isDirty());
    assertTrue(status.isEmpty());
  }

  @Test
  void saveWithMarkersWritesTheRepositoryStyleAndCloses() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n");
    try (Git git = Git.open(dir)) {
      StoredConfig config = git.getRepository().getConfig();
      config.setString("merge", null, "conflictStyle", "diff3");
      config.save();
    }
    IFile file = repos.plainProject("finish-markers", "a.txt");
    MergeEditorInput input = open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, SaveDecision.FinishChoice.SAVE_WITH_MARKERS));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    String saved = repos.read("a.txt");
    assertTrue(saved.startsWith("a\nL1\nc\n<<<<<<< "), saved);
    assertTrue(saved.contains("\n||||||| "), "the diff3 base section: " + saved);
    assertTrue(conflicted(file), "not staged");
    waitClosed(input);
    assertTrue(status.isEmpty());
  }

  @Test
  void aFailedStageKeepsTheSavedFileAndTheOpenEditor() throws Exception {
    IFile file = twoConflicts("finish-stage-fails");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    takeAllRight(input);
    File lock = new File(dir, ".git/index.lock");
    assertTrue(lock.createNewFile(), "another git holds the index");
    try {
      assertEquals(MergeEditorInput.FinishOutcome.STAGE_FAILED,
          input.saveAndMarkResolved(new NullProgressMonitor()));
    } finally {
      assertTrue(lock.delete());
    }
    assertEquals("a\nR1\nc\nR3\ne\n", repos.read("a.txt"), "the save stays");
    assertTrue(conflicted(file), "nothing staged");
    assertEquals(1, errors.size());
    assertEquals(List.of(Messages.stageFailedTitle), errorTitles, "not called a failed save");
    UiDriver.flush();
    assertTrue(isOpen(input));
    assertFalse(UiDriver.editor(input).isDirty());
    assertTrue(status.isEmpty());
  }

  @Test
  void aFailedSaveStagesNothingAndKeepsTheEditorDirty() throws Exception {
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nLé\nc\n"), latin1("a\nR\nc\n"));
    IFile file = repos.plainProject("finish-save-fails", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    // ISO-8859-1 (the fallback for the invalid bytes) cannot hold the euro sign: the save fails
    input.viewer().pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "€");
    byte[] before = repos.bytes("a.txt");
    assertEquals(MergeEditorInput.FinishOutcome.SAVE_FAILED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(1, errors.size());
    assertArrayEquals(before, repos.bytes("a.txt"));
    assertTrue(conflicted(file), "nothing staged");
    assertEquals(List.of(Messages.saveFailedTitle), errorTitles);
    UiDriver.flush();
    assertTrue(isOpen(input));
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void ctrlSOnlySaves() throws Exception {
    IFile file = twoConflicts("finish-ctrl-s");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    takeAllRight(input);
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    assertEquals("a\nR1\nc\nR3\ne\n", repos.read("a.txt"));
    assertTrue(conflicted(file), "Ctrl+S never stages");
    assertTrue(isOpen(input), "Ctrl+S never closes");
    assertTrue(status.isEmpty());
  }

  @Test
  void aDirtyResolverKeepsTheStandardSaveQuestionOnClose() throws Exception {
    MergeEditorInput input = open(twoConflicts("finish-close"), ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    var editor = UiDriver.editor(input);
    assertTrue(editor.isDirty());
    assertTrue(((ISaveablePart) editor).isSaveOnCloseNeeded(), "Eclipse asks Save / Don't Save / Cancel");
  }

  @Test
  void saveWithoutStagingThenCancelWritesNothing() throws Exception {
    IFile file = twoConflicts("finish-without-staging-cancel");
    MergeEditorInput input = open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, SaveDecision.FinishChoice.SAVE_WITHOUT_STAGING));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    byte[] before = repos.bytes("a.txt");
    assertEquals(MergeEditorInput.FinishOutcome.CANCELLED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertArrayEquals(before, repos.bytes("a.txt"));
    assertTrue(conflicted(file));
    UiDriver.flush();
    assertTrue(isOpen(input));
    assertTrue(UiDriver.editor(input).isDirty());
    assertTrue(status.isEmpty());
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void aFailedSaveWithMarkersKeepsTheEditorOpenAndDirty() throws Exception {
    repos.conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nL\u00e9\nc\n"), latin1("a\nR\nc\n"));
    IFile file = repos.plainProject("finish-markers-fail", "a.txt");
    file.getProject().setDefaultCharset("UTF-8", null);
    MergeEditorInput input = open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, SaveDecision.FinishChoice.SAVE_WITH_MARKERS));
    // ISO-8859-1 (the fallback for the invalid bytes) cannot hold the euro sign: the save fails
    input.viewer().pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "\u20ac");
    assertTrue(input.session().model().unresolvedConflicts() > 0);
    byte[] before = repos.bytes("a.txt");
    assertEquals(MergeEditorInput.FinishOutcome.SAVE_FAILED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(1, errors.size());
    assertArrayEquals(before, repos.bytes("a.txt"));
    assertTrue(conflicted(file));
    UiDriver.flush();
    assertTrue(isOpen(input));
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void anEditBeforeTheCloseKeepsTheResolverOpen() throws Exception {
    IFile file = twoConflicts("finish-edit-before-close");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    takeAllRight(input);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    // typed before the asynchronous close runs
    input.viewer().pane(PaneKind.RESULT).viewer().getDocument().replace(0, 0, "typed\n");
    UiDriver.flush();
    assertTrue(isOpen(input), "never closed with unsaved changes");
    assertTrue(UiDriver.editor(input).isDirty());
    assertTrue(input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get().startsWith("typed\n"));
    assertEquals("a\nR1\nc\nR3\ne\n", repos.read("a.txt"), "the saved text is on disk");
    assertFalse(conflicted(file), "staged with the saved text");
    assertEquals(List.of(MessageFormat.format(Messages.finishStagedKeptOpen, "a.txt")), status);
  }

  @Test
  void anEditBeforeTheCloseAfterSaveWithMarkersKeepsTheResolverOpen() throws Exception {
    IFile file = twoConflicts("finish-markers-edit-before-close");
    MergeEditorInput input = open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, SaveDecision.FinishChoice.SAVE_WITH_MARKERS));
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    UiDriver.flush();
    assertTrue(isOpen(input), "never closed with unsaved changes");
    assertTrue(UiDriver.editor(input).isDirty());
    assertTrue(repos.read("a.txt").startsWith("a\n<<<<<<< "), "the markers are on disk");
    assertTrue(conflicted(file));
    assertEquals(List.of(MessageFormat.format(Messages.finishKeptOpen, "a.txt")), status);
  }

  private MergeEditorInput openJava(IFile file, SaveDecision decision) {
    MergeEditorInput input = open(file, decision);
    assertEquals(ResultKind.JAVA_EDITOR, input.viewer().resultKind());
    return input;
  }

  private static IDocument doc(ITextEditor e) {
    return e.getDocumentProvider().getDocument(e.getEditorInput());
  }

  @Test
  void resolvedWithAJavaEditorOfTheFileOpen() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.javaProject("finish-java-resolved", JavaSources.PATH);
    ITextEditor java = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
    MergeEditorInput input = openJava(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    waitClosed(input);
    UiDriver.flush();
    assertEquals(JavaSources.OURS, repos.read(JavaSources.PATH));
    assertFalse(conflicted(file));
    assertFalse(java.isDirty());
    assertEquals(JavaSources.OURS, doc(java).get());
    assertEquals(1, status.size(), status.toString());
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void saveWithMarkersWithAJavaEditorOfTheFileOpen() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.javaProject("finish-java-markers", JavaSources.PATH);
    ITextEditor java = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
    MergeEditorInput input = openJava(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, SaveDecision.FinishChoice.SAVE_WITH_MARKERS));
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    waitClosed(input);
    UiDriver.flush();
    String disk = repos.read(JavaSources.PATH);
    assertTrue(disk.contains("<<<<<<< "), disk);
    assertTrue(conflicted(file));
    assertFalse(java.isDirty());
    assertEquals(disk, doc(java).get());
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void anEditOfTheSharedDocumentBeforeTheCloseIsKept() throws Exception {
    repos.conflict(JavaSources.PATH, JavaSources.BASE, JavaSources.OURS, JavaSources.THEIRS);
    IFile file = repos.javaProject("finish-java-edit-before-close", JavaSources.PATH);
    ITextEditor java = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
    MergeEditorInput input = openJava(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    IDocument d = doc(java);
    d.replace(d.get().indexOf("int count;"), 0, "// typed\n  ");
    UiDriver.flush();
    assertTrue(isOpen(input), "never closed with unsaved changes");
    assertTrue(UiDriver.editor(input).isDirty());
    assertTrue(doc(java).get().contains("// typed"), "the edit survives in the shared document");
    assertEquals(JavaSources.OURS, repos.read(JavaSources.PATH), "the saved text is on disk");
    assertFalse(conflicted(file), "staged with the saved text");
    assertEquals(List.of(MessageFormat.format(Messages.finishStagedKeptOpen, file.getName())), status);
  }

  private static Shell shellTitled(String title) {
    for (Shell s : Display.getCurrent().getShells()) {
      if (title.equals(s.getText()) && s.isVisible()) {
        return s;
      }
    }
    throw new AssertionError("no dialog '" + title + "'");
  }

  private static void collect(Control c, List<Button> buttons, List<String> labels) {
    if (c instanceof Button b && (b.getStyle() & SWT.PUSH) != 0) {
      buttons.add(b);
    } else if (c instanceof Label l) {
      labels.add(l.getText());
    } else if (c instanceof Composite comp) {
      for (Control child : comp.getChildren()) {
        collect(child, buttons, labels);
      }
    }
  }

  @Test
  void theFinishDialogDefaultsToCancelAndEscCancels() {
    Shell parent = ConflictRepos.page().getWorkbenchWindow().getShell();
    SaveDecision dialogs = SaveDecision.dialogs(parent);
    Set<String> buttons = new HashSet<>();
    List<String> labels = new ArrayList<>();
    String[] defaultButton = {null};
    Display.getCurrent().asyncExec(() -> {
      Shell dialog = shellTitled(Messages.finishTitle);
      List<Button> found = new ArrayList<>();
      collect(dialog, found, labels);
      found.forEach(b -> buttons.add(b.getText()));
      defaultButton[0] = dialog.getDefaultButton() == null ? null : dialog.getDefaultButton().getText();
      dialog.close(); // as Esc or the close button of the window
    });
    assertEquals(SaveDecision.FinishChoice.CANCEL, dialogs.onFinishWithConflicts(2));
    assertEquals(Set.of(Messages.finishCancel, Messages.finishSaveWithoutStaging, Messages.finishSaveWithMarkers),
        buttons);
    assertEquals(Messages.finishCancel, defaultButton[0], "Cancel is the default");
    assertTrue(labels.stream().anyMatch(t -> t.contains("2 open conflicts")), labels.toString());
    Display.getCurrent().asyncExec(() -> {
      List<Button> found = new ArrayList<>();
      collect(shellTitled(Messages.finishTitle), found, new ArrayList<>());
      Button markers = found.stream().filter(b -> Messages.finishSaveWithMarkers.equals(b.getText())).findFirst()
          .orElseThrow();
      markers.notifyListeners(SWT.Selection, new Event());
    });
    assertEquals(SaveDecision.FinishChoice.SAVE_WITH_MARKERS, dialogs.onFinishWithConflicts(1));
  }

  @Test
  void withSaveWithMarkersChosenTheResolverDoesNotAsk() throws Exception {
    MergePreferences.setFinishPolicy(MergePreferences.FinishPolicy.SAVE_WITH_MARKERS);
    IFile file = twoConflicts("finish-pref-markers");
    MergeEditorInput input = open(file, new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        return fail("the preference answers");
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        return fail("the preference answers");
      }
    });
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(repos.read("a.txt").startsWith("a\nL1\nc\n<<<<<<< "));
    assertTrue(conflicted(file));
    waitClosed(input);
  }

  @Test
  void withSaveWithoutStagingChosenTheResolverSavesAndStaysOpen() throws Exception {
    MergePreferences.setFinishPolicy(MergePreferences.FinishPolicy.SAVE_WITHOUT_STAGING);
    IFile file = twoConflicts("finish-pref-without-staging");
    MergeEditorInput input = open(file, new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        return SaveDecision.SaveChoice.MARKERS; // as Ctrl+S: how to write the open conflicts is still asked
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        return fail("the preference answers");
      }
    });
    input.viewer().nextConflict();
    input.viewer().takeLeft();
    assertEquals(MergeEditorInput.FinishOutcome.SAVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(conflicted(file), "not staged");
    UiDriver.flush();
    assertTrue(isOpen(input));
  }

  @Test
  void withCloseAfterFinishOffTheResolvedFileStaysOpen() throws Exception {
    MergePreferences.setCloseAfterFinish(false);
    IFile file = twoConflicts("finish-pref-keep-open");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    takeAllRight(input);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertFalse(conflicted(file), "staged");
    UiDriver.flush();
    UiDriver.flush();
    assertTrue(isOpen(input), "the editor stays open");
    assertEquals(List.of("a.txt resolved and staged"), status);
  }

  @Test
  void withCloseAfterFinishOffSavingWithMarkersStaysOpen() throws Exception {
    MergePreferences.setCloseAfterFinish(false);
    MergePreferences.setFinishPolicy(MergePreferences.FinishPolicy.SAVE_WITH_MARKERS);
    IFile file = twoConflicts("finish-pref-markers-keep-open");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    assertEquals(MergeEditorInput.FinishOutcome.SAVED_WITH_MARKERS,
        input.saveAndMarkResolved(new NullProgressMonitor()));
    UiDriver.flush();
    UiDriver.flush();
    assertTrue(isOpen(input), "the editor stays open");
    assertTrue(conflicted(file));
    assertEquals(List.of(org.eclipse.osgi.util.NLS.bind(Messages.finishSavedWithMarkers, "a.txt")), status,
        "the status line says what happened");
  }

  @Test
  void theTooltipOfSaveAndMarkResolvedFollowsTheCloseSwitch() throws Exception {
    IFile file = twoConflicts("finish-pref-tooltip");
    MergeEditorInput input = open(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    MergeToolbar t = input.viewer().toolbar();
    t.refreshKeyHints();
    assertTrue(t.primaryButton().getToolTipText().startsWith(Messages.saveAndMarkResolvedTooltip),
        t.primaryButton().getToolTipText());
    MergePreferences.setCloseAfterFinish(false);
    t.refreshKeyHints();
    assertTrue(t.primaryButton().getToolTipText().startsWith(Messages.saveAndMarkResolvedKeepOpenTooltip),
        t.primaryButton().getToolTipText());
    assertFalse(t.primaryButton().getToolTipText().contains("close"), t.primaryButton().getToolTipText());
  }
}
