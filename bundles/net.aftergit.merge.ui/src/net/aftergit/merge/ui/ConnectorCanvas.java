package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

/**
 * The 70px strip between a side pane and the result with one Bézier band per changed block (design §6.5,
 * ui-polish §2, block-states §1-§2).
 */
final class ConnectorCanvas extends Canvas {
  private final MergeViewer viewer;
  private final PaneKind side;
  private List<ConnectorGeometry.Band> bands = List.of();

  ConnectorCanvas(Composite parent, MergeViewer viewer, PaneKind side) {
    super(parent, SWT.DOUBLE_BUFFERED);
    this.viewer = viewer;
    this.side = side;
    addPaintListener(e -> paint(e.gc));
    addListener(SWT.MouseDown, e -> {
      if (e.button != 1) {
        return;
      }
      Target t = targetAt(e.x, e.y);
      if (t == null) {
        return;
      }
      int block = t.band().block();
      switch (t.action()) {
        case TAKE -> viewer.takeBlock(block, threeSide(), (e.stateMask & SWT.MOD1) != 0);
        case IGNORE -> viewer.ignoreBlock(block);
        case REVERT_AI -> viewer.revertBlock(block);
        case REVIEW_AI -> viewer.markBlockReviewed(block);
        case SHOW_PROPOSAL -> viewer.showPendingProposal(block);
      }
      // the button is usually gone now; the next mouse move shows the hand again where one is left
      if (!isDisposed()) {
        setCursor(null);
      }
    });
    addListener(SWT.MouseMove, e -> {
      Target t = targetAt(e.x, e.y);
      setCursor(t != null ? getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
      String tip = t == null ? null : tooltip(t.action());
      if (!Objects.equals(tip, getToolTipText())) {
        setToolTipText(tip);
      }
    });
    addListener(SWT.MouseVerticalWheel, e -> {
      e.doit = false;
      viewer.nextChangeIn(side, e.count < 0);
    });
  }

  /**
   * Take is offered on an open conflict, on the other side of a taken one (append), and on an automatic resolution
   * (block-states design §4); on an AI resolution once it is reviewed, since ↶ stands in its place until then.
   */
  static boolean canTake(MergeBlock b, ThreeSide side) {
    if (!b.type().isChange(side)) {
      return false;
    }
    BlockState s = b.state();
    if (s == BlockState.UNRESOLVED) {
      return true;
    }
    if (!b.isConflict()) {
      return false;
    }
    BlockState other = side == ThreeSide.LEFT ? BlockState.TAKEN_RIGHT : BlockState.TAKEN_LEFT;
    return s == other || s == BlockState.AUTO_RESOLVED || (s == BlockState.AI_RESOLVED && !b.needsReview());
  }

  static boolean canIgnore(MergeBlock b) {
    return b.state() == BlockState.UNRESOLVED;
  }

  private ThreeSide threeSide() {
    return side == PaneKind.LEFT ? ThreeSide.LEFT : ThreeSide.RIGHT;
  }

  /** What a click at (x, y) does (agents design §3 adds ↶ / ✓ for agents' blocks and ✨ for waiting proposals). */
  enum Action { TAKE, IGNORE, REVERT_AI, REVIEW_AI, SHOW_PROPOSAL }

  record Target(ConnectorGeometry.Band band, Action action) {}

  /** Replaces the proposal dialog in tests; null puts the dialog back. */
  static void setProposalChooserForTests(BiFunction<Shell, Proposal, PendingProposalDialog.Choice> chooser) {
    MergeViewer.setProposalChooserForTests(chooser);
  }

  /** A block an agent resolved that is not reviewed yet: it gets ↶ and ✓ in the connector. */
  static boolean aiToReview(MergeBlock b) {
    return b.state() == BlockState.AI_RESOLVED && b.needsReview();
  }

  Target targetAt(int x, int y) {
    if (!(viewer.getInput() instanceof MergeSession s)) {
      return null;
    }
    int width = getClientArea().width;
    // while a save or a dialog runs a waiting proposal can be neither applied nor discarded
    Map<Integer, Proposal> waiting = viewer.agentsHeld() ? Map.of() : viewer.pendingProposals();
    for (ConnectorGeometry.Band band : bands) {
      MergeBlock b = s.model().blocks().get(band.block());
      if (waiting.containsKey(b.index()) && ConnectorGeometry.nearCenter(band, x, y, width)) {
        return new Target(band, Action.SHOW_PROPOSAL);
      }
      ConnectorGeometry.Hit h = ConnectorGeometry.hit(band, x, y, width, side == PaneKind.LEFT);
      if (h == ConnectorGeometry.Hit.TAKE && canTake(b, threeSide())) {
        return new Target(band, Action.TAKE);
      }
      if (h == ConnectorGeometry.Hit.IGNORE && canIgnore(b)) {
        return new Target(band, Action.IGNORE);
      }
      if (h == ConnectorGeometry.Hit.TAKE && aiToReview(b)) {
        return new Target(band, Action.REVERT_AI);
      }
      if (h == ConnectorGeometry.Hit.IGNORE && aiToReview(b)) {
        return new Target(band, Action.REVIEW_AI);
      }
    }
    return null;
  }

  private static String tooltip(Action a) {
    return switch (a) {
      case TAKE, IGNORE -> null;
      case REVERT_AI -> Messages.revert;
      case REVIEW_AI -> Messages.markReviewed;
      case SHOW_PROPOSAL -> Messages.proposalShow;
    };
  }

  private List<Integer> sparkles = List.of();

  /** The blocks that got a ✨ in the last paint; for tests. */
  List<Integer> sparkles() {
    return sparkles;
  }

  List<ConnectorGeometry.Band> bands() {
    return bands;
  }

  PaneKind side() {
    return side;
  }

  List<ConnectorGeometry.Band> computeBands() {
    if (!(viewer.getInput() instanceof MergeSession s)) {
      return List.of();
    }
    MergeModel m = s.model();
    MergePane sidePane = viewer.pane(side);
    MergePane resultPane = viewer.pane(PaneKind.RESULT);
    int height = getClientArea().height;
    List<ConnectorGeometry.Band> out = new ArrayList<>();
    for (MergeBlock b : m.blocks()) {
      if (!BlockStyles.visible(b, side)) {
        continue;
      }
      int st = sidePane.lineY(BlockStyles.start(b, side), this);
      int sb = sidePane.lineY(BlockStyles.end(b, side), this);
      int rt = resultPane.lineY(b.resultStart(), this);
      int rb = resultPane.lineY(b.resultEnd(), this);
      ConnectorGeometry.Band band = side == PaneKind.LEFT
          ? new ConnectorGeometry.Band(b.index(), st, sb, rt, rb)
          : new ConnectorGeometry.Band(b.index(), rt, rb, st, sb);
      if (ConnectorGeometry.visible(band, height)) {
        out.add(band);
      } else if (Math.min(band.lt(), band.rt()) > height) {
        break; // blocks are ordered: everything after this one is below the strip
      }
    }
    return List.copyOf(out);
  }

  private void paint(GC gc) {
    bands = computeBands();
    if (bands.isEmpty()) {
      sparkles = List.of();
      return;
    }
    Map<Integer, Proposal> waiting = viewer.pendingProposals();
    List<Integer> drawn = new ArrayList<>();
    MergeModel m = ((MergeSession) viewer.getInput()).model();
    int width = getClientArea().width;
    gc.setAntialias(SWT.ON);
    for (ConnectorGeometry.Band band : bands) {
      MergeBlock b = m.blocks().get(band.block());
      ConnectorGeometry.Shape s = ConnectorGeometry.shape(band, width);
      BlockState state = b.state();
      LineStyle.Kind kind = BlockStyles.kind(b, side, viewer.origin(b.index()));
      if (state == BlockState.AI_RESOLVED) {
        // phase-1 look kept: a dotted outline in the AI color
        gc.setLineStyle(SWT.LINE_DOT);
        gc.setForeground(Palette.strong(LineStyle.Kind.AI));
        drawOutline(gc, s);
      } else if (kind == LineStyle.Kind.DROPPED) {
        // block-states design §1: the side not taken is only a dashed outline
        gc.setLineStyle(SWT.LINE_DASH);
        gc.setForeground(Palette.strong(LineStyle.Kind.DROPPED));
        drawOutline(gc, s);
      } else {
        Color fill = fill(b, kind);
        if (fill != null) {
          Path p = outline(band, s, width);
          gc.setBackground(fill);
          gc.fillPath(p);
          p.dispose();
        }
        // the current block's edges replace the outline: under them it would show as a hairline
        boolean current = b.index() == viewer.selectedBlock();
        if (!current && (state == BlockState.IGNORED || (b.isConflict() && state == BlockState.UNRESOLVED))) {
          gc.setLineStyle(SWT.LINE_SOLID);
          gc.setForeground(Palette.strong(kind));
          drawOutline(gc, s);
        }
      }
      gc.setLineStyle(SWT.LINE_SOLID);
      if (b.index() == viewer.selectedBlock()) {
        drawCurrentEdges(gc, band, s, width);
      }
      drawButtons(gc, band, b, width);
      boolean pendingHere = waiting.containsKey(b.index());
      if (pendingHere || state == BlockState.AI_RESOLVED) {
        drawSparkle(gc, band, width, pendingHere);
        drawn.add(b.index());
      }
      if (aiToReview(b)) {
        drawReviewButtons(gc, band, width);
      }
    }
    sparkles = List.copyOf(drawn);
  }

  /**
   * The band's fill (block-states Ruling 9): a taken side in modified.strong, everything else in the light shade of
   * its kind; null for an ignored block (only outlined).
   */
  static Color fill(MergeBlock b, LineStyle.Kind kind) {
    BlockState s = b.state();
    if (s == BlockState.IGNORED) {
      return null;
    }
    if (b.isConflict() && (s == BlockState.TAKEN_LEFT || s == BlockState.TAKEN_RIGHT || s == BlockState.TAKEN_BOTH)) {
      return Palette.strong(LineStyle.Kind.MODIFIED);
    }
    return Palette.light(kind);
  }

  /** The current block's frame goes on along the band's top and bottom edge, 2 px (3 px thick) in the current colour. */
  private void drawCurrentEdges(GC gc, ConnectorGeometry.Band band, ConnectorGeometry.Shape s, int width) {
    gc.setLineStyle(SWT.LINE_SOLID);
    gc.setLineWidth(Appearance.currentFrame());
    gc.setForeground(Palette.current());
    // the line's centre, so that its rows match the panes' frame; SWT moves a line of odd width half a pixel down
    int w = Appearance.currentFrame();
    float shift = w % 2 == 1 ? 0.5f : 0f;
    float o = w / 2f - shift;
    float u = w / 2f + shift;
    // the panes' frame covers the block's first and last rows (2 or 3): the edges sit on the same rows
    Path top = new Path(getDisplay());
    top.moveTo(0, band.lt() + o);
    top.lineTo(s.top().x0(), s.top().y0() + o);
    top.cubicTo(s.top().c1x(), s.top().c1y() + o, s.top().c2x(), s.top().c2y() + o, s.top().x1(), s.top().y1() + o);
    top.lineTo(width, band.rt() + o);
    gc.drawPath(top);
    top.dispose();
    Path bottom = new Path(getDisplay());
    bottom.moveTo(width, band.rb() - u);
    bottom.lineTo(s.bottom().x0(), s.bottom().y0() - u);
    bottom.cubicTo(s.bottom().c1x(), s.bottom().c1y() - u, s.bottom().c2x(), s.bottom().c2y() - u, s.bottom().x1(),
        s.bottom().y1() - u);
    bottom.lineTo(0, band.lb() - u);
    gc.drawPath(bottom);
    bottom.dispose();
    gc.setLineWidth(1);
  }

  /** ✨ in the AI color in the middle of the band; a waiting proposal also gets a ring (click to see it). */
  private void drawSparkle(GC gc, ConnectorGeometry.Band band, int width, boolean waiting) {
    int[] c = ConnectorGeometry.center(band, width);
    int x = c[0];
    int y = c[1];
    int r = 6;
    int k = 2; // a narrower waist reads as a plus sign, and with the ring as an "add" button
    gc.setBackground(Palette.strong(LineStyle.Kind.AI));
    gc.fillPolygon(new int[] {x, y - r, x + k, y - k, x + r, y, x + k, y + k, x, y + r, x - k, y + k, x - r, y,
        x - k, y - k});
    if (waiting) {
      gc.setLineStyle(SWT.LINE_SOLID);
      gc.setForeground(Palette.strong(LineStyle.Kind.AI));
      gc.drawOval(x - r - 2, y - r - 2, 2 * r + 4, 2 * r + 4);
    }
  }

  /** ↶ (back to the conflict) at the side pane's edge and ✓ (reviewed) at the result's edge. */
  private void drawReviewButtons(GC gc, ConnectorGeometry.Band band, int width) {
    gc.setLineStyle(SWT.LINE_SOLID);
    gc.setForeground(buttonColor());
    int w = width - 1;
    int h = getClientArea().height;
    boolean sideLeft = side == PaneKind.LEFT;
    int sideTop = sideLeft ? band.lt() : band.rt();
    int sideBottom = Math.max(sideLeft ? band.lb() : band.rb(), sideTop + ConnectorGeometry.MIN_BUTTON);
    int resTop = sideLeft ? band.rt() : band.lt();
    int resBottom = Math.max(sideLeft ? band.rb() : band.lb(), resTop + ConnectorGeometry.MIN_BUTTON);
    int sy = (Math.max(0, sideTop) + Math.min(h, sideBottom)) / 2;
    int sx = sideLeft ? ConnectorGeometry.BUTTON / 2 : w - ConnectorGeometry.BUTTON / 2;
    // ↶: an arc over the top from right to left, its head pointing down at the left end
    gc.drawArc(sx - 3, sy - 4, 8, 8, 0, 180);
    gc.drawPolyline(new int[] {sx - 5, sy - 1, sx - 3, sy + 2, sx - 1, sy - 1});
    int ry = (Math.max(0, resTop) + Math.min(h, resBottom)) / 2;
    int rx = sideLeft ? w - ConnectorGeometry.BUTTON / 2 : ConnectorGeometry.BUTTON / 2;
    gc.drawPolyline(new int[] {rx - 3, ry, rx - 1, ry + 2, rx + 3, ry - 3});
  }

  /**
   * The color of the connector's buttons: the editor text color, so they stay readable on the dark theme too (the
   * strip takes its background from the theme, the widget foreground does not follow it).
   */
  private Color buttonColor() {
    return viewer.pane(PaneKind.RESULT).text().getForeground();
  }

  private void drawButtons(GC gc, ConnectorGeometry.Band band, MergeBlock b, int width) {
    gc.setForeground(buttonColor());
    int w = width - 1;
    boolean sideLeft = side == PaneKind.LEFT;
    int sideTop = sideLeft ? band.lt() : band.rt();
    int sideBottom = Math.max(sideLeft ? band.lb() : band.rb(), sideTop + ConnectorGeometry.MIN_BUTTON);
    int resTop = sideLeft ? band.rt() : band.lt();
    int resBottom = Math.max(sideLeft ? band.rb() : band.lb(), resTop + ConnectorGeometry.MIN_BUTTON);
    int h = getClientArea().height;
    if (canTake(b, threeSide())) {
      int cy = (Math.max(0, sideTop) + Math.min(h, sideBottom)) / 2;
      int cx = sideLeft ? ConnectorGeometry.BUTTON / 2 : w - ConnectorGeometry.BUTTON / 2;
      int dir = sideLeft ? 1 : -1; // » points to the result
      for (int off : new int[] {-2, 2}) {
        int x = cx + off * dir;
        gc.drawPolyline(new int[] {x - 2 * dir, cy - 3, x + dir, cy, x - 2 * dir, cy + 3});
      }
    }
    if (canIgnore(b)) {
      int cy = (Math.max(0, resTop) + Math.min(h, resBottom)) / 2;
      int cx = sideLeft ? w - ConnectorGeometry.BUTTON / 2 : ConnectorGeometry.BUTTON / 2;
      gc.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
      gc.drawLine(cx - 3, cy + 3, cx + 3, cy - 3);
    }
  }

  private Path outline(ConnectorGeometry.Band band, ConnectorGeometry.Shape s, int width) {
    Path p = new Path(getDisplay());
    p.moveTo(0, band.lt());
    p.lineTo(s.top().x0(), s.top().y0());
    p.cubicTo(s.top().c1x(), s.top().c1y(), s.top().c2x(), s.top().c2y(), s.top().x1(), s.top().y1());
    p.lineTo(width, band.rt());
    p.lineTo(width, band.rb());
    p.lineTo(s.bottom().x0(), s.bottom().y0());
    p.cubicTo(s.bottom().c1x(), s.bottom().c1y(), s.bottom().c2x(), s.bottom().c2y(), s.bottom().x1(),
        s.bottom().y1());
    p.lineTo(0, band.lb());
    p.close();
    return p;
  }

  /** The band's top and bottom curves, {@link Appearance#border()} wide (preferences design Ruling 12). */
  private void drawOutline(GC gc, ConnectorGeometry.Shape s) {
    gc.setLineWidth(Appearance.border());
    drawCurve(gc, s.top());
    drawCurve(gc, s.bottom());
    gc.setLineWidth(1);
  }

  private void drawCurve(GC gc, ConnectorGeometry.Curve c) {
    Path p = new Path(getDisplay());
    p.moveTo(c.x0(), c.y0());
    p.cubicTo(c.c1x(), c.c1y(), c.c2x(), c.c2y(), c.x1(), c.y1());
    gc.drawPath(p);
    p.dispose();
  }
}
