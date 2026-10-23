package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Save actions of another Java editor reformat the shared buffer: every block stays on its own lines. */
class FormatOnSaveTest {
  static final String PATH = "src/p/F.java";
  static final String BASE = "package p;\n\npublic class F {\n  int count;\n\n  int value() {\n    return 0;\n  }\n\n"
      + "  int other() {\n    return 1;\n  }\n}\n";
  static final String OURS = BASE.replace("return 0;", "return count;").replace("return 1;", "return 2;");
  static final String THEIRS = BASE.replace("return 0;", "return count + 1;");
  private static final String[] FORMAT_ON_SAVE = {
      "editor_save_participant_org.eclipse.jdt.ui.postsavelistener.cleanup", "true",
      "sp_cleanup.on_save_use_additional_actions", "false",
      "sp_cleanup.format_source_code", "true",
      "sp_cleanup.format_source_code_changes_only", "false"};

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    var node = InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
    for (int i = 0; i < FORMAT_ON_SAVE.length; i += 2) {
      node.remove(FORMAT_ON_SAVE[i]);
    }
    net.aftergit.merge.jdt.JavaPanes.setSaveFailureForTests(null);
    MergePreferences.reset();
    repos.dispose();
  }

  private static int occurrences(String text, String part) {
    int n = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
      n++;
    }
    return n;
  }

  private static void formatOnSave() {
    var node = InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
    for (int i = 0; i < FORMAT_ON_SAVE.length; i += 2) {
      node.put(FORMAT_ON_SAVE[i], FORMAT_ON_SAVE[i + 1]);
    }
  }

  private MergeEditorInput resolvedJava(String project) throws Exception {
    repos.conflict(PATH, BASE, OURS, THEIRS);
    IFile file = repos.javaProject(project, PATH);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeModel m = input.session().model();
    input.viewer().selectBlock(BlockLocator.nextConflict(m, -1, true));
    input.viewer().takeLeft();
    assertEquals(0, m.unresolvedConflicts());
    return input;
  }

  @Test
  void theResolversSaveAppliesTheJavaSaveActionsWhenSwitchedOn() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-on");
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    String saved = repos.read(PATH);
    assertTrue(saved.contains("\t\treturn count;"), "the save actions formatted the file: " + saved);
    assertFalse(UiDriver.editor(input).isDirty());
    assertFalse(input.foreignSaveInProgress(), "our own save, not another editor's");
  }

  private static java.util.List<BlockState> states(MergeModel m) {
    return m.blocks().stream().map(MergeBlock::state).toList();
  }

  @Test
  void theSaveActionsKeepTheStatesOfTheResolvedBlocks() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-states");
    MergeModel m = input.session().model();
    var before = states(m);
    int review = m.needsReviewCount();
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    String saved = repos.read(PATH);
    assertTrue(saved.contains("\t\treturn count;"), saved);
    assertEquals(before, states(m), "the formatting is no edit of the user's");
    assertFalse(states(m).contains(BlockState.EDITED));
    assertEquals(review, m.needsReviewCount());
    assertEquals(saved, input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get(), "the result shows the file");
    assertFalse(UiDriver.editor(input).isDirty());
  }

  @Test
  void undoAfterTheSaveTakesBackTheSaveActionsLikeInAJavaEditor() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-undo");
    MergeModel m = input.session().model();
    String unformatted = input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
    var before = states(m);
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    input.viewer().undo();
    UiDriver.flush();
    assertEquals(unformatted, input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get(),
        "one undo step takes the formatting back");
    assertEquals(before, states(m));
    assertTrue(UiDriver.editor(input).isDirty(), "the result differs from the file again");
    input.viewer().redo();
    UiDriver.flush();
    assertEquals(repos.read(PATH), input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get());
    assertEquals(before, states(m));
  }

  @Test
  void aJavaFileOutsideAJavaProjectIsSavedWithTheSwitchOn() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    repos.conflict(PATH, BASE, OURS, THEIRS);
    IFile file = repos.plainProject("save-actions-plain", PATH);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeModel m = input.session().model();
    input.viewer().selectBlock(BlockLocator.nextConflict(m, -1, true));
    input.viewer().takeLeft();
    UiDriver.editor(input).doSave(new NullProgressMonitor());
    UiDriver.flush();
    String saved = repos.read(PATH);
    assertTrue(saved.contains("return count;"), saved);
    assertFalse(saved.contains("<<<<<<< "), saved);
    assertFalse(UiDriver.editor(input).isDirty());
  }

  private static boolean conflicted(IFile file) throws Exception {
    var rf = net.aftergit.merge.git.RepositoryLocator.locate(file).orElseThrow();
    return net.aftergit.merge.git.ConflictState.isConflicted(rf.repository(), rf.path());
  }

  /** Errors the resolver reports, by title, instead of showing them in a dialog. */
  private static java.util.List<String> reportErrors(MergeEditorInput input) {
    var titles = new java.util.ArrayList<String>();
    input.setTitledErrorReporter((title, status) -> titles.add(title));
    return titles;
  }

  @Test
  void aSaveActionThatFailsIsAFailedSave() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-fail");
    var errors = reportErrors(input);
    byte[] before = repos.bytes(PATH);
    net.aftergit.merge.jdt.JavaPanes.setSaveFailureForTests(new IllegalStateException("a broken save participant"));
    UiDriver.editor(input).doSave(new NullProgressMonitor());
    UiDriver.flush();
    assertEquals(java.util.List.of(Messages.saveFailedTitle), errors);
    assertArrayEquals(before, repos.bytes(PATH), "nothing written");
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void aSaveActionThatFailsWithoutAMessageNamesItsKind() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-fail-silent");
    var messages = new java.util.ArrayList<String>();
    input.setTitledErrorReporter((title, status) -> collect(status, messages));
    net.aftergit.merge.jdt.JavaPanes.setSaveFailureForTests(new IllegalStateException());
    UiDriver.editor(input).doSave(new NullProgressMonitor());
    UiDriver.flush();
    String all = String.join("\n", messages);
    assertTrue(all.contains("IllegalStateException"), all);
    assertFalse(all.contains("null"), all);
  }

  private static void collect(org.eclipse.core.runtime.IStatus status, java.util.List<String> into) {
    into.add(status.getMessage());
    for (var child : status.getChildren()) {
      collect(child, into);
    }
  }

  @Test
  void aSaveActionThatFailsStagesNothingOnSaveAndMarkResolved() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-fail-finish");
    var errors = reportErrors(input);
    byte[] before = repos.bytes(PATH);
    net.aftergit.merge.jdt.JavaPanes.setSaveFailureForTests(new IllegalStateException("a broken save participant"));
    assertEquals(MergeEditorInput.FinishOutcome.SAVE_FAILED, input.saveAndMarkResolved(new NullProgressMonitor()));
    UiDriver.flush();
    assertEquals(java.util.List.of(Messages.saveFailedTitle), errors);
    assertArrayEquals(before, repos.bytes(PATH), "nothing written");
    assertTrue(conflicted(input.file()), "nothing staged");
    assertNotNull(UiDriver.editor(input), "the resolver stays open");
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void saveAndMarkResolvedStagesTheTextTheSaveActionsWrote() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    MergeEditorInput input = resolvedJava("save-actions-finish");
    var errors = reportErrors(input);
    IFile file = input.file();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    UiDriver.flush();
    assertTrue(errors.isEmpty(), errors.toString());
    assertTrue(repos.read(PATH).contains("\t\treturn count;"), repos.read(PATH));
    assertFalse(conflicted(file), "staged");
  }

  @Test
  void byDefaultTheResolverSavesItsTextAsItIs() throws Exception {
    formatOnSave();
    assertFalse(MergePreferences.saveActions(), "off by default: the resolver ran no Save Actions before");
    MergeEditorInput input = resolvedJava("save-actions-off");
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    String saved = repos.read(PATH);
    assertTrue(saved.contains("    return count;"), saved);
    assertFalse(UiDriver.editor(input).isDirty());
  }

  @Test
  void aSaveWithOpenConflictsNeverRunsTheSaveActions() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    repos.conflict(PATH, BASE, OURS, THEIRS);
    IFile file = repos.javaProject("save-actions-markers", PATH);
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertEquals(1, input.session().model().unresolvedConflicts());
    UiDriver.editor(input).doSave(new NullProgressMonitor()); // saved as markers, as answered
    UiDriver.flush();
    String saved = repos.read(PATH);
    assertTrue(saved.contains("<<<<<<< "), saved);
    assertFalse(saved.contains("\t\treturn"), "markers are written as they are: " + saved);
  }

  @Test
  void theCurrentTextWithOpenConflictsIsWrittenWithoutTheSaveActions() throws Exception {
    formatOnSave();
    MergePreferences.setSaveActions(true);
    repos.conflict(PATH, BASE, OURS, THEIRS);
    IFile file = repos.javaProject("save-actions-current", PATH);
    MergeEditorInput input = ConflictRepos.open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    assertEquals(1, input.session().model().unresolvedConflicts());
    UiDriver.editor(input).doSave(new NullProgressMonitor());
    UiDriver.flush();
    String saved = repos.read(PATH);
    assertFalse(saved.contains("<<<<<<< "), saved);
    assertTrue(saved.contains("    return 2;"), "written as it is: " + saved);
  }

  @Test
  void formatOnSaveKeepsBlocksAndTakeDoesNotDuplicate() throws Exception {
    repos.conflict(PATH, BASE, OURS, THEIRS);
    IFile file = repos.javaProject("format-it", PATH);
    ITextEditor java = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file, JavaUI.ID_CU_EDITOR);
    MergeEditorInput input = ConflictRepos.open(file,
        ConflictRepos.answer(SaveDecision.SaveChoice.CURRENT_TEXT, false));
    assertNotNull(input.viewer().sharedBuffer(), "the result is the Java editor's document");
    MergeModel m = input.session().model();
    int conflict = BlockLocator.nextConflict(m, -1, true);
    int git = m.blocks().stream().filter(b -> b.state() == BlockState.RESOLVED_BY_GIT)
        .mapToInt(MergeBlock::index).findFirst().orElseThrow();
    var node = InstanceScope.INSTANCE.getNode("org.eclipse.jdt.ui");
    for (int i = 0; i < FORMAT_ON_SAVE.length; i += 2) {
      node.put(FORMAT_ON_SAVE[i], FORMAT_ON_SAVE[i + 1]);
    }
    ConflictRepos.page().activate(java);
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class)
        .executeCommand("org.eclipse.ui.file.save", null);
    while (Display.getCurrent().readAndDispatch()) {
      // the save's follow-up work
    }
    ConflictRepos.waitUntil(() -> !input.foreignSaveInProgress(), 10, "the end of the save");
    assertTrue(repos.read(PATH).contains("\t\treturn 2;"), "the save actions formatted the file");
    assertEquals("\t\treturn 0;\n", m.resultText(conflict), "the conflict still holds its own line");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(conflict).state(), "formatting resolves no conflict");
    assertEquals(1, m.unresolvedConflicts());
    assertEquals("\t\treturn 2;\n", m.resultText(git));
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(git).state(), "formatted by the save, not edited");
    input.viewer().selectBlock(conflict);
    input.viewer().takeLeft();
    String text = input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
    assertFalse(text.contains("return 0;"), text);
    assertEquals(1, occurrences(text, "return count;"), text);
    assertEquals(2, occurrences(text, "return "), text);
  }
}
