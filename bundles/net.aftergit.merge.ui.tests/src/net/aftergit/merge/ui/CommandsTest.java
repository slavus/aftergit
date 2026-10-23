package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import net.aftergit.merge.core.model.BlockState;
import org.eclipse.core.commands.Command;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preferences design §5: every action is a command of the category "AfterGit", keys work everywhere. */
class CommandsTest {
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

  @Test
  void everyActionIsACommandOfTheAfterGitCategoryWithADescription() throws Exception {
    ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
    assertEquals("AfterGit", cs.getCategory("net.aftergit.merge.category").getName());
    for (String id : SiteServices.ALL) {
      Command c = cs.getCommand(id);
      assertTrue(c.isDefined(), id);
      assertEquals("net.aftergit.merge.category", c.getCategory().getId(), id);
      assertNotNull(c.getDescription(), id);
      assertFalse(c.getDescription().isBlank(), id);
    }
    assertTrue(SiteServices.ALL.contains(SiteServices.TOGGLE_STRUCTURE));
    assertTrue(SiteServices.ALL.containsAll(List.of(SiteServices.TAKE_BOTH, SiteServices.IGNORE,
        SiteServices.REVERT, SiteServices.MARK_REVIEWED, SiteServices.SHOW_PROPOSAL, SiteServices.AI_RESOLVE_CONFLICT,
        SiteServices.AI_RESOLVE_ALL, SiteServices.SAVE_AND_MARK_RESOLVED, SiteServices.COMPARE_OURS_BASE,
        SiteServices.COMPARE_THEIRS_BASE, SiteServices.COMPARE_OURS_THEIRS, SiteServices.LAYOUT_ALL,
        SiteServices.LAYOUT_LEFT_RESULT, SiteServices.LAYOUT_RESULT_RIGHT, SiteServices.LAYOUT_RESULT_BELOW)));
    for (PaneLayout pl : PaneLayout.values()) {
      assertTrue(SiteServices.ALL.contains(SiteServices.layoutCommand(pl)), pl.toString());
    }
  }

  @Test
  void theCommandsActOnTheActiveResolver() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n");
    IFile file = repos.plainProject("commands", "a.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    MergeViewer v = input.viewer();
    UiDriver.activate(v.getControl());
    v.nextConflict();
    int b = v.currentBlock();
    UiDriver.command(SiteServices.TAKE_BOTH);
    assertEquals(BlockState.TAKEN_BOTH, input.session().model().blocks().get(b).state());
    UiDriver.command(SiteServices.REVERT);
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(b).state());
    UiDriver.command(SiteServices.IGNORE);
    assertEquals(BlockState.IGNORED, input.session().model().blocks().get(b).state());
    UiDriver.command(SiteServices.LAYOUT_RESULT_BELOW);
    assertEquals(PaneLayout.RESULT_BELOW, v.paneLayout());
    UiDriver.command(SiteServices.LAYOUT_LEFT_RESULT);
    assertEquals(PaneLayout.LEFT_RESULT, v.paneLayout());
    UiDriver.command(SiteServices.LAYOUT_ALL);
    assertEquals(PaneLayout.ALL, v.paneLayout());
  }

  @Test
  void theToolbarCarriesTheCommandsAndShowsTheirKeys() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("commands-toolbar", "a.txt");
    MergeViewer v = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false)).viewer();
    // wide enough for Take Both on the bar, with the resolver's context active (where JFace would add the keys again)
    v.getControl().getShell().setBounds(0, 0, 1600, 1200);
    UiDriver.flush();
    MergeToolbar t = v.toolbar();
    assertFalse(t.overflow().contains(t.action(Messages.takeBoth)), "Take Both shows on the bar");
    assertEquals(SiteServices.TAKE_BOTH, t.commandOf(Messages.takeBoth));
    assertEquals(SiteServices.IGNORE, t.commandOf(Messages.ignore));
    assertEquals(SiteServices.TAKE_LEFT, t.commandOf(Messages.takeLeft));
    assertEquals(SiteServices.NEXT_ANY_CONFLICT, t.commandOf(Messages.nextAnyConflictName));
    assertEquals(SiteServices.LAYOUT_RESULT_BELOW, t.commandOf(MergeViewer.layoutLabel(PaneLayout.RESULT_BELOW)));
    assertEquals(SiteServices.TAKE_BOTH, t.action(Messages.takeBoth).getActionDefinitionId());
    String keys = KeyHints.binding(SiteServices.TAKE_BOTH);
    assertNotNull(keys);
    t.refreshKeyHints();
    assertEquals(KeyHints.tooltip(Messages.takeBoth, SiteServices.TAKE_BOTH),
        t.action(Messages.takeBoth).getToolTipText());
    String tip = UiDriver.toolItem(v, Messages.takeBoth).getToolTipText();
    assertEquals(KeyHints.tooltip(Messages.takeBoth, SiteServices.TAKE_BOTH), tip, "the keys once");
    assertTrue(tip.endsWith(keys + ")"), tip);
    String layoutTip = t.action(MergeViewer.layoutLabel(PaneLayout.RESULT_BELOW)).getToolTipText();
    assertTrue(layoutTip.endsWith(KeyHints.binding(SiteServices.LAYOUT_RESULT_BELOW) + ")"), layoutTip);
    Menu menu = t.dropDownMenu(Messages.takeBoth);
    try {
      MenuItem first = menu.getItems()[0];
      assertEquals(Messages.takeLeftThenRight + "\t" + keys, first.getText(), "the menu shows the command's keys");
    } finally {
      menu.dispose();
    }
  }

  @Test
  void aBindingChangedInTheKeysShowsInTheTooltipsOnTheNextHover() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("commands-rebind", "a.txt");
    MergeViewer v = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false)).viewer();
    v.getControl().getShell().setBounds(0, 0, 1600, 1200);
    UiDriver.flush();
    var bindings = PlatformUI.getWorkbench().getService(org.eclipse.ui.keys.IBindingService.class);
    org.eclipse.jface.bindings.Binding[] before = bindings.getBindings();
    var scheme = bindings.getActiveScheme();
    var command = PlatformUI.getWorkbench().getService(ICommandService.class).getCommand(SiteServices.TAKE_BOTH);
    var sequence = org.eclipse.jface.bindings.keys.KeySequence.getInstance("M1+M2+M3+F9");
    var added = new org.eclipse.jface.bindings.keys.KeyBinding(sequence,
        new org.eclipse.core.commands.ParameterizedCommand(command, null), scheme.getId(),
        "org.eclipse.ui.contexts.window", null, null, null, org.eclipse.jface.bindings.Binding.USER);
    var after = java.util.Arrays.copyOf(before, before.length + 1);
    after[before.length] = added;
    try {
      bindings.savePreferences(scheme, after);
      org.eclipse.swt.widgets.ToolItem item = UiDriver.toolItem(v, Messages.takeBoth);
      item.getParent().notifyListeners(org.eclipse.swt.SWT.MouseEnter, new org.eclipse.swt.widgets.Event());
      String keys = sequence.format();
      String tip = item.getToolTipText();
      assertTrue(tip.endsWith("(" + keys + ")"), tip);
      assertEquals(tip.indexOf(keys), tip.lastIndexOf(keys), "the keys once: " + tip);
    } finally {
      bindings.savePreferences(scheme, before);
    }
    UiDriver.toolItem(v, Messages.takeBoth).getParent().notifyListeners(org.eclipse.swt.SWT.MouseEnter,
        new org.eclipse.swt.widgets.Event());
    assertEquals(KeyHints.tooltip(Messages.takeBoth, SiteServices.TAKE_BOTH),
        UiDriver.toolItem(v, Messages.takeBoth).getToolTipText(), "back to the default keys");
  }
}
