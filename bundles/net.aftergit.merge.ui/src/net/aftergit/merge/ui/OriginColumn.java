package net.aftergit.merge.ui;

import java.util.List;
import org.eclipse.jface.text.source.AbstractRulerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

/**
 * The result's ruler column next to the line numbers (block-states design §1, §4): the bar of a resolved conflict,
 * where its result came from (◀ ours, ▶ theirs, ◆ both, ✎ typed by hand) and the "A" of an automatic resolution. In
 * the ruler and not in the text, so copying never picks them up; the glyphs are shapes, so no font has to have them.
 */
final class OriginColumn extends AbstractRulerColumn {
  static final int WIDTH = 28;
  static final int BAR = 4;
  static final int BADGE_X = 6;
  static final int BADGE_WIDTH = 10;
  static final int GLYPH_X = 18;
  static final int GLYPH_WIDTH = 8;

  private List<PaneStyle.Gutter> gutters = List.of();
  /** The font of the badge's "A", derived from the ruler's; disposed with the column's control. */
  private Font badgeFont;
  private Font badgeFontBase;
  private int badgeFontHeight;
  /** The control whose disposal disposes the badge font. */
  private Control listening;

  /** Width without the signs (preferences design §3, Ruling 15): the bar and a gap. */
  static final int BARS_ONLY = BAR + 2;

  OriginColumn() {
    setWidth(Appearance.signs() ? WIDTH : BARS_ONLY);
  }

  /** Follows "Always show state signs": the full column or the bars alone. */
  void updateWidth() {
    int w = Appearance.signs() ? WIDTH : BARS_ONLY;
    if (getWidth() != w) {
      setWidth(w);
      if (getParentRuler() != null) {
        getParentRuler().relayout();
      }
    }
  }

  void setGutters(List<PaneStyle.Gutter> newGutters) {
    if (newGutters.equals(gutters)) {
      return;
    }
    gutters = List.copyOf(newGutters);
    Control c = getControl();
    if (c != null && !c.isDisposed()) {
      redraw();
    }
  }

  List<PaneStyle.Gutter> gutters() {
    return gutters;
  }

  /**
   * The gutter painted on document line {@code line}: the block covering it, or an empty block on its top. An empty
   * block at the very end of the document has no line under it and shows no glyph.
   */
  static PaneStyle.Gutter at(List<PaneStyle.Gutter> gutters, int line) {
    PaneStyle.Gutter empty = null;
    for (PaneStyle.Gutter g : gutters) {
      if (g.start() <= line && line < g.end()) {
        return g;
      }
      if (empty == null && g.start() == g.end() && g.start() == line) {
        empty = g;
      }
    }
    return empty;
  }

  static Color barColor(PaneStyle.Bar bar) {
    return switch (bar) {
      case TAKEN -> Palette.takenStrong();
      case EDITED -> Palette.editedStrong();
      case AUTO -> Palette.strong(LineStyle.Kind.AUTO);
      case NONE -> null;
    };
  }

  @Override
  protected void paintLine(GC gc, int modelLine, int widgetLine, int linePixel, int lineHeight) {
    PaneStyle.Gutter g = at(gutters, modelLine);
    if (g == null) {
      return;
    }
    Color bar = barColor(g.bar());
    if (bar != null && g.start() < g.end()) {
      gc.setBackground(bar);
      gc.fillRectangle(0, linePixel, BAR, lineHeight);
    }
    if (modelLine != g.start()) {
      return; // badge and glyph only on the block's first line
    }
    if (!Appearance.signs()) {
      return; // the bar alone: no badge, no glyph
    }
    gc.setAntialias(SWT.ON);
    paintBadge(gc, g.badge(), linePixel, lineHeight);
    paintGlyph(gc, g, linePixel + lineHeight / 2);
  }

  private void paintBadge(GC gc, PaneStyle.Badge badge, int top, int height) {
    if (badge == PaneStyle.Badge.NONE) {
      return;
    }
    Color auto = Palette.strong(LineStyle.Kind.AUTO);
    int h = Math.max(6, Math.min(height - 2, 12));
    int y = top + (height - h) / 2;
    if (badge == PaneStyle.Badge.PENDING) {
      gc.setBackground(auto);
      gc.fillRoundRectangle(BADGE_X, y, BADGE_WIDTH, h, 4, 4);
      gc.setForeground(getControl().getBackground()); // the letter cut out of the filled badge
    } else {
      gc.setForeground(auto);
      gc.drawRoundRectangle(BADGE_X, y, BADGE_WIDTH - 1, h - 1, 4, 4);
    }
    Font font = gc.getFont();
    gc.setFont(badgeFont(gc, font, h));
    Point size = gc.textExtent("A");
    gc.drawString("A", BADGE_X + (BADGE_WIDTH - size.x + 1) / 2, y + (h - size.y + 1) / 2, true);
    gc.setFont(font);
  }

  /** The ruler's font, small enough for its "A" to sit inside a badge {@code h} pixels high. */
  private Font badgeFont(GC gc, Font rulerFont, int h) {
    if (badgeFont != null && badgeFontBase == rulerFont && badgeFontHeight == h) {
      return badgeFont;
    }
    if (listening != getControl()) {
      listening = getControl();
      listening.addDisposeListener(e -> disposeBadgeFont());
    }
    disposeBadgeFont();
    // scale by the ruler font's own pixel size, so display scaling and reported DPI do not matter
    var metrics = gc.getFontMetrics();
    float em = Math.max(1, metrics.getAscent() + metrics.getDescent());
    FontData[] data = rulerFont.getFontData();
    for (FontData d : data) {
      d.setHeight(Math.max(4, Math.min(d.getHeight(), Math.round(d.getHeight() * h * 0.75f / em))));
    }
    badgeFont = new Font(getControl().getDisplay(), data);
    badgeFontBase = rulerFont;
    badgeFontHeight = h;
    return badgeFont;
  }

  private void disposeBadgeFont() {
    if (badgeFont != null && !badgeFont.isDisposed()) {
      badgeFont.dispose();
    }
    badgeFont = null;
  }

  /** A take shows its glyph in the taken colour, as its bar; others in the colour of the result's text. */
  private void paintGlyph(GC gc, PaneStyle.Gutter g, int cy) {
    PaneStyle.Origin origin = g.origin();
    Color fg = g.bar() == PaneStyle.Bar.TAKEN ? Palette.takenStrong()
        : getParentRuler().getTextViewer().getTextWidget().getForeground();
    gc.setBackground(fg);
    gc.setForeground(fg);
    int x = GLYPH_X;
    int w = GLYPH_WIDTH;
    int r = 4;
    switch (origin) {
      case LEFT -> gc.fillPolygon(new int[] {x + w, cy - r, x + w, cy + r, x, cy});
      case RIGHT -> gc.fillPolygon(new int[] {x, cy - r, x, cy + r, x + w, cy});
      case BOTH -> gc.fillPolygon(new int[] {x + w / 2, cy - r, x + w, cy, x + w / 2, cy + r, x, cy});
      case EDITED -> {
        gc.setLineWidth(2);
        gc.drawLine(x + 2, cy + r - 2, x + w, cy - r); // the pencil's body
        gc.setLineWidth(1);
        gc.fillPolygon(new int[] {x, cy + r, x + 3, cy + r, x, cy + r - 3}); // its tip
      }
      case NONE -> {
        // a bar or badge without an origin (AI text that is new)
      }
    }
  }
}
