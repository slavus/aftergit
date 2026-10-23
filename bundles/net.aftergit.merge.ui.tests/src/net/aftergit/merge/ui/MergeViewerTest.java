package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ManifestResolver;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MergeViewerTest {
  Shell shell;
  MergeViewer viewer;
  MergeModel model;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n",
        "a\nR1\nc\nR3\ne\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
    viewer.setInput(new MergeSession(model, new Labels("main (\"ours\", 1)", "feature (\"theirs\", 2)"), "\n", true));
    shell.open();
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  @Test
  void showsThreePanesAndStatus() {
    assertEquals("a\nL1\nc\nL3\ne\n", viewer.pane(PaneKind.LEFT).viewer().getDocument().get());
    assertEquals("a\nb\nc\nd\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
    assertEquals("2 conflicts", viewer.statusText());
    assertFalse(viewer.pane(PaneKind.LEFT).viewer().isEditable());
    assertTrue(viewer.pane(PaneKind.RESULT).viewer().isEditable());
  }

  @Test
  void plainSetupHasNoNoticeAndAPlainResult() {
    assertEquals("", viewer.noticeText());
    assertEquals(ResultKind.PLAIN, viewer.resultKind());
  }

  @Test
  void setupIsDisposedWithTheViewer() {
    boolean[] disposed = {false};
    Shell s = new Shell(Display.getCurrent());
    MergeViewer v = new MergeViewer(s, new PlainPaneSetup() {
      @Override
      public void dispose() {
        disposed[0] = true;
      }
    });
    v.setInput(new MergeSession(model, new Labels("o", "t"), "\n", true));
    s.dispose();
    assertTrue(disposed[0]);
  }

  @Test
  void allResolvedShowsGreenBar() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.selectBlock(1);
    viewer.takeRight();
    assertEquals("✓ All conflicts resolved", viewer.statusText());
    assertTrue(viewer.allResolvedBarShown());
    assertTrue(viewer.saveAndMarkResolvedButton().isVisible());
  }

  private static void type(org.eclipse.swt.custom.StyledText w, char c) {
    Event e = new Event();
    e.character = c;
    w.notifyListeners(SWT.KeyDown, e);
  }

  @Test
  void takeAfterUndoingTypingActsAtTheCaretNotOnTheNavigatedBlock() throws Exception {
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    org.eclipse.swt.custom.StyledText w = viewer.pane(PaneKind.RESULT).viewer().getTextWidget();
    w.setFocus();
    w.setCaretOffset(doc.getLineOffset(2)); // line 2 is the gap between block 0 and block 1
    type(w, 'x');
    assertEquals("a\nb\nxc\nd\ne\n", doc.get());
    w.setCaretOffset(0); // caret in front of the typed text: undoing it moves no caret
    viewer.selectBlock(0); // navigation selects the earlier block
    assertEquals(0, viewer.currentBlock());
    viewer.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(1, viewer.currentBlock(), "the next conflict after the caret, not the navigated block");
    viewer.takeRight();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
  }

  @Test
  void typingGroupsUntilTheCaretMovesAway() throws Exception {
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    org.eclipse.swt.custom.StyledText w = viewer.pane(PaneKind.RESULT).viewer().getTextWidget();
    w.setFocus();
    int o = doc.getLineOffset(1);
    w.setCaretOffset(o);
    type(w, 'x');
    type(w, 'y');
    type(w, 'z');
    assertEquals("a\nxyzb\nc\nd\ne\n", doc.get());
    // moving away and back to the end of the typing still starts a new step
    w.setCaretOffset(doc.getLineOffset(3));
    w.setCaretOffset(o + 3);
    type(w, 'w');
    assertEquals("a\nxyzwb\nc\nd\ne\n", doc.get());
    assertTrue(viewer.canUndo());
    viewer.undo();
    assertEquals("a\nxyzb\nc\nd\ne\n", doc.get());
    viewer.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertFalse(viewer.canUndo());
    assertTrue(viewer.canRedo());
  }

  private static void pump() {
    while (Display.getCurrent().readAndDispatch()) {
      // drain pending async runnables
    }
  }

  @Test
  void repaintsWhenMergeColorChanges() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.selectBlock(1);
    viewer.takeRight();
    pump(); // let the refresh scheduled by the document change run before the color changes
    org.eclipse.jface.resource.ColorRegistry reg =
        org.eclipse.ui.PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry();
    String id = ColorIds.lightId(LineStyle.Kind.ADDED);
    org.eclipse.swt.graphics.RGB original = ColorIds.DEFAULTS.get(id);
    try {
      reg.put(id, new org.eclipse.swt.graphics.RGB(1, 2, 3));
      pump();
      assertEquals(new org.eclipse.swt.graphics.RGB(1, 2, 3), viewer.statusBarBackground().getRGB());
    } finally {
      reg.put(id, original);
    }
  }

  @Test
  void repeatedSetInputDetachesOldModel() {
    MergeModel first = model;
    MergeModel fresh = HybridInitializer.initialize(new MergeInput("x\n", "y\n", "z\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(fresh, new Labels("o", "t"), "\n", true));
    int[] dirty = {0};
    viewer.addDirtyListener(() -> dirty[0]++);
    first.ignore(0); // the detached model must not reach the viewer any more
    assertEquals(0, dirty[0]);
    fresh.ignore(0);
    assertEquals(1, dirty[0]);
  }

  @Test
  void nextChangeWalksFocusedPane() {
    var rt = viewer.pane(PaneKind.RIGHT).viewer().getTextWidget();
    rt.setFocus();
    rt.setCaretOffset(0);
    viewer.nextChange();
    assertEquals(0, viewer.currentBlock());
    viewer.nextChange();
    assertEquals(1, viewer.currentBlock());
    viewer.previousChange();
    assertEquals(0, viewer.currentBlock());
  }

  @Test
  void navigateAndTake() {
    viewer.nextConflict();
    assertEquals(0, viewer.currentBlock());
    viewer.takeRight();
    assertEquals("a\nR1\nc\nd\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(0).state());
    assertEquals("1 conflict", viewer.statusText());
    viewer.nextConflict();
    assertEquals(1, viewer.currentBlock());
    viewer.takeLeftThenRight();
    assertEquals("a\nR1\nc\nL3\nR3\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
  }

  @Test
  void revertAndIgnore() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.revert();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    viewer.ignore();
    assertEquals(BlockState.IGNORED, model.blocks().get(0).state());
  }

  @Test
  void dirtyListenerFires() {
    int[] calls = {0};
    viewer.addDirtyListener(() -> calls[0]++);
    viewer.selectBlock(1);
    viewer.takeLeft();
    assertTrue(calls[0] > 0);
  }

  @Test
  void actionFollowsCaretAfterPreviousAction() throws Exception {
    viewer.selectBlock(0);
    viewer.takeLeft();
    var rv = viewer.pane(PaneKind.RESULT).viewer();
    int offset = rv.getDocument().getLineOffset(model.blocks().get(1).resultStart());
    rv.setSelectedRange(offset, 0);
    viewer.takeRight();
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
  }

  @Test
  void toolbarOrderAndKindsFollowTheDesign() {
    assertEquals(List.of("check:" + Messages.structureToggle, "|",
        "◀:" + Messages.prevAnyConflictName, "label:" + Messages.conflictGroup, "▶:" + Messages.nextAnyConflictName,
        "◀:" + Messages.prevUnresolvedName, "label:" + Messages.unresolvedGroup, "▶:" + Messages.nextUnresolvedName,
        "icon:" + Messages.prevChange, "icon:" + Messages.nextChange, "|",
        "text:" + Messages.takeLeft, "text:" + Messages.takeRight, "icon▾:" + Messages.takeBoth,
        "icon:" + Messages.ignore, "|",
        "text▾:" + Messages.autoResolve, "|",
        "radio:" + Messages.layoutAll, "radio:" + Messages.layoutLeftResult, "radio:" + Messages.layoutResultRight,
        "radio:" + Messages.layoutResultBelow,
        "<stretch>", "menu:" + Messages.more, "primary:Save & Mark Resolved"), viewer.toolbar().describe());
  }

  @Test
  void tooltipsNameTheActionAndItsKeys() throws Exception {
    assertEquals(Messages.nextUnresolvedName + " (" + KeySequence.getInstance("M1+M2+.").format() + ")",
        UiDriver.toolItem(viewer, Messages.nextUnresolvedName).getToolTipText());
    assertEquals(Messages.prevAnyConflictName + " (" + KeySequence.getInstance("M1+M3+,").format() + ")",
        UiDriver.toolItem(viewer, Messages.prevAnyConflictName).getToolTipText());
    assertEquals(Messages.prevChange + " (" + KeySequence.getInstance("M1+,").format() + ")",
        UiDriver.toolItem(viewer, Messages.prevChange).getToolTipText());
    assertEquals(Messages.takeLeft + " (" + KeySequence.getInstance("M1+M3+ARROW_LEFT").format() + ")",
        UiDriver.toolItem(viewer, Messages.takeLeft).getToolTipText());
    assertEquals(Messages.ignore + " (" + KeySequence.getInstance("M1+M3+I").format() + ")",
        UiDriver.toolItem(viewer, Messages.ignore).getToolTipText());
    assertEquals(Messages.takeBoth + " (" + KeySequence.getInstance("M1+M3+B").format() + ")",
        UiDriver.toolItem(viewer, Messages.takeBoth).getToolTipText());
    assertEquals(Messages.layoutResultBelow + " (" + KeySequence.getInstance("M1+M3+4").format() + ")",
        UiDriver.toolItem(viewer, Messages.layoutResultBelow).getToolTipText());
    assertEquals(Messages.autoResolve, UiDriver.toolItem(viewer, Messages.autoResolve).getToolTipText(),
        "no command: name only");
  }

  @Test
  void itemsAreGreyedWhereTheyCannotAct() {
    viewer.selectBlock(0);
    assertFalse(UiDriver.toolItem(viewer, Messages.prevAnyConflictName).isEnabled());
    assertTrue(UiDriver.toolItem(viewer, Messages.nextAnyConflictName).isEnabled());
    assertFalse(UiDriver.toolItem(viewer, Messages.prevUnresolvedName).isEnabled(), "at the first conflict");
    assertTrue(UiDriver.toolItem(viewer, Messages.nextUnresolvedName).isEnabled());
    assertTrue(UiDriver.toolItem(viewer, Messages.takeLeft).isEnabled());
    assertFalse(UiDriver.toolItem(viewer, Messages.prevChange).isEnabled(), "no change above the first");
    assertTrue(UiDriver.toolItem(viewer, Messages.nextChange).isEnabled());
    viewer.selectBlock(1);
    assertTrue(UiDriver.toolItem(viewer, Messages.prevUnresolvedName).isEnabled());
    assertFalse(UiDriver.toolItem(viewer, Messages.nextUnresolvedName).isEnabled(), "at the last conflict");
    assertTrue(UiDriver.toolItem(viewer, Messages.prevChange).isEnabled());
    assertFalse(UiDriver.toolItem(viewer, Messages.nextChange).isEnabled(), "no change below the last");
    reopen("a\nb\n", "a\nb\n", "a\nb\n"); // nothing to merge
    for (String label : List.of(Messages.prevUnresolvedName, Messages.nextUnresolvedName, Messages.prevChange,
        Messages.nextChange, Messages.takeLeft, Messages.takeRight, Messages.takeBoth, Messages.ignore)) {
      assertFalse(UiDriver.toolItem(viewer, label).isEnabled(), label);
    }
    assertTrue(UiDriver.toolItem(viewer, Messages.layoutAll).isEnabled());
    assertTrue(viewer.saveAndMarkResolvedButton().isEnabled());
  }

  @Test
  void theConflictButtonsWalkResolvedConflictsTooAndTheUnresolvedOnesSkipThem() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0);
    UiDriver.click(viewer, Messages.nextAnyConflictName);
    assertEquals(0, viewer.selectedBlock(), "Conflict ▶ stops at the taken conflict");
    UiDriver.click(viewer, Messages.nextAnyConflictName);
    assertEquals(1, viewer.selectedBlock());
    UiDriver.click(viewer, Messages.prevAnyConflictName);
    assertEquals(0, viewer.selectedBlock());
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0);
    UiDriver.click(viewer, Messages.nextUnresolvedName);
    assertEquals(1, viewer.selectedBlock(), "Unresolved ▶ skips it");
  }

  @Test
  void theStatusLineSaysWhereTheUserIs() {
    assertEquals("", viewer.positionText(), "no current block yet");
    viewer.selectBlock(0);
    assertEquals("Conflict 1 of 2 · unresolved", viewer.positionText());
    viewer.takeLeft();
    assertEquals("Conflict 1 of 2 · Take Left ◀", viewer.positionText());
    assertEquals("1 conflict", viewer.statusText(), "the counts stay in their own part");
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0);
    assertEquals("", viewer.positionText());
  }

  @Test
  void arrowButtonsAreReadByTheirFullName() {
    for (String name : List.of(Messages.prevAnyConflictName, Messages.nextAnyConflictName,
        Messages.prevUnresolvedName, Messages.nextUnresolvedName)) {
      ToolItem item = UiDriver.toolItem(viewer, name);
      assertTrue(item.getText().equals("◀") || item.getText().equals("▶"), "only the arrow on the button: "
          + item.getText());
      assertEquals(item.getToolTipText(), MergeToolbar.accessibleName(item));
      assertTrue(MergeToolbar.accessibleName(item).startsWith(name + " ("), MergeToolbar.accessibleName(item));
    }
  }

  @Test
  void eachNavigationPairSharesOneWordBetweenItsArrowsAndOverflowsAsAWhole() {
    boolean sawInRow = false;
    boolean sawInMore = false;
    for (int width = 250; width <= 1300; width += 25) {
      narrow(width);
      MergeToolbar bar = viewer.toolbar();
      List<Action> overflow = bar.overflow();
      List<String> words = new ArrayList<>();
      for (ToolItem item : bar.manager().getControl().getItems()) {
        if (item.getControl() instanceof CLabel l) {
          words.add(l.getText());
        }
      }
      List<String> more = new ArrayList<>();
      Menu m = bar.createMoreMenu(bar.control());
      try {
        for (MenuItem item : m.getItems()) {
          more.add(withoutKeys(item.getText()));
        }
      } finally {
        m.dispose();
      }
      for (List<String> pair : List.of(
          List.of(Messages.prevAnyConflictName, Messages.conflictGroup, Messages.nextAnyConflictName),
          List.of(Messages.prevUnresolvedName, Messages.unresolvedGroup, Messages.nextUnresolvedName))) {
        boolean prevOut = overflow.contains(bar.action(pair.get(0)));
        boolean nextOut = overflow.contains(bar.action(pair.get(2)));
        assertEquals(prevOut, nextOut, "the pair " + pair + " stays together at " + width);
        assertEquals(!prevOut, words.contains(pair.get(1)), "the word goes with its arrows at " + width);
        assertEquals(prevOut, more.contains(pair.get(0)) && more.contains(pair.get(2)),
            "both arrows by their full names in More at " + width + ": " + more);
        assertFalse(more.contains(pair.get(1)), "the word itself is no action");
        sawInRow |= !prevOut;
        sawInMore |= prevOut;
      }
    }
    assertTrue(sawInRow && sawInMore, "the widths cover both cases");
  }

  /** A menu item's label without the keys of its command ("label\tkeys"). */
  private static String withoutKeys(String text) {
    int tab = text.indexOf('\t');
    return tab < 0 ? text : text.substring(0, tab);
  }

  private static List<String> texts(Menu menu) {
    List<String> out = new ArrayList<>();
    for (MenuItem item : menu.getItems()) {
      out.add((item.getStyle() & SWT.SEPARATOR) != 0 ? "|"
          : (item.getStyle() & SWT.CASCADE) != 0 ? item.getText() + " ▸" : item.getText());
    }
    return out;
  }

  @Test
  void takeBothTakesLeftThenRightAndItsMenuOffersBothOrders() throws Exception {
    viewer.selectBlock(0);
    UiDriver.click(viewer, Messages.takeBoth);
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
    assertEquals(
        List.of(MergeToolbar.withKeys(Messages.takeLeftThenRight, SiteServices.TAKE_BOTH), Messages.takeRightThenLeft),
        texts(viewer.toolbar().dropDownMenu(Messages.takeBoth)));
  }

  @Test
  void theMoreMenuHoldsTheRarerActions() {
    ResolverContributions.setForTests(List.of(ResolverContributionsTest.PROBE));
    Menu m = viewer.toolbar().createMoreMenu(viewer.toolbar().control());
    try {
      assertEquals(List.of(Messages.compareWithBase + " ▸", "|", Messages.revert, "|", Messages.prevError,
          Messages.nextError, Messages.prevAutoResolved, Messages.nextAutoResolved, "|", Messages.settings + " ▸"),
          texts(m));
      assertEquals(List.of(Messages.compareOursBase, Messages.compareTheirsBase, Messages.compareOursTheirs),
          texts(m.getItem(0).getMenu()));
      assertEquals(List.of(Messages.settingIgnoreWhitespace, Messages.settingGreedy, Messages.settingInnerLine,
          Messages.settingAutoResolveOnOpen, "|", Messages.settingResolverImports, Messages.settingResolverProperties,
          Messages.settingResolverManifest, Messages.settingResolverNewerVersion, "Probe"), texts(m.getItem(9).getMenu()));
    } finally {
      m.dispose();
      ResolverContributions.setForTests(null);
    }
  }

  @Test
  void newerVersionWinsIsOffUntilSwitchedOn() {
    Menu m = viewer.toolbar().createMoreMenu(viewer.toolbar().control());
    try {
      MenuItem newer = java.util.Arrays.stream(m.getItem(9).getMenu().getItems())
          .filter(i -> Messages.settingResolverNewerVersion.equals(i.getText())).findFirst().orElseThrow();
      assertFalse(newer.getSelection(), "off by default");
      assertFalse(MergePreferences.resolverEnabled(NewerVersionResolver.ID));
      newer.setSelection(true);
      newer.notifyListeners(SWT.Selection, new Event());
      assertTrue(MergePreferences.resolverEnabled(NewerVersionResolver.ID));
    } finally {
      m.dispose();
      MergePreferences.reset();
    }
  }

  @Test
  void aResolverSwitchIsStoredInThePreferences() {
    Menu m = viewer.toolbar().createMoreMenu(viewer.toolbar().control());
    try {
      MenuItem manifest = java.util.Arrays.stream(m.getItem(9).getMenu().getItems())
          .filter(i -> Messages.settingResolverManifest.equals(i.getText())).findFirst().orElseThrow();
      assertTrue(manifest.getSelection(), "on by default");
      manifest.setSelection(false);
      manifest.notifyListeners(SWT.Selection, new Event());
      assertFalse(MergePreferences.resolverEnabled(ManifestResolver.ID));
    } finally {
      m.dispose();
      MergePreferences.reset();
    }
  }

  @Test
  void thePrimaryButtonIsARealButtonAtTheRightEdgeInTheSelectionColors() {
    shell.layout(true, true); // the shell lays out lazily; the geometry below needs the real row
    flush();
    Button b = viewer.saveAndMarkResolvedButton();
    assertSame(viewer.toolbar().control(), b.getParent(), "in the toolbar row");
    assertTrue(b.isVisible() && b.isEnabled());
    assertEquals(Boolean.TRUE, b.getData(MergeToolbar.CSS_DISABLED), "the theme cannot restyle it");
    assertEquals(Display.getCurrent().getSystemColor(SWT.COLOR_LIST_SELECTION), b.getBackground());
    var row = viewer.toolbar().control().getClientArea();
    var bounds = b.getBounds();
    assertTrue(row.width - (bounds.x + bounds.width) <= 2, "at the right edge: " + bounds + " in " + row);
    for (var c : viewer.statusBar().getChildren()) {
      assertFalse(c instanceof Button, "the status bar keeps only the status text and notices");
    }
  }

  @Test
  void at700PixelsEveryActionStaysReachableInTheRowOrInMore() throws Exception {
    assertEveryActionReachableAt(700);
  }

  @Test
  void at900PixelsEveryActionStaysReachableInTheRowOrInMore() throws Exception {
    assertEveryActionReachableAt(900);
  }

  @Test
  void overflowingActionsWorkFromMoreAndReturnToTheRowWhenItWidens() {
    narrow(700);
    viewer.selectBlock(0);
    UiDriver.clickMore(viewer, Messages.ignore);
    assertEquals(BlockState.IGNORED, model.blocks().get(0).state(), "Ignore from More");
    viewer.selectBlock(1);
    UiDriver.clickMore(viewer, Messages.takeRightThenLeft);
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(1).state(), "a Take Both order from its submenu");
    UiDriver.clickMore(viewer, Messages.layoutLeftResult);
    try {
      assertEquals(PaneLayout.LEFT_RESULT, viewer.paneLayout(), "a layout from More");
      narrow(1200); // the navigation pairs share one word each: the whole bar measured 1168 pixels, about 32 to spare
      assertEquals(List.of(), viewer.toolbar().overflow(), "everything back in the row");
      assertTrue(UiDriver.toolItem(viewer, Messages.layoutLeftResult).getSelection(), "the radio follows the layout");
    } finally {
      viewer.setPaneLayout(PaneLayout.ALL); // the layout is a preference: the next viewers open with it
    }
  }

  private void narrow(int width) {
    shell.setSize(width, 800);
    shell.layout(true, true);
    flush();
  }

  private void assertEveryActionReachableAt(int width) throws Exception {
    narrow(width);
    MergeToolbar bar = viewer.toolbar();
    ToolBar main = bar.manager().getControl();
    Button primary = viewer.saveAndMarkResolvedButton();
    var row = bar.control().getClientArea();
    var p = primary.getBounds();
    assertTrue(primary.isVisible() && p.x >= 0 && p.x + p.width <= row.width,
        "the primary button stays in the row: " + p + " in " + row);
    var mainBounds = main.getBounds();
    assertTrue(mainBounds.x + mainBounds.width <= p.x, "the bar ends before the primary button");
    assertFalse(bar.overflow().isEmpty(), "too narrow for the whole bar at " + width);
    List<String> more = new ArrayList<>();
    Menu m = bar.createMoreMenu(bar.control());
    try {
      for (MenuItem item : m.getItems()) {
        more.add(withoutKeys(item.getText()));
      }
    } finally {
      m.dispose();
    }
    int actions = 0;
    for (String entry : bar.describe()) {
      int colon = entry.indexOf(':');
      if (colon < 0 || entry.startsWith("menu:") || entry.startsWith("primary:") || entry.startsWith("label:")) {
        continue;
      }
      actions++;
      String label = entry.substring(colon + 1);
      String inMore = MergeToolbar.moreText(label); // the compact Structure toggle has its full name there
      ToolItem shown = null;
      for (ToolItem item : main.getItems()) {
        if (item.getData() instanceof org.eclipse.jface.action.ActionContributionItem a
            && label.equals(a.getAction().getText())) {
          shown = item;
        }
      }
      if (shown != null) {
        var b = shown.getBounds();
        assertTrue(b.x + b.width <= mainBounds.width, label + " is fully visible in the row: " + b + " of "
            + mainBounds);
        assertFalse(more.contains(inMore), label + " is not also in More");
      } else {
        assertTrue(more.contains(inMore), label + " overflowed into More: " + more);
      }
    }
    assertEquals(16, actions, "every action checked"); // the Structure toggle included
    assertTrue(Screens.painted(Screens.capture(bar.control(), "toolbar-" + width + "-light")));
  }

  @Test
  void thePrimaryButtonNeverBecomesTheDefaultButtonSoEnterInTheResultStaysANewline() {
    Button b = viewer.saveAndMarkResolvedButton();
    assertNotSame(b, shell.getDefaultButton(), "after opening");
    b.setFocus();
    flush();
    viewer.pane(PaneKind.RESULT).text().setFocus();
    flush();
    assertNotSame(b, shell.getDefaultButton(), "after the button had the focus and the result took it back");
  }

  @Test
  void iconOnlyButtonsAreNamedForAssistiveTechnology() {
    var prevChange = UiDriver.toolItem(viewer, Messages.prevChange);
    assertEquals("", prevChange.getText(), "icon only");
    assertEquals(prevChange.getToolTipText(), MergeToolbar.accessibleName(prevChange));
    assertEquals(KeyHints.tooltip(Messages.layoutAll, SiteServices.LAYOUT_ALL),
        MergeToolbar.accessibleName(UiDriver.toolItem(viewer, Messages.layoutAll)), "the name and its keys");
    var take = UiDriver.toolItem(viewer, Messages.takeLeft);
    assertEquals(take.getText(), MergeToolbar.accessibleName(take), "a labelled button keeps its label");
  }

  @Test
  void revertAllAutoResolvedAndUndo() {
    reopen("x a z b y\n", "x A z b y\n", "x a z B y\n");
    viewer.rerunAutoResolve();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    viewer.revertAllAutoResolved();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("x a z b y\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
    viewer.undo();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
  }

  @Test
  void runGreedyOnceTakesTheLongerInsertion() {
    reopen("x x\n", "x Y x\n", "x Y Z x\n");
    viewer.runGreedyOnce();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertEquals("x Y Z x\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
  }

  @Test
  void markAllReviewedClearsReviewCount() {
    reopen("x a z b y\n", "x A z b y\n", "x a z B y\n");
    viewer.rerunAutoResolve();
    assertEquals(1, model.needsReviewCount());
    viewer.markAllReviewed();
    assertEquals(0, model.needsReviewCount());
  }

  private MergeModel reopen(String base, String ours, String theirs) {
    shell.dispose();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false)).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    return model;
  }

  @Test
  void innerLineHighlightsChangedWordOnLightFill() {
    reopen("a\nx = 0;\nc\n", "a\nx = 1;\nc\n", "a\nx = 2;\nc\n");
    viewer.refresh();
    var text = viewer.pane(PaneKind.LEFT).viewer().getTextWidget();
    int offset = text.getOffsetAtLine(1) + 4; // "1"
    assertEquals(Palette.strong(LineStyle.Kind.CONFLICT), text.getStyleRangeAtOffset(offset).background);
    assertEquals(LineStyle.Fill.LIGHT, viewer.pane(PaneKind.LEFT).style().lines()[1].fill());
    viewer.setInnerLineEnabled(false);
    assertEquals(LineStyle.Fill.STRONG, viewer.pane(PaneKind.LEFT).style().lines()[1].fill());
    var range = text.getStyleRangeAtOffset(offset);
    assertTrue(range == null || range.background == null);
  }

  private static final String B2 = "a\nx = 0;\nc\ny = 0;\ne\n";
  private static final String L2 = "a\nx = 1;\nc\ny = 1;\ne\n";
  private static final String R2 = "a\nx = 2;\nc\ny = 2;\ne\n";

  private org.eclipse.swt.graphics.Color bgAt(PaneKind pane, int line, int column) {
    var text = viewer.pane(pane).viewer().getTextWidget();
    var r = text.getStyleRangeAtOffset(text.getOffsetAtLine(line) + column);
    return r == null ? null : r.background;
  }

  @Test
  void innerLineCoversResultAndRightPanesAndLaterConflicts() {
    reopen(B2, L2, R2);
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    for (PaneKind p : PaneKind.values()) {
      assertEquals(strong, bgAt(p, 1, 4), p + " first conflict");
      assertEquals(strong, bgAt(p, 3, 4), p + " second conflict");
      assertNull(bgAt(p, 1, 0), p + " unchanged word");
      assertNull(bgAt(p, 1, 2), p + " unchanged operator");
      assertNull(bgAt(p, 2, 0), p + " outside conflicts");
    }
  }

  @Test
  void whitespaceOnlyConflictKeepsStrongFill() {
    reopen("a\nc\n", "a\n\nc\n", "a\n \nc\n");
    assertEquals(LineStyle.Kind.CONFLICT, viewer.pane(PaneKind.LEFT).style().lines()[1].kind());
    assertEquals(LineStyle.Fill.STRONG, viewer.pane(PaneKind.LEFT).style().lines()[1].fill());
  }

  @Test
  void innerLineKeepsUnrelatedStyles() {
    reopen(B2, L2, R2);
    var display = Display.getCurrent();
    var red = display.getSystemColor(SWT.COLOR_RED);
    for (PaneKind p : PaneKind.values()) {
      var text = viewer.pane(p).viewer().getTextWidget();
      text.setStyleRange(new org.eclipse.swt.custom.StyleRange(text.getOffsetAtLine(2), 1, red, null));
      text.setStyleRange(new org.eclipse.swt.custom.StyleRange(text.getOffsetAtLine(1), 1, red, null));
    }
    viewer.refresh();
    viewer.setInnerLineEnabled(false);
    viewer.setInnerLineEnabled(true);
    for (PaneKind p : PaneKind.values()) {
      var text = viewer.pane(p).viewer().getTextWidget();
      assertEquals(red, text.getStyleRangeAtOffset(text.getOffsetAtLine(2)).foreground, p + " outside");
      assertEquals(red, text.getStyleRangeAtOffset(text.getOffsetAtLine(1)).foreground, p + " in conflict");
      assertEquals(Palette.strong(LineStyle.Kind.CONFLICT), bgAt(p, 1, 4));
    }
  }

  @Test
  void editingResultLeavesNoGhostHighlights() throws Exception {
    reopen("a\nx = foo;\nc\ny = 0;\ne\n", "a\nx = bar;\nc\ny = 1;\ne\n", "a\nx = baz;\nc\ny = 2;\ne\n");
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    var rv = viewer.pane(PaneKind.RESULT).viewer();
    assertEquals(strong, bgAt(PaneKind.RESULT, 1, 5));
    rv.getDocument().replace(rv.getDocument().getLineOffset(1) + 5, 0, "Z"); // inside the highlighted word
    while (Display.getCurrent().readAndDispatch()) {
      // let the scheduled refresh run
    }
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    var text = rv.getTextWidget();
    for (var r : text.getStyleRanges()) {
      if (r.start < text.getOffsetAtLine(3)) {
        assertNotEquals(strong, r.background, "ghost highlight at " + r.start + "+" + r.length);
      }
    }
    assertEquals(strong, bgAt(PaneKind.RESULT, 3, 4), "second conflict still highlighted");
  }

  @Test
  void highlightsAreRepaintedAfterSetInput() {
    reopen(B2, L2, R2);
    var again = HybridInitializer.initialize(new MergeInput(B2, L2, R2, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(again, new Labels("ours", "theirs"), "\n", true));
    for (PaneKind p : PaneKind.values()) {
      assertEquals(Palette.strong(LineStyle.Kind.CONFLICT), bgAt(p, 1, 4), p.name());
    }
  }

  @Test
  void actingThroughCaretFallbackRevealsTheActedBlock() throws Exception {
    var rv = viewer.pane(PaneKind.RESULT).viewer();
    rv.getTextWidget().setFocus();
    // line 2 ("c") lies between the two conflicts: the action falls back to the next conflict below
    rv.setSelectedRange(rv.getDocument().getLineOffset(2), 0);
    viewer.takeRight();
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
    assertEquals(1, viewer.currentBlock());
    var acted = model.blocks().get(1);
    int caret = viewer.pane(PaneKind.RESULT).caretLine();
    assertTrue(acted.resultStart() <= caret && caret < acted.resultEnd(),
        "caret line " + caret + " inside acted block " + acted.resultStart() + ".." + acted.resultEnd());
  }

  @Test
  void paneDocumentsSplitLinesOnlyOnNewline() {
    reopen("", "x\n", "y\n");
    for (PaneKind k : PaneKind.values()) {
      assertArrayEquals(new String[] {"\n"}, viewer.pane(k).viewer().getDocument().getLegalLineDelimiters(), k.name());
    }
  }

  @Test
  void enterInEmptyResultInsertsNewline() {
    reopen("", "x\n", "y\n");
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    assertEquals("", doc.get());
    var widget = viewer.pane(PaneKind.RESULT).viewer().getTextWidget();
    widget.setFocus();
    Event e = new Event();
    e.character = SWT.CR;
    e.keyCode = SWT.CR;
    widget.notifyListeners(SWT.KeyDown, e);
    assertEquals("\n", doc.get());
  }

  @Test
  void loneCarriageReturnStaysInsideItsLine() throws Exception {
    reopen("a\rb\nc\nd\n", "a\rb\nL\nd\n", "a\rb\nR\nd\n");
    for (PaneKind k : PaneKind.values()) {
      IDocument doc = viewer.pane(k).viewer().getDocument();
      int modelLines = switch (k) {
        case LEFT -> model.left().size();
        case RIGHT -> model.right().size();
        case RESULT -> model.document().lineCount();
      };
      assertEquals(modelLines + 1, doc.getNumberOfLines(), k.name());
    }
    IDocument left = viewer.pane(PaneKind.LEFT).viewer().getDocument();
    int conflictLine = left.getLineOfOffset(left.get().indexOf("L\n"));
    assertEquals(BlockStyles.start(model.blocks().get(0), PaneKind.LEFT), conflictLine);
    var style = viewer.pane(PaneKind.LEFT).style();
    assertEquals(LineStyle.Kind.CONFLICT, style.lines()[conflictLine].kind());
    assertEquals(LineStyle.Kind.NONE, style.lines()[0].kind());
  }

  @Test
  void setInputIgnoresCompareInputAndNullButRejectsOtherTypes() {
    Object before = viewer.getInput();
    viewer.setInput(new DiffNode(null, 0));
    viewer.setInput(null);
    assertSame(before, viewer.getInput());
    assertThrows(IllegalArgumentException.class, () -> viewer.setInput("unexpected"));
  }

  @Test
  void panesHaveNoOwnVerticalScrollbarAndScrollTogether() {
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      base.append(i).append('\n');
      ours.append(i == 150 ? "L\nL2\nL3\nL4\n" : i + "\n");
      theirs.append(i == 150 ? "R\n" : i + "\n");
    }
    reopen(base.toString(), ours.toString(), theirs.toString());
    assertNull(viewer.pane(PaneKind.LEFT).text().getVerticalBar());
    viewer.selectBlock(0);
    flush();
    int leftTop = viewer.pane(PaneKind.LEFT).text().getTopIndex();
    int rightTop = viewer.pane(PaneKind.RIGHT).text().getTopIndex();
    assertTrue(leftTop > 100 && rightTop > 100, "both panes followed the result: " + leftTop + "/" + rightTop);
  }

  private void reopenTall() {
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      base.append(i).append('\n');
      ours.append(i == 150 ? "L\nL2\nL3\nL4\n" : i + "\n");
      theirs.append(i == 150 ? "R\n" : i + "\n");
    }
    reopen(base.toString(), ours.toString(), theirs.toString());
    flush();
  }

  @Test
  void scrollingTheVirtualSpaceMovesAllPanes() {
    reopenTall();
    viewer.scrollSync().scrollToVirtual(200);
    flush();
    for (PaneKind k : PaneKind.values()) {
      int top = viewer.pane(k).text().getTopIndex();
      assertTrue(top > 180, k + " top " + top);
    }
    int l = viewer.pane(PaneKind.LEFT).text().getTopIndex();
    int r = viewer.pane(PaneKind.RIGHT).text().getTopIndex();
    assertEquals(3, l - r, "below the block the left pane is 3 lines ahead");
  }

  @Test
  void mouseWheelOverAPaneScrollsAllPanes() {
    // Display.post cannot synthesize wheel events on GTK: deliver the event SWT sends for one notch down
    reopenTall();
    for (int i = 0; i < 5; i++) {
      Event wheel = new Event();
      wheel.detail = SWT.SCROLL_LINE;
      wheel.count = -3;
      wheel.doit = true;
      viewer.pane(PaneKind.RIGHT).text().notifyListeners(SWT.MouseVerticalWheel, wheel);
      assertFalse(wheel.doit, "the pane must not scroll on its own");
    }
    flush();
    for (PaneKind k : PaneKind.values()) {
      assertTrue(viewer.pane(k).text().getTopIndex() > 0, k + " scrolled by the wheel");
    }
  }

  @Test
  void editingTheResultKeepsItsScrollPosition() throws Exception {
    reopenTall();
    viewer.scrollSync().scrollToVirtual(50);
    flush();
    var text = viewer.pane(PaneKind.RESULT).text();
    String topLine = text.getLine(text.getTopIndex());
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLineOffset(5), doc.getLineOffset(8) - doc.getLineOffset(5), ""); // shrinks the gap above
    flush();
    assertEquals(topLine, text.getLine(text.getTopIndex()), "the text at the top of the result did not move");
  }

  /** Conflict at {@code at}: left 1 line, result 1 line, right 31 lines. */
  private void reopenUneven(int at) {
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      base.append(i).append('\n');
      ours.append(i == at ? "L" : i).append('\n');
      if (i == at) {
        for (int j = 0; j < 31; j++) {
          theirs.append("R").append(j).append('\n');
        }
      } else {
        theirs.append(i).append('\n');
      }
    }
    reopen(base.toString(), ours.toString(), theirs.toString());
    flush();
  }

  private double viewportLines() {
    var t = viewer.pane(PaneKind.RESULT).text();
    return t.getClientArea().height / (double) t.getLineHeight();
  }

  /** What a click and a resize in {@code kind} make the text viewer report to its viewport listeners. */
  private void clickAndResize(PaneKind kind) {
    var t = viewer.pane(kind).text();
    var area = t.getClientArea();
    for (int type : new int[] {SWT.MouseDown, SWT.MouseUp}) {
      Event e = new Event();
      e.button = 1;
      e.count = 1;
      e.x = area.width / 2;
      e.y = area.height / 2;
      t.notifyListeners(type, e);
    }
    t.notifyListeners(SWT.Resize, new Event());
    flush();
  }

  @Test
  void clickInsideAnUnevenBlockDoesNotMoveTheOtherPanes() {
    reopenUneven(150);
    viewer.scrollSync().scrollToVirtual(150 + 15 - viewportLines() / 2); // viewport middle inside the block
    flush();
    int result = viewer.pane(PaneKind.RESULT).text().getTopPixel();
    int right = viewer.pane(PaneKind.RIGHT).text().getTopPixel();
    clickAndResize(PaneKind.LEFT);
    assertEquals(result, viewer.pane(PaneKind.RESULT).text().getTopPixel(), "result");
    assertEquals(right, viewer.pane(PaneKind.RIGHT).text().getTopPixel(), "right");
  }

  private static boolean showsLastLines(org.eclipse.swt.custom.StyledText t) {
    int visible = t.getClientArea().height / t.getLineHeight();
    return t.getTopIndex() + visible >= t.getLineCount() - 2;
  }

  @Test
  void growingTheWindowAtTheEndKeepsTheLongerPaneAtItsEnd() {
    reopenUneven(297);
    viewer.scrollSync().scrollToVirtual(1_000_000);
    flush();
    var right = viewer.pane(PaneKind.RIGHT).text();
    assertTrue(showsLastLines(right), "right shows its last lines before the resize");
    shell.setSize(1200, 1000);
    flush();
    assertTrue(showsLastLines(right), "right shows its last lines after the window grew: top " + right.getTopIndex()
        + " of " + right.getLineCount());
  }

  @Test
  void pastingIntoTheShortResultBlockKeepsTheResultStill() throws Exception {
    reopenUneven(150);
    viewer.scrollSync().scrollToVirtual(150 + 15 - viewportLines() / 2); // viewport middle halfway into the block
    flush();
    var text = viewer.pane(PaneKind.RESULT).text();
    int top = text.getTopPixel();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLineOffset(150), 0, "P\n".repeat(20));
    flush();
    assertEquals(top, text.getTopPixel(), "result top after pasting 20 lines into the block");
  }

  @Test
  void editElsewhereDoesNotReanchorOnAnEmptyResultBlock() throws Exception {
    // after line 150 left inserts 30 lines and right 1 line: the conflict has no lines in the result
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      base.append(i).append('\n');
      ours.append(i).append('\n');
      theirs.append(i).append('\n');
      if (i == 150) {
        for (int j = 0; j < 30; j++) {
          ours.append("L").append(j).append('\n');
        }
        theirs.append("R\n");
      }
    }
    reopen(base.toString(), ours.toString(), theirs.toString());
    flush();
    viewer.scrollSync().scrollToVirtual(151 + 15 - viewportLines() / 2); // viewport middle inside the block
    flush();
    var left = viewer.pane(PaneKind.LEFT).text();
    int leftTop = left.getTopPixel();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLineOffset(280), 0, "new\n"); // far below the viewport
    flush();
    assertEquals(leftTop, left.getTopPixel(), "left top after an edit elsewhere in the result");
  }

  @Test
  void clickInAClampedPaneAtTheEndDoesNotPullTheLongerPaneBack() {
    reopenUneven(297); // the block ends near the end, so only the right pane reaches the bottom
    viewer.scrollSync().scrollToVirtual(1_000_000);
    flush();
    var right = viewer.pane(PaneKind.RIGHT).text();
    int rightTop = right.getTopPixel();
    assertTrue(right.getTopIndex() + (int) viewportLines() >= right.getLineCount() - 2, "right shows its last lines");
    clickAndResize(PaneKind.LEFT);
    assertEquals(rightTop, right.getTopPixel(), "after a click in the left pane");
    clickAndResize(PaneKind.RESULT);
    viewer.refresh();
    flush();
    assertEquals(rightTop, right.getTopPixel(), "after a click in the result and a refresh");
  }

  @Test
  void caretDrivenHorizontalScrollMovesTheOtherPanes() {
    String longLine = "x".repeat(2000);
    reopen("a\n" + longLine + "\nc\n", "a\n" + longLine + "\nc\n", "a\n" + longLine + "\nc\n");
    flush();
    var rt = viewer.pane(PaneKind.RESULT).text();
    rt.setFocus();
    rt.setSelection(rt.getOffsetAtLine(1) + 2000);
    rt.showSelection();
    flush();
    assertTrue(rt.getHorizontalPixel() > 0, "result scrolled horizontally");
    var lt = viewer.pane(PaneKind.LEFT).text();
    Display d = Display.getCurrent();
    long until = System.currentTimeMillis() + 2000;
    while (lt.getHorizontalPixel() == 0 && System.currentTimeMillis() < until) {
      if (!d.readAndDispatch()) {
        d.timerExec(50, () -> {}); // wake up even when nothing else happens
        d.sleep();
      }
    }
    assertTrue(viewer.pane(PaneKind.LEFT).text().getHorizontalPixel() > 0, "left followed");
  }

  @Test
  void sliderIsDisabledWithoutInput() {
    Shell s = new Shell(Display.getCurrent());
    try {
      MergeViewer v = new MergeViewer(s);
      var slider = (org.eclipse.swt.widgets.Slider) MergeLayout.slots((org.eclipse.swt.widgets.Composite)
          v.pane(PaneKind.RESULT).getParent()).get(MergeLayout.Slot.SLIDER);
      assertFalse(slider.getEnabled());
    } finally {
      s.dispose();
    }
  }

  @Test
  void clickOnStripeRevealsBlock() {
    ChangeStripe stripe = viewer.pane(PaneKind.RIGHT).stripe();
    flush();
    var markers = stripe.markers();
    var target = markers.get(markers.size() - 1);
    org.eclipse.swt.widgets.Event e = new org.eclipse.swt.widgets.Event();
    e.x = 3;
    e.y = target.y() + 1;
    e.button = 1;
    stripe.notifyListeners(SWT.MouseDown, e);
    assertEquals(target.block(), viewer.currentBlock());
  }

  /** 300 lines: word conflicts at 195 and 200, an auto-resolved block needing review at 205. */
  private void reopenLongWithConflictsNear200() {
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      switch (i) {
        case 195 -> { base.append("x = 0;\n"); ours.append("x = 1;\n"); theirs.append("x = 2;\n"); }
        case 200 -> { base.append("y = 0;\n"); ours.append("y = 1;\n"); theirs.append("y = 2;\n"); }
        case 205 -> { base.append("p a q b r\n"); ours.append("p A q b r\n"); theirs.append("p a q B r\n"); }
        default -> { base.append(i).append('\n'); ours.append(i).append('\n'); theirs.append(i).append('\n'); }
      }
    }
    reopen(base.toString(), ours.toString(), theirs.toString());
    flush();
    viewer.scrollSync().scrollToVirtual(185);
    flush();
  }

  private int blockAtResultLine(int line) {
    for (var b : model.blocks()) {
      if (b.resultStart() == line) {
        return b.index();
      }
    }
    throw new AssertionError("no block at result line " + line);
  }

  @Test
  void undoOfTakeKeepsScrollCaretAndHighlights() {
    reopenLongWithConflictsNear200();
    var text = viewer.pane(PaneKind.RESULT).text();
    int block = blockAtResultLine(200);
    viewer.selectBlock(block);
    viewer.takeLeft();
    flush();
    int top = text.getTopIndex();
    assertTrue(top > 150, "scrolled down: " + top);
    viewer.undo();
    flush();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(block).state());
    assertTrue(Math.abs(text.getTopIndex() - top) <= 1, "result top " + text.getTopIndex() + " was " + top);
    assertEquals(block, viewer.currentBlock(), "the undone block is current");
    int caret = viewer.pane(PaneKind.RESULT).caretLine();
    assertEquals(200, caret, "caret on the undone block");
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    assertEquals(strong, bgAt(PaneKind.RESULT, 195, 4), "word highlight of the other conflict");
    assertEquals(strong, bgAt(PaneKind.RESULT, 200, 4), "word highlight of the undone conflict");
    viewer.takeRight();
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(block).state());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(blockAtResultLine(195)).state());
  }

  @Test
  void undoOfMarkReviewedKeepsScrollAndHighlights() {
    reopenLongWithConflictsNear200();
    int auto = blockAtResultLine(205);
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(auto).state());
    assertTrue(model.blocks().get(auto).needsReview());
    var text = viewer.pane(PaneKind.RESULT).text();
    viewer.selectBlock(auto);
    viewer.markReviewed();
    flush();
    int top = text.getTopIndex();
    viewer.undo();
    flush();
    assertTrue(model.blocks().get(auto).needsReview());
    assertTrue(Math.abs(text.getTopIndex() - top) <= 1, "result top " + text.getTopIndex() + " was " + top);
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    assertEquals(strong, bgAt(PaneKind.RESULT, 195, 4), "word highlight after undo");
    assertEquals(strong, bgAt(PaneKind.LEFT, 200, 4), "left word highlight after undo");
  }

  @Test
  void undoOfIgnoreKeepsScrollAndCaret() {
    reopenLongWithConflictsNear200();
    var text = viewer.pane(PaneKind.RESULT).text();
    int block = blockAtResultLine(200);
    viewer.selectBlock(block);
    viewer.ignore();
    flush();
    int top = text.getTopIndex();
    int leftTop = viewer.pane(PaneKind.LEFT).text().getTopIndex();
    viewer.undo();
    flush();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(block).state());
    assertTrue(Math.abs(text.getTopIndex() - top) <= 1, "result top " + text.getTopIndex() + " was " + top);
    assertTrue(Math.abs(viewer.pane(PaneKind.LEFT).text().getTopIndex() - leftTop) <= 1, "left pane stayed");
    assertEquals(200, viewer.pane(PaneKind.RESULT).caretLine());
    assertEquals(block, viewer.currentBlock());
  }

  @Test
  void wordHighlightsAreComputedOncePerBlockAndInput() {
    reopen(B2, L2, R2);
    flush();
    int n = viewer.innerLineComputations();
    assertTrue(n > 0);
    viewer.refresh();
    viewer.refresh();
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.undo();
    flush();
    assertEquals(n, viewer.innerLineComputations(), "side and base texts never change: no recomputation");
    assertEquals(Palette.strong(LineStyle.Kind.CONFLICT), bgAt(PaneKind.LEFT, 1, 4));
    viewer.setInput(new MergeSession(HybridInitializer.initialize(new MergeInput(B2, L2, R2, null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model(), new Labels("o", "t"), "\n", true));
    assertTrue(viewer.innerLineComputations() > n, "a new input starts a new cache");
  }

  @Test
  void anActionRefreshesOnlyOnce() {
    flush();
    int before = viewer.refreshCount();
    viewer.selectBlock(0);
    viewer.takeLeft();
    flush();
    assertEquals(before + 1, viewer.refreshCount(), "the queued refresh after the synchronous one is skipped");
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    try {
      doc.replace(0, 0, "z");
    } catch (org.eclipse.jface.text.BadLocationException e) {
      throw new AssertionError(e);
    }
    flush();
    assertEquals(before + 2, viewer.refreshCount(), "typing still refreshes");
  }

  private void reopenInput(MergeInput input) {
    shell.dispose();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(input, ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    flush();
  }

  @Test
  void overrideOfAnotherLengthGetsNoSideWordHighlights() {
    // the user edited inside git's markers before opening: the side text is the hunk's, longer than the stage block
    reopenInput(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n",
        "a\n<<<<<<< ours\nL edited\nmore\n=======\nR\n>>>>>>> theirs\nc\n"));
    var b = model.blocks().get(0);
    assertNotNull(b.leftOverride());
    assertEquals(1, BlockStyles.end(b, PaneKind.LEFT) - BlockStyles.start(b, PaneKind.LEFT), "the stage's one line");
    assertFalse(InnerLine.compute(model, 0).left().isEmpty(), "the override has word ranges");
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    for (var r : viewer.pane(PaneKind.LEFT).text().getStyleRanges()) {
      assertNotEquals(strong, r.background, "misplaced left highlight at " + r.start);
    }
  }

  @Test
  void aSideFromTheMarkersGetsItsWordHighlightsInPlace() {
    // no base and no ours stage: the left pane shows ours as git's markers do, the block on its own line
    reopenInput(new MergeInput(null, null, "a\nfoo baz\nc\n",
        "a\n<<<<<<< ours\nfoo bar\n=======\nfoo baz\n>>>>>>> theirs\nc\n"));
    var b = model.blocks().get(0);
    assertNull(b.leftOverride());
    assertEquals(1, BlockStyles.start(b, PaneKind.LEFT));
    assertEquals(2, BlockStyles.end(b, PaneKind.LEFT));
    var left = viewer.pane(PaneKind.LEFT).text();
    assertEquals("a\nfoo bar\nc\n", left.getText());
    var strong = Palette.strong(LineStyle.Kind.CONFLICT);
    boolean found = false;
    for (var r : left.getStyleRanges()) {
      if (strong.equals(r.background)) {
        assertTrue(r.start >= left.getOffsetAtLine(1) && r.start + r.length <= left.getOffsetAtLine(2),
            "highlight inside the block at " + r.start);
        found = true;
      }
    }
    assertTrue(found, "the block's words are highlighted in the left pane");
  }

  @Test
  void setInputClearsTheSelectedBlock() {
    viewer.selectBlock(1);
    MergeModel fresh = HybridInitializer.initialize(new MergeInput("x\n", "y\n", "z\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(fresh, new Labels("o", "t"), "\n", true));
    assertTrue(viewer.getSelection().isEmpty());
    assertEquals(0, viewer.currentBlock());
  }

  @Test
  void navigationAndActionsWithoutInputDoNothing() {
    Shell s = new Shell(Display.getCurrent());
    try {
      MergeViewer v = new MergeViewer(s);
      assertDoesNotThrow(() -> {
        v.nextConflict();
        v.previousConflict();
        v.nextChange();
        v.previousChange();
        v.nextChangeIn(PaneKind.LEFT, true);
        v.nextAutoResolved();
        v.previousAutoResolved();
        v.takeLeft();
        v.takeRight();
        v.ignore();
        v.revert();
        v.markReviewed();
        v.markAllReviewed();
        v.rerunAutoResolve();
        v.runGreedyOnce();
        v.revertAllAutoResolved();
        v.takeBlock(0, net.aftergit.merge.core.merge.ThreeSide.LEFT, false);
        v.ignoreBlock(0);
        v.undo();
        v.redo();
        v.statusText();
        Event wheel = new Event();
        wheel.count = -3;
        v.connector(PaneKind.LEFT).notifyListeners(SWT.MouseVerticalWheel, wheel);
      });
      assertEquals(-1, v.currentBlock());
    } finally {
      s.dispose();
    }
  }

  @Test
  void bordersArePaintedForVisibleLinesOnly() {
    reopenTall();
    viewer.scrollSync().scrollToVirtual(200);
    flush();
    var t = viewer.pane(PaneKind.RESULT).text();
    int[] range = viewer.pane(PaneKind.RESULT).painter().borderLines();
    int visible = t.getClientArea().height / t.getLineHeight();
    assertTrue(range[0] >= t.getTopIndex() - 1 && range[1] <= t.getTopIndex() + visible + 2,
        "lines " + range[0] + ".." + range[1] + " for top " + t.getTopIndex());
  }

  private void flush() {
    Display d = Display.getCurrent();
    while (d.readAndDispatch()) {
      // run pending async refreshes and paints
    }
  }

  private static MergeViewer viewerWith(Shell s, PaneSetup setup, MergeModel m) {
    MergeViewer v = new MergeViewer(s, setup);
    v.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
    return v;
  }

  /** Without JDT: the result has an error on line 0; ours has whatever the test says, theirs nothing. */
  private static class Checked extends PlainPaneSetup {
    List<LineProblem> left = List.of();

    @Override
    public List<ResultProblem> problems() {
      return List.of(new ResultProblem(0, 1, true, "boom"));
    }

    @Override
    public List<LineProblem> sideErrors(ThreeSide side) {
      return side == ThreeSide.LEFT ? left : List.of();
    }
  }

  @Test
  void aBlockThatDoesNotCompileHoldsTheGreenBar() {
    Shell s = new Shell(Display.getCurrent());
    try {
      MergeModel m = HybridInitializer.initialize(new MergeInput("x = 0; y = 0;\n", "x = 1; y = 0;\n",
          "x = 0; y = 2;\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      Checked c = new Checked();
      MergeViewer v = viewerWith(s, c, m);
      assertTrue(v.notCompiling(0));
      assertFalse(v.allResolvedBarShown());
      assertTrue(v.statusText().contains(MessageFormat.format(Messages.statusNotCompiling, 1)), v.statusText());
      assertEquals(List.of(new PaneStyle.Frame(0, 1)), v.pane(PaneKind.RESULT).style().errorFrames());
      c.left = List.of(new LineProblem(0, "boom")); // ours has the same error there: inherited
      v.refresh();
      assertFalse(v.notCompiling(0));
      assertTrue(v.allResolvedBarShown());
      assertTrue(v.pane(PaneKind.RESULT).style().errorFrames().isEmpty());
    } finally {
      s.dispose();
    }
  }

  /** Like the Java imports: after every body change it adds a line at the top, through the model. */
  private static final class Adjusting extends PlainPaneSetup {
    final List<String> befores = new ArrayList<>();
    Throwable failure;

    @Override
    public void afterBodyChange(MergeModel m, String before) {
      befores.add(before);
      if (failure instanceof RuntimeException e) {
        throw e;
      }
      if (failure instanceof Error e) {
        throw e;
      }
      m.applyDerivedText("// adjusted\n" + m.document().chunk(0, m.document().lineCount()));
    }
  }

  private static MergeModel freshModel() {
    return HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n",
        null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
  }

  @Test
  void anAdjustmentAfterTakeIsPartOfTheSameUndoStep() {
    Shell s = new Shell(Display.getCurrent());
    try {
      Adjusting a = new Adjusting();
      MergeModel m = freshModel();
      MergeViewer v = viewerWith(s, a, m);
      IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
      String before = doc.get();
      v.selectBlock(0);
      v.takeLeft();
      assertEquals(List.of(before), a.befores);
      String after = "// adjusted\na\nL1\nc\nd\ne\n";
      assertEquals(after, doc.get());
      assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
      assertEquals("L1\n", m.resultText(0), "the block moved with the added line");
      v.undo();
      assertEquals(before, doc.get());
      assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
      assertFalse(v.canUndo(), "Take and its adjustment were one step");
      v.redo();
      assertEquals(after, doc.get(), "one redo brings both back");
      assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
      assertEquals("L1\n", m.resultText(0));
      assertFalse(v.canRedo());
    } finally {
      s.dispose();
    }
  }

  @Test
  void ignoreAndReviewMarksAreNoBodyChanges() {
    Shell s = new Shell(Display.getCurrent());
    try {
      Adjusting a = new Adjusting();
      MergeViewer v = viewerWith(s, a, freshModel());
      v.selectBlock(0);
      v.ignore();
      v.markAllReviewed();
      assertTrue(a.befores.isEmpty());
      v.rerunAutoResolve();
      assertEquals(1, a.befores.size(), "auto-resolution is a body change");
      v.revertAllAutoResolved();
      v.runGreedyOnce();
      assertEquals(3, a.befores.size(), "reverting and greedy runs are body changes");
    } finally {
      s.dispose();
    }
  }

  @Test
  void aFailingAdjustmentKeepsTheAction() {
    Shell s = new Shell(Display.getCurrent());
    try {
      Adjusting a = new Adjusting();
      a.failure = new IllegalStateException("a broken integration");
      MergeModel m = freshModel();
      MergeViewer v = viewerWith(s, a, m);
      IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
      v.selectBlock(0);
      v.takeLeft();
      assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
      assertEquals("a\nL1\nc\nd\ne\n", doc.get());
      v.undo();
      assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
      a.failure = new LinkageError("a half-installed integration");
      v.selectBlock(1);
      v.takeRight();
      assertEquals(BlockState.TAKEN_RIGHT, m.blocks().get(1).state());
      assertTrue(v.canUndo(), "the Take is still an undo step");
      v.undo();
      assertEquals(BlockState.UNRESOLVED, m.blocks().get(1).state());
    } finally {
      s.dispose();
    }
  }

  @Test
  void aVirtualMachineErrorOfTheAdjustmentStillLeavesAnUndoStep() {
    Shell s = new Shell(Display.getCurrent());
    try {
      Adjusting a = new Adjusting();
      a.failure = new InternalError("a virtual machine failure");
      MergeModel m = freshModel();
      MergeViewer v = viewerWith(s, a, m);
      IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
      String before = doc.get();
      v.selectBlock(0);
      assertThrows(InternalError.class, v::takeLeft, "not swallowed");
      assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
      assertTrue(v.canUndo(), "the Take is an undo step");
      v.undo();
      assertEquals(before, doc.get());
      assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    } finally {
      s.dispose();
    }
  }

  @Test
  void aMarkUpdateReadsTheProblemsOnce() {
    Shell s = new Shell(Display.getCurrent());
    try {
      MergeModel m = HybridInitializer.initialize(new MergeInput("x = 0; y = 0;\n", "x = 1; y = 0;\n",
          "x = 0; y = 2;\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
      int[] reads = {0};
      Checked c = new Checked() {
        @Override
        public List<ResultProblem> problems() {
          reads[0]++;
          return super.problems();
        }
      };
      MergeViewer v = viewerWith(s, c, m);
      reads[0] = 0;
      v.refresh();
      assertEquals(1, reads[0], "marks and status share one snapshot");
      assertTrue(v.notCompiling(0));
    } finally {
      s.dispose();
    }
  }

  @Test
  void changingTheErrorColorRepaintsTheFrames() {
    Shell s = new Shell(Display.getCurrent());
    org.eclipse.jface.preference.IPreferenceStore store = org.eclipse.ui.editors.text.EditorsUI.getPreferenceStore();
    String key = Palette.ERROR_COLOR_KEY;
    String old = store.getString(key);
    try {
      MergeModel m = HybridInitializer.initialize(new MergeInput("x = 0; y = 0;\n", "x = 1; y = 0;\n",
          "x = 0; y = 2;\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
      MergeViewer v = viewerWith(s, new Checked(), m);
      flush();
      int before = v.refreshCount();
      store.setValue(key, "1,2,3".equals(old) ? "4,5,6" : "1,2,3");
      flush();
      assertTrue(v.refreshCount() > before, "the frames follow the error annotation color");
    } finally {
      store.setValue(key, old);
      s.dispose();
    }
  }

  @Test
  void aSessionNoticeStandsBesideTheSetupNotice() {
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true, "f.txt", null, null,
        "from the markers", net.aftergit.merge.git.MarkerConfig.DEFAULT));
    assertEquals("from the markers", viewer.noticeText(), "plain panes have no notice of their own");
    assertEquals("a \u00b7 b", MergeViewer.joinNotices("a", "b"));
    assertEquals("b", MergeViewer.joinNotices(null, "b"));
    assertNull(MergeViewer.joinNotices("", null));
  }

  @Test
  void sidePanesHighlightTheChangedWordsOfResolvedBlocksInTheirKindsStrongShade() {
    reopen("a\nx = 0;\nc\n", "a\nx = 1;\nc\n", "a\nx = 0;\nc\n");
    assertEquals(BlockState.RESOLVED_BY_GIT, model.blocks().get(0).state());
    assertEquals(Palette.strong(LineStyle.Kind.MODIFIED), bgAt(PaneKind.LEFT, 1, 4));
    assertNull(bgAt(PaneKind.LEFT, 1, 0), "unchanged word");
    assertEquals(LineStyle.Fill.LIGHT, viewer.pane(PaneKind.LEFT).style().lines()[1].fill());
    assertNull(bgAt(PaneKind.RESULT, 1, 4), "the result gets the fill, no word highlights");
    viewer.setInnerLineEnabled(false);
    assertNull(bgAt(PaneKind.LEFT, 1, 4), "inner-line differences off: no words anywhere");
  }

  @Test
  void autoResolvedBlocksShowTheirSidesWordsAndTheAutoGutter() {
    reopen("x a z b y\n", "x A z b y\n", "x a z B y\n");
    viewer.rerunAutoResolve();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    // each side against the merged result "x A z B y" (visual checklist row 44), in the auto words' shade
    assertEquals(Palette.word(LineStyle.Kind.AUTO), bgAt(PaneKind.LEFT, 0, 6), "ours lacks theirs' B");
    assertEquals(Palette.word(LineStyle.Kind.AUTO), bgAt(PaneKind.RIGHT, 0, 2), "theirs lacks ours' A");
    assertNull(bgAt(PaneKind.LEFT, 0, 2), "ours' own A is in the result");
    assertNull(bgAt(PaneKind.LEFT, 0, 0));
    assertEquals(PaneStyle.Bar.AUTO, viewer.pane(PaneKind.RESULT).style().gutters().get(0).bar());
    assertEquals(LineStyle.Kind.AUTO, viewer.pane(PaneKind.RESULT).style().lines()[0].kind());
  }

  @Test
  void theSelectedBlockIsOutlinedNotFilled() {
    LineStyle before = viewer.pane(PaneKind.LEFT).style().lines()[3];
    viewer.selectBlock(1);
    var b = model.blocks().get(1);
    for (PaneKind k : PaneKind.values()) {
      assertEquals(new PaneStyle.Frame(BlockStyles.start(b, k), BlockStyles.end(b, k)),
          viewer.pane(k).painter().selection(), k.name());
    }
    assertEquals(before, viewer.pane(PaneKind.LEFT).style().lines()[3], "the fill stays as it was");
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0); // block-states §2: outside every block, no current block
    for (PaneKind k : PaneKind.values()) {
      assertNull(viewer.pane(k).painter().selection(), k.name());
    }
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setCaretOffset(result.getOffsetAtLine(3)); // into block 1 with the keyboard or a click
    assertEquals(new PaneStyle.Frame(BlockStyles.start(b, PaneKind.LEFT), BlockStyles.end(b, PaneKind.LEFT)),
        viewer.pane(PaneKind.LEFT).painter().selection(), "a caret move into a block makes it current");
  }

  @Test
  void aCaretMoveIntoAResolvedBlockMakesItCurrentInEveryPane() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    result.setCaretOffset(0);
    assertTrue(viewer.getSelection().isEmpty());
    result.setCaretOffset(result.getOffsetAtLine(1));
    assertEquals(0, viewer.selectedBlock(), "a taken block is current like an open one");
    var b = model.blocks().get(0);
    for (PaneKind k : PaneKind.values()) {
      assertEquals(new PaneStyle.Frame(BlockStyles.start(b, k), BlockStyles.end(b, k)),
          viewer.pane(k).painter().selection(), k.name());
    }
  }

  @Test
  void aCaretMoveIntoABlockOnScreenRepaintsBothConnectorsAndTheStripes() {
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    result.setCaretOffset(result.getOffsetAtLine(model.blocks().get(0).resultStart()));
    flush();
    assertEquals(0, viewer.selectedBlock());
    int before = viewer.decorationRedraws();
    // no scroll and no refresh: only the change of the current block can ask for the repaint
    result.setCaretOffset(result.getOffsetAtLine(model.blocks().get(1).resultStart()));
    flush();
    assertEquals(1, viewer.selectedBlock());
    assertTrue(viewer.decorationRedraws() > before,
        "the connectors' edges and the stripes' outline are repainted for the new current block");
    before = viewer.decorationRedraws();
    result.setCaretOffset(result.getOffsetAtLine(model.blocks().get(1).resultStart()) + 1);
    flush();
    assertEquals(before, viewer.decorationRedraws(), "a caret move inside the same block repaints nothing");
  }

  @Test
  void theCountsKeepTheirPlaceAndStandSixteenPixelsRightOfThePosition() {
    var children = viewer.statusBar().getChildren();
    Control position = children[0];
    Control counts = children[1];
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0);
    flush();
    assertEquals("", viewer.positionText());
    assertFalse(position.getVisible(), "no position: no label");
    int alone = counts.getBounds().x;
    viewer.selectBlock(0);
    flush();
    assertTrue(position.getVisible());
    assertEquals(alone, position.getBounds().x, "the position takes the counts' place at the left");
    var p = position.getBounds();
    assertEquals(16, counts.getBounds().x - (p.x + p.width), "16 px between the position and the counts");
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0);
    flush();
    assertFalse(position.getVisible());
    assertEquals(alone, counts.getBounds().x, "the counts go back to the left edge");
  }

  @Test
  void theCaretInASidePaneSelectsTheBlockOfThatSide() {
    var left = viewer.pane(PaneKind.LEFT).text();
    left.setFocus();
    left.setCaretOffset(left.getOffsetAtLine(3));
    assertEquals(1, viewer.selectedBlock());
    left.setCaretOffset(left.getOffsetAtLine(2));
    assertEquals(-1, viewer.selectedBlock(), "between the blocks");
  }

  @Test
  void focusingAPaneMakesTheBlockUnderItsCaretCurrent() {
    var right = viewer.pane(PaneKind.RIGHT).text();
    right.setCaretOffset(right.getOffsetAtLine(1)); // not focused: no change yet
    viewer.pane(PaneKind.RESULT).text().setFocus();
    right.setFocus();
    UiDriver.flush();
    assertEquals(0, viewer.selectedBlock());
  }

  @Test
  void afterOpeningTheFirstStepReachesAConflictOnTheFirstLine() {
    reopen("b\nx\n", "L\nx\n", "R\nx\n");
    UiDriver.flush();
    assertEquals(-1, viewer.selectedBlock(), "opening makes no block current");
    viewer.nextConflict();
    assertEquals(0, viewer.selectedBlock(), "the conflict on line 0 is the first target");
  }

  private void reopen(MergeModel m) {
    shell.dispose();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = m;
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
  }

  @Test
  void aNavigatedEmptyBlockStaysCurrentAcrossAFocusBounceAndTakeActsOnIt() {
    reopen(BlockLocatorTest.emptyNextToABlock());
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    UiDriver.flush();
    viewer.selectBlock(0);
    assertEquals(0, viewer.selectedBlock());
    var elsewhere = new org.eclipse.swt.widgets.Text(shell, SWT.NONE); // another part of the workbench
    shell.layout();
    elsewhere.setFocus();
    UiDriver.flush();
    result.setFocus();
    UiDriver.flush();
    assertEquals(0, viewer.selectedBlock(), "the focus bounce keeps the navigated empty block");
    viewer.takeRight();
    var empty = model.blocks().get(0);
    assertEquals(BlockState.TAKEN_BOTH, empty.state(), "Take acts on the empty block (left taken, now right too)");
    assertTrue(empty.resultEnd() > empty.resultStart(), "the right side is in the result");
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(1).state(), "not on its neighbour");
  }

  @Test
  void aClickIntoTheLinesOfTheNeighbourOfAnEmptyBlockSelectsTheNeighbour() {
    reopen(BlockLocatorTest.emptyNextToABlock());
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    UiDriver.flush();
    viewer.selectBlock(0);
    result.setCaretOffset(result.getOffsetAtLine(2));
    assertEquals(1, viewer.selectedBlock());
    result.setCaretOffset(result.getOffsetAtLine(1));
    assertEquals(1, viewer.selectedBlock(), "the neighbour still covers its first line");
  }

  @Test
  void fromOutsideTheCaretOnTheMarkerLineOfAnEmptyBlockSelectsTheBlockWithThatLine() {
    reopen(BlockLocatorTest.emptyNextToABlock());
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    UiDriver.flush();
    result.setCaretOffset(0);
    assertEquals(-1, viewer.selectedBlock());
    result.setCaretOffset(result.getOffsetAtLine(1));
    assertEquals(1, viewer.selectedBlock(), "the block with lines wins over the empty block on the same line");
  }

  @Test
  void nextConflictGoesPastTheBlockTheCaretIsIn() {
    var result = viewer.pane(PaneKind.RESULT).text();
    result.setFocus();
    result.setCaretOffset(result.getOffsetAtLine(1));
    assertEquals(0, viewer.selectedBlock());
    viewer.nextConflict();
    assertEquals(1, viewer.selectedBlock());
  }

  @Test
  void wordHighlightsFollowAColorChangeAndLeaveNoStaleColor() {
    reopen(B2, L2, R2);
    var registry = Palette.registry();
    String id = ColorIds.strongId(LineStyle.Kind.CONFLICT);
    org.eclipse.swt.graphics.RGB before = registry.getRGB(id);
    try {
      registry.put(id, new org.eclipse.swt.graphics.RGB(10, 20, 30));
      viewer.refresh();
      var now = Palette.strong(LineStyle.Kind.CONFLICT);
      for (PaneKind p : PaneKind.values()) {
        assertEquals(now, bgAt(p, 1, 4), p.name());
        for (var r : viewer.pane(p).text().getStyleRanges()) {
          assertFalse(r.background != null && r.background.getRGB().equals(before),
              p + ": stale word highlight at " + r.start);
        }
      }
    } finally {
      registry.put(id, before);
      viewer.refresh();
    }
  }

  @Test
  void sideWordsAreComputedOncePerBlockAndInput() {
    reopen("a\nx = 0;\nc\n", "a\nx = 1;\nc\n", "a\nx = 0;\nc\n");
    int n = viewer.sideWordComputations();
    assertTrue(n > 0);
    for (int i = 0; i < 5; i++) {
      viewer.refresh();
    }
    assertEquals(n, viewer.sideWordComputations(), "side and base texts never change: no recomputation");
  }

  private org.eclipse.swt.custom.StyleRange styleAt(PaneKind pane, int line, int column) {
    var text = viewer.pane(pane).viewer().getTextWidget();
    return text.getStyleRangeAtOffset(text.getOffsetAtLine(line) + column);
  }

  @Test
  void aSideNotTakenIsGreyAndStruckThroughAndTheResultRulerShowsTheOrigin() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    var right = styleAt(PaneKind.RIGHT, 1, 0);
    assertNotNull(right, "the side not taken has a style");
    assertTrue(right.strikeout);
    assertEquals(Palette.strong(LineStyle.Kind.DROPPED), right.strikeoutColor);
    // R1 differs from the result L1 as a whole: the word's text is the darker grey of a dropped side's words
    assertEquals(Palette.wordText(LineStyle.Kind.DROPPED), right.foreground);
    var left = styleAt(PaneKind.LEFT, 1, 0);
    assertTrue(left == null || !left.strikeout, "the taken side is not struck");
    assertEquals(List.of(new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT,
        PaneStyle.Badge.NONE)), viewer.pane(PaneKind.RESULT).originColumn().gutters());
    assertNull(viewer.pane(PaneKind.LEFT).originColumn(), "only the result has the column");
  }

  @Test
  void aDroppedSideStaysStruckWithInnerLineOff() {
    viewer.setInnerLineEnabled(false);
    try {
      viewer.selectBlock(0);
      viewer.takeRight();
      var left = styleAt(PaneKind.LEFT, 1, 0);
      assertNotNull(left);
      assertTrue(left.strikeout, "the origin is not a word difference: it stays without inner-line");
    } finally {
      viewer.setInnerLineEnabled(true);
    }
  }

  @Test
  void droppedTextFollowsAColorChangeAndLeavesNoStaleStrike() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    pump();
    var registry = Palette.registry();
    org.eclipse.swt.graphics.RGB before = registry.getRGB(ColorIds.DROPPED_TEXT);
    try {
      registry.put(ColorIds.DROPPED_TEXT, new org.eclipse.swt.graphics.RGB(10, 20, 30));
      pump();
      assertEquals(new org.eclipse.swt.graphics.RGB(10, 20, 30), styleAt(PaneKind.RIGHT, 1, 0).strikeoutColor.getRGB());
      assertEquals(Palette.wordText(LineStyle.Kind.DROPPED), styleAt(PaneKind.RIGHT, 1, 0).foreground,
          "the differing word's text follows the new colour too");
      viewer.selectBlock(0);
      viewer.revert();
      pump();
      var text = viewer.pane(PaneKind.RIGHT).text();
      for (var r : text.getStyleRanges()) {
        assertFalse(r.strikeout, "no stale strike at " + r.start);
      }
    } finally {
      registry.put(ColorIds.DROPPED_TEXT, before);
    }
  }
}
