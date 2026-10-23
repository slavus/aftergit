package net.aftergit.merge.ui;

import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/** 8px overview of all blocks of one pane with the visible area; click jumps to a block. */
final class ChangeStripe extends Canvas {
  private MergeViewer viewer;
  private PaneKind pane;
  private List<StripeGeometry.Marker> markers = List.of();

  ChangeStripe(Composite parent) {
    super(parent, SWT.DOUBLE_BUFFERED);
    addPaintListener(e -> paint(e.gc));
    addListener(SWT.MouseDown, e -> {
      int block = StripeGeometry.hit(markers(), e.y);
      if (e.button == 1 && block >= 0 && viewer != null) {
        viewer.revealBlock(block, pane);
      }
    });
    addListener(SWT.MouseMove, e -> setCursor(StripeGeometry.hit(markers, e.y) >= 0
        ? getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null));
  }

  void setSource(MergeViewer viewer, PaneKind pane) {
    this.viewer = viewer;
    this.pane = pane;
  }

  @Override
  public Point computeSize(int wHint, int hHint, boolean changed) {
    return new Point(StripeGeometry.WIDTH, hHint == SWT.DEFAULT ? 100 : hHint);
  }

  List<StripeGeometry.Marker> markers() {
    if (viewer == null || !(viewer.getInput() instanceof MergeSession s)) {
      return List.of();
    }
    markers = StripeGeometry.markers(s.model(), pane, viewer.pane(pane).lineCount(), getClientArea().height,
        viewer.origins());
    return markers;
  }

  private void paint(GC gc) {
    int w = getClientArea().width;
    for (StripeGeometry.Marker m : markers()) {
      Color c = m.strong() ? Palette.strong(m.kind()) : Palette.light(m.kind());
      if (c != null) {
        gc.setBackground(c);
        gc.fillRectangle(1, m.y(), w - 2, m.height());
      }
    }
    if (viewer != null && pane == PaneKind.RESULT) {
      int[] lines = viewer.errorLines();
      if (lines.length > 0) {
        gc.setBackground(Palette.error());
        for (int y : StripeGeometry.errorMarks(lines, Math.max(1, viewer.pane(pane).lineCount()), getClientArea().height)) {
          gc.fillRectangle(1, y, w - 2, 2);
        }
      }
    }
    if (viewer != null) {
      var t = viewer.pane(pane).text();
      int visible = t.getClientArea().height / Math.max(1, t.getLineHeight());
      int[] vp = StripeGeometry.viewport(t.getTopIndex(), visible, Math.max(1, viewer.pane(pane).lineCount()),
          getClientArea().height);
      gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
      gc.drawRectangle(0, vp[0], w - 1, vp[1] - 1);
    }
    // last: the viewport rectangle covers the outer columns wherever the whole file fits in the pane
    int current = viewer == null ? -1 : viewer.selectedBlock();
    for (StripeGeometry.Marker m : markers) {
      if (m.block() == current) { // block-states design §2: where the current block is in the file
        gc.setForeground(Palette.current());
        gc.setLineWidth(1);
        gc.drawRectangle(0, Math.max(0, m.y() - 1), w - 1, m.height() + 1);
      }
    }
  }
}
