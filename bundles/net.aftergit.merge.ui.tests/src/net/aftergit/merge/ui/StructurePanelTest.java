package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.git.Labels;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Structure-compare design §2: the column left of the panes, hidden by default, its toggle, « and remembered state. */
class StructurePanelTest {
  Shell shell;
  MergeViewer viewer;
  MergeModel model;

  @BeforeEach
  void open() {
    MergePreferences.reset();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setLayout(new FillLayout());
    shell.setSize(1200, 800);
    viewer = new MergeViewer(shell);
    model = session(viewer, "messages.properties");
    shell.open();
    shell.layout(true, true);
    UiDriver.flush();
  }

  @AfterEach
  void close() {
    shell.dispose();
    MergePreferences.reset();
  }

  static MergeModel session(MergeViewer v, String name) {
    MergeModel m = HybridInitializer.initialize(new MergeInput(StructureControllerTest.BASE,
        StructureControllerTest.OURS, StructureControllerTest.THEIRS, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    v.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true, name));
    return m;
  }

  private Action toggle() {
    return viewer.toolbar().action(Messages.structureToggle);
  }

  private int columnWidth() {
    return viewer.structurePanel().control().getBounds().width;
  }

  @Test
  void theColumnIsHiddenByDefault() {
    assertFalse(viewer.structureShown());
    assertFalse(viewer.structurePanel().control().isVisible());
    assertTrue(toggle().isEnabled());
    assertFalse(toggle().isChecked());
    assertEquals(0, viewer.pane(PaneKind.LEFT).getBounds().x, "the panes start at the left edge");
  }

  @Test
  void theToggleAndTheChevronShowAndHideTheColumn() {
    UiDriver.click(UiDriver.toolItem(viewer, Messages.structureToggle));
    UiDriver.flush();
    assertTrue(viewer.structureShown());
    assertTrue(viewer.structurePanel().control().isVisible());
    assertTrue(toggle().isChecked());
    assertEquals(MergeLayout.STRUCTURE_DEFAULT, columnWidth());
    assertEquals(columnWidth() + MergeLayout.SASH, viewer.pane(PaneKind.LEFT).getBounds().x);
    UiDriver.click(viewer.structurePanel().hideItem());
    UiDriver.flush();
    assertFalse(viewer.structureShown());
    assertFalse(toggle().isChecked());
    assertEquals(0, viewer.pane(PaneKind.LEFT).getBounds().x);
    viewer.toggleStructure(); // the command's handler
    UiDriver.flush();
    assertTrue(viewer.structureShown());
  }

  @Test
  void shownStateAndWidthAreRememberedForTheNextResolver() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    Event e = new Event();
    e.x = 340;
    e.detail = SWT.NONE; // released, not dragging
    viewer.structureSash().notifyListeners(SWT.Selection, e);
    UiDriver.flush();
    assertEquals(340, columnWidth());
    assertTrue(MergePreferences.structureShown());
    assertEquals(340, MergePreferences.structureWidth());
    Shell other = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      other.setLayout(new FillLayout());
      other.setSize(1200, 800);
      MergeViewer v2 = new MergeViewer(other);
      session(v2, "other.properties");
      other.open();
      other.layout(true, true);
      UiDriver.flush();
      assertTrue(v2.structureShown(), "per workbench, not per file");
      assertEquals(340, v2.structurePanel().control().getBounds().width);
    } finally {
      other.dispose();
    }
  }

  @Test
  void theColumnNeverTakesMoreThanHalfTheEditor() {
    viewer.setStructureShown(true);
    Event e = new Event();
    e.x = 900;
    viewer.structureSash().notifyListeners(SWT.Selection, e);
    shell.setSize(700, 500);
    shell.layout(true, true);
    UiDriver.flush();
    int area = viewer.structurePanel().control().getParent().getClientArea().width;
    assertTrue(columnWidth() <= area / 2, columnWidth() + " of " + area);
    assertTrue(columnWidth() >= Math.min(MergeLayout.STRUCTURE_MIN, area / 2));
    assertTrue(MergePreferences.structureWidth() <= 600, "remembered as shown, at most half of 1200: "
        + MergePreferences.structureWidth());
  }

  @Test
  void theColumnStaysLeftOfThePanesInEveryLayout() {
    viewer.setStructureShown(true);
    for (PaneLayout pl : PaneLayout.values()) {
      viewer.setPaneLayout(pl);
      UiDriver.flush();
      int edge = columnWidth() + MergeLayout.SASH;
      for (PaneKind k : PaneKind.values()) {
        if (viewer.pane(k).isVisible()) {
          assertTrue(viewer.pane(k).getBounds().x >= edge, pl + " " + k);
        }
      }
    }
  }

  @Test
  void hidingTheColumnWithTheFocusInItGivesTheFocusToTheResult() {
    UiDriver.activate(shell);
    viewer.setStructureShown(true);
    UiDriver.flush();
    viewer.structurePanel().tree().tree().setFocus();
    UiDriver.flush();
    viewer.setStructureShown(false);
    UiDriver.flush();
    assertTrue(viewer.pane(PaneKind.RESULT).text().isFocusControl());
  }

  @Test
  void aFileWithoutStructureDisablesTheToggleAndKeepsTheRememberedState() {
    MergePreferences.setStructureShown(true);
    session(viewer, "notes.txt");
    UiDriver.flush();
    assertFalse(toggle().isEnabled());
    assertFalse(viewer.structureShown());
    viewer.toggleStructure();
    assertFalse(viewer.structureShown(), "the key does nothing");
    assertTrue(MergePreferences.structureShown(), "left for the next file");
  }

  @Test
  void aClickInThePanelJumpsAndTheCaretSelectsTheNode() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    Tree t = viewer.structurePanel().tree().tree();
    StructureTreeTest.click(t, t.getItem(1)); // c
    assertEquals(1, viewer.selectedBlock());
    StyledText r = viewer.pane(PaneKind.RESULT).text();
    ConflictRepos.focusIn(r);
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    try {
      r.setCaretOffset(doc.getLineOffset(model.blocks().get(0).resultStart()));
    } catch (org.eclipse.jface.text.BadLocationException e) {
      throw new AssertionError(e);
    }
    UiDriver.flush();
    assertEquals("a", ((StructureNode) t.getSelection()[0].getData()).label());
  }

  @Test
  void aTakeAndItsUndoUpdateTheChip() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    StructureTree tree = viewer.structurePanel().tree();
    viewer.selectBlock(0);
    viewer.takeLeft();
    ConflictRepos.waitUntil(() -> tree.rows().get(0).equals("a | both | ◀"), 5, "the taken chip");
    viewer.undo();
    ConflictRepos.waitUntil(() -> tree.rows().get(0).equals("a | both | conflict"), 5, "the conflict chip again");
  }

  @Test
  void showAllInTheHeaderFiltersTheTree() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    Button all = viewer.structurePanel().showAllButton();
    all.setSelection(true);
    all.notifyListeners(SWT.Selection, new Event());
    UiDriver.flush();
    assertEquals(List.of("a | both | conflict", "b |  | ", "c | ours | git"), viewer.structurePanel().tree().rows());
  }

  @Test
  void theToggleIsTheLastToOverflowAndWorksFromMore() {
    MergeToolbar bar = viewer.toolbar();
    for (int width = 700; width > 100 && !bar.overflow().contains(toggle()); width -= 50) {
      shell.setSize(width, 800);
      shell.layout(true, true);
      UiDriver.flush();
    }
    assertEquals(toggle(), bar.overflow().get(0), "first in More, the last to leave the row");
    assertEquals(16, bar.overflow().size(), "every other action left the row before it");
    UiDriver.clickMore(viewer, Messages.structureTitle);
    assertTrue(viewer.structureShown());
    assertTrue(toggle().isChecked());
    UiDriver.clickMore(viewer, Messages.structureTitle);
    assertFalse(viewer.structureShown());
  }

  @Test
  void showingTheColumnScrollsToTheNodeOfTheCurrentBlock() {
    MergePreferences.setStructureShowAll(true);
    StringBuilder base = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      base.append("k").append(i).append("=1\n");
    }
    String b = base.toString();
    MergeModel m = HybridInitializer.initialize(new MergeInput(b, b.replace("k199=1", "k199=2"),
        b.replace("k199=1", "k199=3"), null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true, "long.properties"));
    viewer.selectBlock(0);
    UiDriver.flush();
    viewer.setStructureShown(true);
    UiDriver.flush();
    Tree t = viewer.structurePanel().tree().tree();
    assertEquals("k199", ((StructureNode) t.getSelection()[0].getData()).label());
    var item = t.getSelection()[0].getBounds();
    var area = t.getClientArea();
    assertTrue(item.y >= 0 && item.y + item.height <= area.height, item + " in view of " + area);
  }

  @Test
  void theToggleIsCompactAndNamedByItsTooltip() {
    ToolItem item = UiDriver.toolItem(viewer, Messages.structureToggle);
    assertEquals("\u2630", item.getText(), "only the glyph on the button");
    assertEquals(KeyHints.tooltip(Messages.structureToggleName, SiteServices.TOGGLE_STRUCTURE), item.getToolTipText());
    assertTrue(item.getToolTipText().startsWith(Messages.structureToggleName), item.getToolTipText());
    assertEquals(item.getToolTipText(), MergeToolbar.accessibleName(item), "read without the glyph");
    assertEquals(Messages.structureTitle, MergeToolbar.moreText(Messages.structureToggle), "its full name in More");
  }

  @Test
  void aDragMovesTheColumnWithoutRememberingItsWidth() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    Event e = new Event();
    e.x = 300;
    e.detail = SWT.DRAG;
    viewer.structureSash().notifyListeners(SWT.Selection, e);
    UiDriver.flush();
    assertEquals(300, columnWidth());
    assertEquals(MergeLayout.STRUCTURE_DEFAULT, MergePreferences.structureWidth(), "remembered only once released");
  }

  @Test
  void aLongRowShortensWhoAndKeepsItsChipAtTheRightEdge() {
    String base = "multiplyNumbers=1\n\nz=1\n";
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, "z=1\n", base, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true, "m.properties"));
    viewer.setStructureShown(true);
    UiDriver.flush();
    assertEquals(MergeLayout.STRUCTURE_DEFAULT, columnWidth());
    StructureTree tree = viewer.structurePanel().tree();
    assertEquals("multiplyNumbers (removed) | removed by ours | git", tree.rows().get(0));
    Tree t = tree.tree();
    TreeItem item = t.getItem(0);
    StructureTree.RowParts parts = tree.parts(item);
    var area = t.getClientArea();
    assertTrue(parts.who().endsWith("\u2026") && parts.who().length() > 1, "who shortened: " + parts.who());
    assertTrue(Messages.structureWhoRemovedByOurs.startsWith(parts.who().substring(0, parts.who().length() - 1)));
    assertTrue(parts.whoEnd() <= parts.chipX(), parts + " who before the chip");
    assertTrue(parts.chipX() + parts.chipWidth() <= area.x + area.width, parts + " chip inside " + area);
    // the item's bounds measure its text (for assistive technology, never painted); the column is what scrolls
    ScrollBar h = t.getHorizontalBar();
    assertTrue(h == null || h.getMaximum() - h.getMinimum() <= h.getThumb(),
        "needs no horizontal scrolling: max " + (h == null ? 0 : h.getMaximum()) + ", thumb "
            + (h == null ? 0 : h.getThumb()));
  }

  @Test
  void theHeaderFitsTheNarrowestColumn() {
    viewer.setStructureShown(true);
    UiDriver.flush();
    Event e = new Event();
    e.x = MergeLayout.STRUCTURE_MIN;
    viewer.structureSash().notifyListeners(SWT.Selection, e);
    UiDriver.flush();
    assertEquals(MergeLayout.STRUCTURE_MIN, columnWidth());
    StructurePanel p = viewer.structurePanel();
    var root = p.control().getBounds();
    for (Control c : ((Composite) p.control()).getChildren()) {
      if (c != p.tree().control()) {
        var b = c.getBounds();
        assertTrue(b.x >= 0 && b.x + b.width <= root.width, c + " " + b + " inside the column of " + root.width);
        boolean wraps = (c.getStyle() & SWT.WRAP) != 0; // takes more lines rather than being cut
        var wanted = c.computeSize(wraps ? b.width : SWT.DEFAULT, SWT.DEFAULT);
        assertTrue(wanted.x <= b.width && wanted.y <= b.height, c + " " + b + " shows all of " + wanted);
      }
    }
  }

  @Test
  void theColumnsControlsAreNamedForAssistiveTechnology() {
    StructurePanel p = viewer.structurePanel();
    ToolItem hide = p.hideItem();
    assertEquals(Messages.structureHide, UiDriver.accessibleName(hide.getParent(), hide.getParent().indexOf(hide)));
    assertEquals(Messages.structureTitle, UiDriver.accessibleName(p.tree().tree(), ACC.CHILDID_SELF));
    assertEquals(Messages.structureResize, UiDriver.accessibleName(viewer.structureSash(), ACC.CHILDID_SELF));
  }
}
