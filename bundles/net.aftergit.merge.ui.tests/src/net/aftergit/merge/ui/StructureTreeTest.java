package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.NodeState;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.core.structure.Visibility;
import net.aftergit.merge.core.structure.Who;
import net.aftergit.merge.git.Labels;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Structure-compare design §2: rows, filter, click and Enter, the current block, empty states, icons. */
class StructureTreeTest {
  Shell shell;
  Shell side;
  MergeViewer viewer;
  StructureTree tree;
  final Object owner = new Object();

  @BeforeEach
  void open() {
    MergePreferences.reset();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new FillLayout());
    viewer = new MergeViewer(shell);
    session("messages.properties", StructureControllerTest.BASE, StructureControllerTest.OURS,
        StructureControllerTest.THEIRS);
    side = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    side.setSize(400, 400);
    side.setLayout(new FillLayout());
    tree = new StructureTree(side, viewer, false);
    viewer.structure().setShowing(owner, true);
    shell.open();
    side.open();
    UiDriver.flush();
  }

  @AfterEach
  void close() {
    JavaBridge.setAvailableForTests(null);
    StructureController.setImagesForTests(null);
    side.dispose();
    shell.dispose();
    MergePreferences.reset();
  }

  MergeModel session(String name, String base, String ours, String theirs) {
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true, name));
    UiDriver.flush();
    return m;
  }

  static void click(Tree t, TreeItem item) {
    Rectangle b = item.getBounds();
    Event e = new Event();
    e.button = 1;
    e.x = b.x + 4;
    e.y = b.y + b.height / 2;
    t.notifyListeners(SWT.MouseUp, e);
    UiDriver.flush();
  }

  @Test
  void rowsShowTheChangedNodesWithWhoAndChip() {
    assertEquals(List.of("a | both | conflict", "c | ours | git"), tree.rows());
  }

  @Test
  void showAllAddsTheUnchangedNodesAndIsRemembered() {
    viewer.structure().setShowAll(true);
    UiDriver.flush();
    assertEquals(List.of("a | both | conflict", "b |  | ", "c | ours | git"), tree.rows());
    assertTrue(MergePreferences.structureShowAll());
  }

  @Test
  void aNodeRemovedInTheResultIsMarkedRemoved() {
    session("m.properties", "a=1\n\nb=1\n", "a=1\n", "a=1\n\nb=1\n");
    assertEquals(List.of("b (removed) | removed by ours | git"), tree.rows());
  }

  /** The items' own texts, children after their parent, as assistive technology and type-ahead read them. */
  static List<String> itemTexts(Tree t) {
    List<String> out = new java.util.ArrayList<>();
    for (TreeItem i : t.getItems()) {
      itemTexts(i, out);
    }
    return out;
  }

  private static void itemTexts(TreeItem item, List<String> out) {
    out.add(item.getText());
    for (TreeItem c : item.getItems()) {
      itemTexts(c, out);
    }
  }

  @Test
  void eachRowCarriesItsNameWhoAndStateInWordsAsItsText() {
    assertEquals(List.of("a, both, conflict", "c, ours, resolved by git"), itemTexts(tree.tree()));
    session("m.properties", "a=1\n\nb=1\n", "a=1\n", "a=1\n\nb=1\n");
    assertEquals(List.of("b (removed), removed by ours, resolved by git"), itemTexts(tree.tree()));
  }

  @Test
  void typeAheadFindsANodeByTheStartOfItsName() {
    TreeItem found = null;
    for (TreeItem i : tree.tree().getItems()) {
      if (found == null && i.getText().toLowerCase().startsWith("c")) {
        found = i;
      }
    }
    assertNotNull(found, "texts: " + itemTexts(tree.tree()));
    assertEquals("c", ((StructureNode) found.getData()).label());
  }

  @Test
  void theStateInWordsIsTheStatusLinesName() {
    assertEquals("auto-resolved", StructureLabels.state(NodeState.AUTO_REVIEWED));
    assertEquals("auto-resolved \u00B7 needs review", StructureLabels.state(NodeState.AUTO_TO_REVIEW));
    assertEquals("Take Left \u25C0", StructureLabels.state(NodeState.TAKEN_LEFT));
    assertEquals("", StructureLabels.state(NodeState.NONE));
  }

  @Test
  void aPropertyWithAnEmptyKeyIsNamed() {
    session("m.properties", "=1\na=1\n", "=2\na=1\n", "=1\na=1\n");
    assertEquals(List.of("(empty key) | ours | git"), tree.rows());
  }

  @Test
  void aDoubleClickStaysOnTheFirstBlock() {
    session("m.properties", "k=a \\\n  b \\\n  c\nz=1\n", "k=A \\\n  b \\\n  c\nz=1\n",
        "k=a \\\n  b \\\n  C\nz=1\n");
    Tree t = tree.tree();
    click(t, t.getItem(0));
    assertEquals(0, viewer.selectedBlock());
    Rectangle b = t.getItem(0).getBounds();
    Event e = new Event();
    e.button = 1;
    e.count = 2; // the second click of a double-click
    e.x = b.x + 4;
    e.y = b.y + b.height / 2;
    t.notifyListeners(SWT.MouseUp, e);
    UiDriver.flush();
    assertEquals(0, viewer.selectedBlock(), "the double-click lands on the first block");
  }

  @Test
  void aClickMakesTheFirstBlockCurrentAndTheNextClicksCycleThroughTheNodesBlocks() {
    // key k spans three lines; ours changes the first, theirs the last: two blocks of one node
    session("m.properties", "k=a \\\n  b \\\n  c\nz=1\n", "k=A \\\n  b \\\n  c\nz=1\n",
        "k=a \\\n  b \\\n  C\nz=1\n");
    Tree t = tree.tree();
    TreeItem k = t.getItem(0);
    assertEquals("k", ((StructureNode) k.getData()).label());
    click(t, k);
    assertEquals(0, viewer.selectedBlock());
    click(t, t.getItem(0));
    assertEquals(1, viewer.selectedBlock());
    click(t, t.getItem(0));
    assertEquals(0, viewer.selectedBlock());
  }

  @Test
  void enterJumpsLikeAClick() {
    Tree t = tree.tree();
    t.setSelection(t.getItem(1)); // c
    Event e = new Event();
    e.keyCode = SWT.CR;
    t.notifyListeners(SWT.KeyDown, e);
    assertEquals(1, viewer.selectedBlock());
  }

  @Test
  void theCurrentBlockSelectsItsNodeWithoutTakingTheFocus() {
    Tree t = tree.tree();
    // the side shell opened last and the tree is its only control: the focus goes to the result first
    shell.forceActive();
    viewer.pane(PaneKind.RESULT).viewer().getTextWidget().forceFocus();
    UiDriver.flush();
    assertFalse(t.isFocusControl(), "the focus is in the result before the block moves");
    viewer.selectBlock(1);
    UiDriver.flush();
    assertEquals(1, t.getSelectionCount());
    assertEquals("c", ((StructureNode) t.getSelection()[0].getData()).label());
    assertFalse(t.isFocusControl());
  }

  @Test
  void emptyStatesNameTheReason() {
    assertNull(tree.emptyText(), "the tree shows");
    session("notes.txt", "a\n", "b\n", "c\n");
    assertEquals(Messages.structureNotAvailable, tree.emptyText());
    JavaBridge.setAvailableForTests(false);
    session("A.java", "class A {}\n", "class A { }\n", "class A {  }\n");
    assertEquals(Messages.structureNeedsJava, tree.emptyText());
    JavaBridge.setAvailableForTests(null);
    session("m.properties", "a=1\n# x\n", "a=1\n# y\n", "a=1\n# x\n"); // only the trailing comment changed
    assertEquals(Messages.structureNoChanges, tree.emptyText());
  }

  @Test
  void neutralIconsAreDrawnLettersOnePerKind() {
    ImageData d = NeutralIcons.INSTANCE.descriptor(NodeKind.METHOD, Visibility.PUBLIC).getImageData(100);
    assertEquals(16, d.width);
    boolean drawn = false;
    for (int y = 0; y < d.height; y++) {
      for (int x = 0; x < d.width; x++) {
        drawn |= d.getAlpha(x, y) > 128;
      }
    }
    assertTrue(drawn, "the letter is opaque");
    assertEquals(32, NeutralIcons.INSTANCE.descriptor(NodeKind.METHOD, Visibility.PUBLIC).getImageData(200).width);
    assertSame(NeutralIcons.INSTANCE.descriptor(NodeKind.METHOD, Visibility.PUBLIC),
        NeutralIcons.INSTANCE.descriptor(NodeKind.METHOD, Visibility.PRIVATE), "one icon per kind");
  }

  @Test
  void aFailingJavaIconGivesTheNeutralOneAndIsLoggedOnce() {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (s, plugin) -> {
      if (s.getMessage() != null && s.getMessage().contains("icons of the structure tree")) {
        logged.add(s);
      }
    };
    Platform.addLogListener(l);
    try {
      AtomicInteger asked = new AtomicInteger();
      StructureController.setImagesForTests((kind, visibility) -> {
        asked.incrementAndGet();
        throw new IllegalStateException("JDT UI did not start");
      });
      session("A.java", "class A {\n  void m() {}\n  void n() {}\n}\n", "class A {\n  void m() { }\n  void n() {}\n}\n",
          "class A {\n  void m() {}\n  void n() { }\n}\n");
      Tree t = tree.tree();
      assertTrue(t.getItemCount() > 0, "rows: " + tree.rows());
      // the icons are asked for only while rows are painted, and GTK paints on its own frame clock: neither redraw
      // with update nor a flush paints at once, so the test waits for the paints it counts on
      AtomicInteger painted = new AtomicInteger();
      t.addListener(SWT.PaintItem, e -> painted.incrementAndGet());
      t.redraw();
      ConflictRepos.waitUntil(() -> asked.get() > 0, 10, "the first paint of the rows");
      for (int i = 0; i < 2; i++) {
        int before = painted.get();
        t.redraw();
        ConflictRepos.waitUntil(() -> painted.get() > before, 10, "another paint of the rows");
      }
      assertEquals(1, asked.get(), "the tree stops asking after the first failure");
      assertEquals(1, logged.size(), "once: " + logged);
    } finally {
      Platform.removeLogListener(l);
    }
  }

  /** Appends to the result and waits for the structure computed after it. */
  private void appendAndRecompute(String line) throws Exception {
    StructureController c = viewer.structure();
    int n = c.computations();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLength(), 0, line);
    ConflictRepos.waitUntil(() -> c.computations() > n, 5, "the computation after the edit");
    UiDriver.flush();
  }

  private static String path(TreeItem item) {
    return ((StructureNode) item.getData()).path();
  }

  @Test
  void aRecomputationKeepsTheNodesTheUserCollapsed() throws Exception {
    session("A.java", "class A {\n  int a;\n}\nclass B {\n  int b;\n}\n",
        "class A {\n  int a = 1;\n}\nclass B {\n  int b;\n}\n", "class A {\n  int a;\n}\nclass B {\n  int b = 2;\n}\n");
    Tree t = tree.tree();
    assertEquals(2, t.getItemCount(), "rows: " + tree.rows());
    TreeItem a = t.getItem(0);
    assertTrue(a.getExpanded(), "expanded at first");
    a.setExpanded(false); // as the user's click on the arrow: SWT sends Collapse
    Event e = new Event();
    e.item = a;
    t.notifyListeners(SWT.Collapse, e);
    UiDriver.flush();
    appendAndRecompute("class C {}\n");
    assertEquals("type:A", path(t.getItem(0)));
    assertFalse(t.getItem(0).getExpanded(), "still collapsed");
    assertTrue(t.getItem(1).getExpanded(), "B stays expanded");
    session("A.java", "class A {\n  int a;\n}\n", "class A {\n  int a = 1;\n}\n", "class A {\n  int a;\n}\n");
    assertTrue(t.getItem(0).getExpanded(), "a new session starts expanded");
  }

  @Test
  void aRecomputationKeepsTheScrollPosition() throws Exception {
    String base = java.util.stream.IntStream.range(0, 200).mapToObj(i -> "k" + i + "=1\n")
        .collect(java.util.stream.Collectors.joining());
    session("m.properties", base, base.replace("k150=1\n", "k150=2\n"), base);
    viewer.structure().setShowAll(true);
    UiDriver.flush();
    Tree t = tree.tree();
    t.setTopItem(t.getItem(120));
    // GTK applies the scroll on its own frame clock: wait for it instead of counting on a flush
    ConflictRepos.waitUntil(() -> !path(t.getItem(0)).equals(path(t.getTopItem())), 10, "the scroll down");
    UiDriver.flush();
    String top = path(t.getTopItem());
    assertNotEquals(path(t.getItem(0)), top, "scrolled down");
    appendAndRecompute("zz=1\n");
    ConflictRepos.waitUntil(() -> top.equals(path(t.getTopItem())), 10,
        "the same row on top after the recomputation, now: " + path(t.getTopItem()));
    assertEquals(top, path(t.getTopItem()), "the same row on top after the recomputation");
  }

  @Test
  void theNodeOfABlockIsTheDeepestOne() {
    var leaf = new StructureNode("A/m", NodeKind.METHOD, "m", Visibility.PUBLIC, Who.OURS, true, NodeState.GIT, 1, 3,
        List.of(3), List.of());
    var parent = new StructureNode("A", NodeKind.CLASS, "A", Visibility.PUBLIC, Who.OURS, true, NodeState.GIT, 1, 3,
        List.of(3), List.of(leaf));
    assertSame(leaf, StructureTree.nodeOf(List.of(parent), 3));
    assertNull(StructureTree.nodeOf(List.of(parent), 4));
  }
}
