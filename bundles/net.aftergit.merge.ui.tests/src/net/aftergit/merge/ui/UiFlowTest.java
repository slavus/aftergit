package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PartInitException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec §10.6 flows through the opened Compare editor, driven by widget events and commands. */
class UiFlowTest {
  private static final String BASE = "a\nb\nc\nd\ne\nf\ng\n";
  private static final String OURS = "a\nL1\nc\nL3\ne\nL5\ng\n";
  private static final String THEIRS = "a\nR1\nc\nR3\ne\nR5\ng\n";

  @TempDir
  File dir;
  ConflictRepos repos;

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

  private IFile threeConflicts() throws Exception {
    repos.conflict("a.txt", BASE, OURS, THEIRS);
    return repos.plainProject("ui-flow", "a.txt");
  }

  private static MergeEditorInput open(IFile file) {
    return UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static String result(MergeEditorInput in) {
    return in.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  private static BlockState state(MergeEditorInput in, int block) {
    return in.session().model().blocks().get(block).state();
  }

  private static MergeEditorInput openedFor(IFile file) {
    for (IEditorReference r : ConflictRepos.page().getEditorReferences()) {
      try {
        if (r.getEditorInput() instanceof MergeEditorInput m && m.equals(new MergeEditorInput(file))) {
          return m;
        }
      } catch (PartInitException e) {
        // not restored: not ours
      }
    }
    return null;
  }

  @Test
  void theResolveCommandOpensTheEditorForTheSelectedFile() throws Exception {
    IFile file = threeConflicts();
    UiDriver.commandOn("net.aftergit.merge.commands.resolve", new StructuredSelection(file));
    ConflictRepos.waitUntil(() -> openedFor(file) != null && openedFor(file).viewer() != null, 10,
        "the resolver of a.txt");
    MergeEditorInput input = openedFor(file);
    assertEquals(String.format(Messages.editorTitle, "a.txt"), input.getTitle());
    assertEquals(3, input.session().model().unresolvedConflicts());
    assertEquals(BASE, result(input), "unresolved blocks show the base text");
  }

  @Test
  void toolbarTakeRevertAndUndoRedoKeepStates() throws Exception {
    MergeEditorInput input = open(threeConflicts());
    MergeViewer v = input.viewer();
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.click(v, Messages.takeRight);
    assertEquals(BlockState.TAKEN_RIGHT, state(input, 0));
    assertEquals("a\nR1\nc\nd\ne\nf\ng\n", result(input));
    UiDriver.clickMore(v, Messages.revert);
    assertEquals(BlockState.UNRESOLVED, state(input, 0));
    assertEquals(BASE, result(input));
    UiDriver.command(IWorkbenchCommandConstants.EDIT_UNDO);
    assertEquals(BlockState.TAKEN_RIGHT, state(input, 0));
    assertEquals("a\nR1\nc\nd\ne\nf\ng\n", result(input));
    UiDriver.command(IWorkbenchCommandConstants.EDIT_REDO);
    assertEquals(BlockState.UNRESOLVED, state(input, 0));
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.clickDropDownItem(v, Messages.takeBoth, Messages.takeLeftThenRight); // overflows into More on a narrow editor
    assertEquals(BlockState.TAKEN_BOTH, state(input, 1));
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.click(v, Messages.ignore);
    assertEquals(BlockState.IGNORED, state(input, 2));
    assertEquals("a\nb\nc\nL3\nR3\ne\nf\ng\n", result(input));
    assertTrue(UiDriver.editor(input).isDirty());
  }

  @Test
  void connectorButtonsTakeAppendReplaceAndIgnore() throws Exception {
    MergeEditorInput input = open(threeConflicts());
    MergeViewer v = input.viewer();
    UiDriver.clickConnector(v, PaneKind.LEFT, 0, true, 0);
    assertEquals(BlockState.TAKEN_LEFT, state(input, 0));
    UiDriver.clickConnector(v, PaneKind.RIGHT, 0, true, 0); // the other side is appended below
    assertEquals(BlockState.TAKEN_BOTH, state(input, 0));
    UiDriver.clickConnector(v, PaneKind.RIGHT, 1, true, 0);
    UiDriver.clickConnector(v, PaneKind.LEFT, 1, true, SWT.MOD1); // Ctrl+click replaces
    assertEquals(BlockState.TAKEN_LEFT, state(input, 1));
    UiDriver.clickConnector(v, PaneKind.LEFT, 2, false, 0);
    assertEquals(BlockState.IGNORED, state(input, 2));
    assertEquals("a\nL1\nR1\nc\nL3\ne\nf\ng\n", result(input));
    UiDriver.command(IWorkbenchCommandConstants.EDIT_UNDO); // the ignore
    UiDriver.command(IWorkbenchCommandConstants.EDIT_UNDO); // the Ctrl+click replace, one step
    assertEquals(BlockState.TAKEN_RIGHT, state(input, 1));
    assertEquals(BlockState.UNRESOLVED, state(input, 2));
  }

  @Test
  void layoutButtonsSwitchTheLayoutAndAreRemembered() throws Exception {
    MergeEditorInput input = open(threeConflicts());
    MergeViewer v = input.viewer();
    UiDriver.click(v, Messages.layoutResultBelow);
    assertEquals(PaneLayout.RESULT_BELOW, v.paneLayout());
    assertFalse(v.connector(PaneKind.LEFT).isVisible());
    assertEquals(PaneLayout.RESULT_BELOW, MergePreferences.layout());
    UiDriver.click(v, Messages.layoutLeftResult);
    assertFalse(v.pane(PaneKind.RIGHT).isVisible());
    UiDriver.click(v, Messages.layoutAll);
    assertTrue(v.pane(PaneKind.RIGHT).isVisible());
    assertTrue(v.connector(PaneKind.LEFT).isVisible());
    assertEquals(PaneLayout.ALL, MergePreferences.layout());
  }

  @Test
  void saveCommandWritesMarkersAndThePrimaryButtonStagesAndCloses() throws Exception {
    IFile file = threeConflicts();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.click(v, Messages.takeLeft);
    UiDriver.command(IWorkbenchCommandConstants.FILE_SAVE);
    String saved = repos.read("a.txt");
    assertTrue(saved.startsWith("a\nL1\nc\n<<<<<<< "), saved);
    assertFalse(UiDriver.editor(input).isDirty());
    for (int i = 0; i < 2; i++) {
      UiDriver.click(v, Messages.nextUnresolvedName);
      UiDriver.click(v, Messages.takeRight);
    }
    assertTrue(v.allResolvedBarShown());
    UiDriver.click(v.saveAndMarkResolvedButton());
    assertEquals("a\nL1\nc\nR3\ne\nR5\ng\n", repos.read("a.txt"));
    var rf = RepositoryLocator.locate(file).orElseThrow();
    assertFalse(ConflictState.isConflicted(rf.repository(), rf.path()));
    ConflictRepos.waitUntil(() -> UiDriver.editor(input) == null, 10, "the resolver to close");
  }

  @Test
  void dropDownAndMoreMenuActThroughRealEventsAndGreyItemsFollowTheCaret() throws Exception {
    MergeEditorInput input = open(threeConflicts());
    MergeViewer v = input.viewer();
    assertFalse(UiDriver.toolItem(v, Messages.prevUnresolvedName).isEnabled(), "no conflict above the caret");
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.clickDropDownItem(v, Messages.takeBoth, Messages.takeRightThenLeft);
    assertEquals(BlockState.TAKEN_BOTH, state(input, 0));
    assertEquals("a\nR1\nL1\nc\nd\ne\nf\ng\n", result(input));
    UiDriver.clickMore(v, Messages.revert);
    assertEquals(BlockState.UNRESOLVED, state(input, 0));
    UiDriver.click(v, Messages.nextUnresolvedName);
    UiDriver.click(v, Messages.nextUnresolvedName);
    assertTrue(UiDriver.toolItem(v, Messages.prevUnresolvedName).isEnabled());
    assertFalse(UiDriver.toolItem(v, Messages.nextUnresolvedName).isEnabled(), "at the last conflict");
  }
}
