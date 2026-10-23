package net.aftergit.merge.ui;

import java.util.EnumMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;

/**
 * SmartGit columns: pane | connector | pane | connector | pane | slider (connectors 70 px by default), panes of equal
 * width (spec §6.2). Children carry a {@link Slot} as layout data.
 */
final class MergeLayout extends Layout {
  static final int CONNECTOR = 70;
  /** The width of each connector (preferences design §3: 40, 70 or 100 px). */
  private int connector = CONNECTOR;

  void setConnectorWidth(int width) { this.connector = width; }

  int connectorWidth() { return connector; }

  enum Slot { LEFT, LEFT_CONNECTOR, RESULT, RIGHT_CONNECTOR, RIGHT, SLIDER, SASH, STRUCTURE, STRUCTURE_SASH }

  static final int STRUCTURE_MIN = 120;
  static final int STRUCTURE_DEFAULT = 260;
  private boolean structureShown;
  private int structureWidth = STRUCTURE_DEFAULT;

  private PaneLayout mode;

  MergeLayout(PaneLayout mode) {
    this.mode = mode;
  }

  PaneLayout mode() {
    return mode;
  }

  void setMode(PaneLayout mode) {
    this.mode = mode;
  }

  static Map<Slot, Control> slots(Composite c) {
    Map<Slot, Control> m = new EnumMap<>(Slot.class);
    for (Control child : c.getChildren()) {
      if (child.getLayoutData() instanceof Slot s) {
        m.put(s, child);
      }
    }
    return m;
  }

  @Override
  protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
    return new Point(wHint == SWT.DEFAULT ? 900 : wHint, hHint == SWT.DEFAULT ? 400 : hHint);
  }

  private double ratio = 0.5;
  static final int SASH = 4;
  static final int SIDE_GAP = 4;
  static final int BELOW_INDENT = 9;

  void setRatio(double ratio) {
    this.ratio = Math.max(0.1, Math.min(0.9, ratio));
  }

  double ratio() {
    return ratio;
  }

  void setStructureShown(boolean shown) { structureShown = shown; }
  boolean structureShown() { return structureShown; }
  void setStructureWidth(int width) { structureWidth = Math.max(STRUCTURE_MIN, width); }
  int structureWidth() { return structureWidth; }

  /** The column's width in an area {@code width} wide: at least 120, at most half (Ruling 12). */
  static int structureWidth(int wanted, int width) {
    return Math.max(0, Math.min(Math.max(STRUCTURE_MIN, wanted), width / 2));
  }

  @Override
  protected void layout(Composite composite, boolean flushCache) {
    var area = composite.getClientArea();
    Map<Slot, Control> s = slots(composite);
    Control slider = s.get(Slot.SLIDER);
    int sliderW = slider == null ? 0 : slider.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
    int headerH = s.get(Slot.RESULT) instanceof MergePane p ? p.headerHeight() : 0;
    if (slider != null) {
      slider.setBounds(area.x + area.width - sliderW, area.y + headerH, sliderW, Math.max(0, area.height - headerH));
    }
    int x = area.x;
    int y = area.y;
    int w = area.width - sliderW;
    int h = area.height;
    Control column = s.get(Slot.STRUCTURE);
    Control columnSash = s.get(Slot.STRUCTURE_SASH);
    if (structureShown && column != null) {
      int cw = structureWidth(structureWidth, w);
      place(column, x, y, cw, h);
      place(columnSash, x + cw, y, SASH, h);
      x += cw + SASH;
      w -= cw + SASH;
    } else {
      hide(column);
      hide(columnSash);
    }
    hide(s.get(Slot.SASH));
    switch (mode) {
      case ALL -> layoutColumns(s, x, y, w, h);
      case LEFT_RESULT -> {
        hide(s.get(Slot.RIGHT));
        hide(s.get(Slot.RIGHT_CONNECTOR));
        two(s.get(Slot.LEFT), s.get(Slot.LEFT_CONNECTOR), s.get(Slot.RESULT), x, y, w, h);
      }
      case RESULT_RIGHT -> {
        hide(s.get(Slot.LEFT));
        hide(s.get(Slot.LEFT_CONNECTOR));
        two(s.get(Slot.RESULT), s.get(Slot.RIGHT_CONNECTOR), s.get(Slot.RIGHT), x, y, w, h);
      }
      case RESULT_BELOW -> {
        hide(s.get(Slot.LEFT_CONNECTOR));
        hide(s.get(Slot.RIGHT_CONNECTOR));
        int topH = (int) Math.round((h - SASH) * ratio);
        int half = (w - SIDE_GAP) / 2;
        place(s.get(Slot.LEFT), x, y, half, topH);
        place(s.get(Slot.RIGHT), x + half + SIDE_GAP, y, w - half - SIDE_GAP, topH);
        place(s.get(Slot.SASH), x, y + topH, w, SASH);
        place(s.get(Slot.RESULT), x + BELOW_INDENT, y + topH + SASH, w - BELOW_INDENT, h - topH - SASH);
      }
    }
  }

  private void two(Control a, Control between, Control b, int x, int y, int w, int h) {
    int paneW = Math.max(0, (w - connector) / 2);
    place(a, x, y, paneW, h);
    place(between, x + paneW, y, connector, h);
    place(b, x + paneW + connector, y, w - paneW - connector, h);
  }

  private static void hide(Control c) {
    if (c != null) {
      c.setVisible(false);
    }
  }

  private void layoutColumns(Map<Slot, Control> s, int x, int y, int width, int height) {
    int paneW = Math.max(0, (width - 2 * connector) / 3);
    int rest = Math.max(0, width - 2 * connector - 3 * paneW);
    place(s.get(Slot.LEFT), x, y, paneW, height);
    place(s.get(Slot.LEFT_CONNECTOR), x + paneW, y, connector, height);
    place(s.get(Slot.RESULT), x + paneW + connector, y, paneW + rest, height);
    place(s.get(Slot.RIGHT_CONNECTOR), x + 2 * paneW + rest + connector, y, connector, height);
    place(s.get(Slot.RIGHT), x + 2 * paneW + rest + 2 * connector, y, paneW, height);
  }

  private static void place(Control c, int x, int y, int w, int h) {
    if (c != null) {
      c.setVisible(true);
      c.setBounds(x, y, w, h);
    }
  }
}
