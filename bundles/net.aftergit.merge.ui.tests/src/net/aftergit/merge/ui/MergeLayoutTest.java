package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MergeLayoutTest {
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
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    shell.layout(true, true);
    flush();
  }

  static void flush() {
    Display d = Display.getCurrent();
    for (int i = 0; i < 5; i++) {
      while (d.readAndDispatch()) {
        // pending resize and layout events
      }
    }
  }

  @AfterEach
  void close() {
    shell.dispose();
    MergePreferences.reset();
  }

  @Test
  void resultBelowPutsSidesOnTop() {
    viewer.setPaneLayout(PaneLayout.RESULT_BELOW);
    flush();
    var l = viewer.pane(PaneKind.LEFT).getBounds();
    var r = viewer.pane(PaneKind.RIGHT).getBounds();
    var res = viewer.pane(PaneKind.RESULT).getBounds();
    assertEquals(l.y, r.y);
    assertTrue(l.y + l.height <= res.y, "result is below the side panes");
    assertTrue(l.x < r.x);
    assertFalse(viewer.connector(PaneKind.LEFT).isVisible());
    assertFalse(viewer.connector(PaneKind.RIGHT).isVisible());
    assertEquals(PaneLayout.RESULT_BELOW, MergePreferences.layout());
  }

  @Test
  void leftAndResultHidesRight() {
    viewer.setPaneLayout(PaneLayout.LEFT_RESULT);
    flush();
    assertFalse(viewer.pane(PaneKind.RIGHT).isVisible());
    assertFalse(viewer.connector(PaneKind.RIGHT).isVisible());
    var l = viewer.pane(PaneKind.LEFT).getBounds();
    var res = viewer.pane(PaneKind.RESULT).getBounds();
    assertTrue(Math.abs(l.width - res.width) <= 1);
    viewer.setPaneLayout(PaneLayout.ALL);
    assertTrue(viewer.pane(PaneKind.RIGHT).isVisible());
  }

  @Test
  void newViewerStartsWithRememberedLayout() {
    viewer.setPaneLayout(PaneLayout.RESULT_RIGHT);
    MergeViewer second = new MergeViewer(shell);
    assertEquals(PaneLayout.RESULT_RIGHT, second.paneLayout());
  }

  @Test
  void takeLeftWorksWhileLeftPaneHidden() {
    viewer.setPaneLayout(PaneLayout.RESULT_RIGHT);
    viewer.selectBlock(0);
    viewer.takeLeft();
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    viewer.takeBlock(0, ThreeSide.RIGHT, false);
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
  }

  /** Pumps events until the condition holds or two seconds pass. */
  static boolean waitFor(java.util.function.BooleanSupplier c) {
    Display d = Display.getCurrent();
    long end = System.currentTimeMillis() + 2000;
    while (!c.getAsBoolean() && System.currentTimeMillis() < end) {
      if (!d.readAndDispatch()) {
        d.timerExec(10, () -> {});
        d.sleep();
      }
    }
    return c.getAsBoolean();
  }

  @Test
  void hidingFocusedPaneMovesFocusToResult() {
    // no window manager on the virtual display: activate the shell explicitly so focus events arrive
    shell.forceActive();
    viewer.pane(PaneKind.LEFT).text().setFocus();
    assertTrue(waitFor(() -> viewer.focusedPane() == PaneKind.LEFT), "left pane gained the focus");
    viewer.setPaneLayout(PaneLayout.RESULT_RIGHT);
    assertEquals(PaneKind.RESULT, viewer.focusedPane());
    assertTrue(waitFor(() -> viewer.pane(PaneKind.RESULT).text().isFocusControl()), "result pane takes the focus");
    viewer.nextChange();
    assertTrue(viewer.currentBlock() >= 0);
  }
}
