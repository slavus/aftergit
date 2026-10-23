package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectorCanvasTest {
  Shell shell;
  MergeViewer viewer;

  MergeModel open(String base, String ours, String theirs) {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setLayout(new FillLayout());
    shell.setSize(1200, 800);
    viewer = new MergeViewer(shell);
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(m, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    shell.layout(true, true);
    flush();
    paintConnectors();
    return m;
  }

  void paintConnectors() {
    for (PaneKind k : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
      viewer.connector(k).redraw();
      viewer.connector(k).update(); // synchronous paint: no window manager on the virtual display
    }
    flush();
  }

  static void flush() {
    Display d = Display.getCurrent();
    for (int i = 0; i < 5; i++) {
      while (d.readAndDispatch()) {
        // paint
      }
    }
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  @Test
  void oneBandPerChangedBlockOnEachSide() {
    open("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nd\ne\n");
    paintConnectors();
    assertEquals(2, viewer.connector(PaneKind.LEFT).bands().size());
    assertEquals(1, viewer.connector(PaneKind.RIGHT).bands().size());
  }

  @Test
  void paintsOnlyVisibleBands() {
    StringBuilder base = new StringBuilder();
    StringBuilder ours = new StringBuilder();
    StringBuilder theirs = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      base.append("line ").append(i).append('\n');
      ours.append(i % 17 == 0 ? "L " : "line ").append(i).append('\n');
      theirs.append(i % 17 == 0 ? "R " : "line ").append(i).append('\n');
    }
    MergeModel m = open(base.toString(), ours.toString(), theirs.toString());
    int visible = viewer.connector(PaneKind.LEFT).bands().size();
    assertTrue(visible > 0 && visible < m.blocks().size() / 4, "visible bands: " + visible);
  }

  private void click(ConnectorCanvas c, int x, int y, int stateMask) {
    org.eclipse.swt.widgets.Event e = new org.eclipse.swt.widgets.Event();
    e.x = x;
    e.y = y;
    e.button = 1;
    e.stateMask = stateMask;
    c.notifyListeners(SWT.MouseDown, e);
    c.notifyListeners(SWT.MouseUp, e);
    flush();
  }

  @Test
  void takeIgnoreAndAppendThroughButtons() {
    MergeModel m = open("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n");
    ConnectorCanvas left = viewer.connector(PaneKind.LEFT);
    ConnectorCanvas right = viewer.connector(PaneKind.RIGHT);
    var b0 = left.bands().get(0);
    click(left, 3, b0.lt() + 2, 0);
    assertEquals(net.aftergit.merge.core.model.BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    var r0 = right.bands().get(0);
    click(right, right.getClientArea().width - 3, r0.rt() + 2, 0);
    assertEquals(net.aftergit.merge.core.model.BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
    var b1 = left.bands().get(1);
    click(left, left.getClientArea().width - 3, b1.rt() + 2, 0);
    assertEquals(net.aftergit.merge.core.model.BlockState.IGNORED, m.blocks().get(1).state());
  }

  @Test
  void ctrlClickReplacesInsteadOfAppending() {
    MergeModel m = open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    viewer.takeBlock(0, net.aftergit.merge.core.merge.ThreeSide.RIGHT, false);
    flush();
    ConnectorCanvas left = viewer.connector(PaneKind.LEFT);
    var b0 = left.bands().get(0);
    click(left, 3, b0.lt() + 2, SWT.MOD1);
    assertEquals(net.aftergit.merge.core.model.BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    assertEquals("a\nL\nc\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
  }

  @Test
  void ctrlClickReplaceIsOneUndoStep() {
    MergeModel m = open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    viewer.takeBlock(0, net.aftergit.merge.core.merge.ThreeSide.RIGHT, false);
    flush();
    ConnectorCanvas left = viewer.connector(PaneKind.LEFT);
    click(left, 3, left.bands().get(0).lt() + 2, SWT.MOD1);
    assertEquals(net.aftergit.merge.core.model.BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    viewer.undo();
    flush();
    assertEquals(net.aftergit.merge.core.model.BlockState.TAKEN_RIGHT, m.blocks().get(0).state());
    assertEquals("a\nR\nc\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());
  }

  @Test
  void clickActionResetsTheHandCursor() {
    open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    ConnectorCanvas left = viewer.connector(PaneKind.LEFT);
    var b0 = left.bands().get(0);
    org.eclipse.swt.widgets.Event move = new org.eclipse.swt.widgets.Event();
    move.x = 3;
    move.y = b0.lt() + 2;
    left.notifyListeners(SWT.MouseMove, move);
    assertNotNull(left.getCursor(), "hand over the take button");
    click(left, 3, b0.lt() + 2, 0);
    assertNull(left.getCursor(), "the button is gone after the click");
  }

  private static int midY(ConnectorGeometry.Band b) {
    return ((b.lt() + b.rt()) / 2 + (b.lb() + b.rb()) / 2) / 2;
  }

  @Test
  void aTakenSidesBandIsFilledInTheTakenColourAndTheOtherIsOnlyDashed() throws Exception {
    open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    viewer.takeBlock(0, net.aftergit.merge.core.merge.ThreeSide.LEFT, false);
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0); // no current block: no frame over the bands
    paintConnectors();
    ConnectorCanvas left = viewer.connector(PaneKind.LEFT);
    ConnectorCanvas right = viewer.connector(PaneKind.RIGHT);
    int x = left.getClientArea().width / 2;
    assertEquals(Palette.strong(LineStyle.Kind.MODIFIED).getRGB(),
        Screens.pixel(Screens.capture(left, "connector-taken-left"), x, midY(left.bands().get(0))), "ours: taken");
    var shot = Screens.capture(right, "connector-dropped-right");
    // the strip's own background below every band: getBackground() of a bare shell need not be what is painted
    RGB background = Screens.pixel(shot, x, right.getClientArea().height - 2);
    assertEquals(background, Screens.pixel(shot, x, midY(right.bands().get(0))), "theirs: only a dashed outline");
    assertTrue(dashedTop(shot, right, right.bands().get(0)), "theirs: the dashed outline is drawn");
  }

  /** A dash of the dropped colour on the band's top edge, in the part between the button columns. */
  static boolean dashedTop(org.eclipse.swt.graphics.ImageData shot, ConnectorCanvas c, ConnectorGeometry.Band b) {
    int top = Math.min(b.lt(), b.rt());
    int bottom = Math.max(b.lt(), b.rt());
    return Screens.contains(shot, Palette.strong(LineStyle.Kind.DROPPED).getRGB(), 12, 12,
        c.getClientArea().width - 12, top - 1, bottom + 1);
  }

  @Test
  void anAutoResolvedBandUsesTheAutoLight() throws Exception {
    MergeModel m = open("x a z b y\n", "x A z b y\n", "x a z B y\n");
    m.applyResolution(0, "x A z B y\n", net.aftergit.merge.core.model.BlockState.AUTO_RESOLVED,
        net.aftergit.merge.core.model.Strategy.TEXT, "simple", null);
    viewer.refresh();
    paintConnectors();
    for (PaneKind side : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
      ConnectorCanvas c = viewer.connector(side);
      var shot = Screens.capture(c, "connector-auto-" + side.name().toLowerCase(java.util.Locale.ROOT));
      assertEquals(Palette.light(LineStyle.Kind.AUTO).getRGB(),
          Screens.pixel(shot, c.getClientArea().width / 2, midY(c.bands().get(0))), side + ": both sides gave the ◆");
    }
  }

  @Test
  void theCurrentBlocksBandEdgesAreDrawnInTheCurrentColour() throws Exception {
    open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    viewer.selectBlock(0);
    paintConnectors();
    for (PaneKind side : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
      ConnectorCanvas c = viewer.connector(side);
      var shot = Screens.capture(c, "connector-current-" + side.name().toLowerCase(java.util.Locale.ROOT));
      ConnectorGeometry.Band b = c.bands().get(0);
      int x = 4; // the straight part in the button column
      int edge = b.lt(); // the top edge at the connector's left column, in either connector
      RGB current = Palette.current().getRGB();
      assertTrue(Screens.pixel(shot, x, edge).equals(current) || Screens.pixel(shot, x, edge + 1).equals(current),
          side + ": the top edge in the current colour");
      int bottom = b.lb() - 1;
      assertTrue(Screens.pixel(shot, x, bottom).equals(current) || Screens.pixel(shot, x, bottom - 1).equals(current),
          side + ": the bottom edge in the current colour");
      RGB strong = Palette.strong(LineStyle.Kind.CONFLICT).getRGB();
      for (int y = b.lt() - 1; y <= b.lb() + 1; y++) {
        for (int at = 0; at < c.getClientArea().width; at++) {
          assertNotEquals(strong, Screens.pixel(shot, at, y), side + ": no conflict outline beside the current edges at "
              + at + "," + y);
        }
      }
    }
  }

  @Test
  void theCurrentBlockIsOutlinedOnTheOverviewStripes() throws Exception {
    open("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    viewer.selectBlock(0);
    flush();
    ChangeStripe stripe = viewer.pane(PaneKind.RESULT).stripe();
    StripeGeometry.Marker marker = stripe.markers().get(0);
    var shot = Screens.capture(stripe, "stripe-current");
    assertEquals(Palette.current().getRGB(), Screens.pixel(shot, 0, Math.max(0, marker.y() - 1)),
        "the marker of the current block has a current outline");
  }
}
