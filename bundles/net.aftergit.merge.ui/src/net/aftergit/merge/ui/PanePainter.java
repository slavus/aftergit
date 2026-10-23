package net.aftergit.merge.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.TypedListener;

/**
 * Paints block fills, hatching, borders, 2px empty-block markers, error frames and the current block's frame on a
 * StyledText.
 */
final class PanePainter {
  private final StyledText text;
  private PaneStyle style = new PaneStyle(new LineStyle[0], java.util.List.of());

  private final LineBackgroundListener background = e -> {
    int line = textLine(e.lineOffset);
    if (line >= 0 && line < style.lines().length) {
      Color c = fillColor(style.lines()[line]);
      if (c != null) {
        e.lineBackground = c;
      }
    }
  };

  private final PaintListener borders = e -> {
    paintBorders(e.gc);
    keepFillsOverTheCurrentLine();
  };

  private static Color fillColor(LineStyle s) {
    return switch (s.fill()) {
      case STRONG -> Palette.strong(s.kind());
      case LIGHT -> Palette.light(s.kind());
      case NONE -> null;
    };
  }

  /** The current block's frame and its bar at the left edge of each pane (block-states design §2). */
  static final int CURRENT_FRAME = 2;
  static final int CURRENT_BAR = 5;
  /** Width of one stripe of the hatching of an auto-resolved block that needs review (§4); gaps are as wide. */
  static final int HATCH = 6;
  /** The error frame lies this far inside the block (and right of the current bar), within the current frame. */
  static final int ERROR_INSET = 4;

  private PaneStyle.Frame selection;

  /** A thin mark at a document offset where a text lacks words of the text it is compared with. */
  record Insertion(int offset, Color color) {}

  /** Width of an insertion mark and the space it leaves above and below within its line. */
  static final int INSERTION = 2;
  static final int INSERTION_INSET = 2;

  private java.util.List<Insertion> insertions = java.util.List.of();

  PanePainter(StyledText text) {
    this.text = text;
    // room for the current block's bar in front of the text
    text.setLeftMargin(Math.max(text.getLeftMargin(), CURRENT_BAR + 2));
    text.addLineBackgroundListener(background);
    text.addPaintListener(borders);
  }

  /**
   * JFace's current-line highlight adds its line background listener lazily, on its first paint, so it runs after
   * ours and paints over the block fill on the caret line. StyledText asks the listeners in the order they were
   * added: put ours last again, so a block keeps its colour and the current line shows only outside blocks.
   */
  private void keepFillsOverTheCurrentLine() {
    Listener[] all = text.getListeners(ST.LineGetBackground);
    if (all.length > 1 && !(all[all.length - 1] instanceof TypedListener t && t.getEventListener() == background)) {
      text.removeLineBackgroundListener(background);
      text.addLineBackgroundListener(background);
      text.redraw();
    }
  }

  /** Lines of the current block, framed (never filled); null for none. */
  void setSelection(PaneStyle.Frame frame) {
    if (!java.util.Objects.equals(frame, selection)) {
      selection = frame;
      text.redraw();
    }
  }

  /** The insertion marks to paint; painted again only when they change. */
  void setInsertions(java.util.List<Insertion> marks) {
    if (!insertions.equals(marks)) {
      insertions = java.util.List.copyOf(marks);
      text.redraw();
    }
  }

  /**
   * Moves the marks with an edit of the document, as the text moves: {@code removed} characters at {@code offset}
   * replaced by {@code inserted} ones. A mark behind the change moves, one in front of it stays, one inside the
   * removed text goes. No repaint: the edit repaints the lines it moves.
   */
  void shiftInsertions(int offset, int removed, int inserted) {
    if (insertions.isEmpty()) {
      return;
    }
    insertions = shifted(insertions, offset, removed, inserted);
  }

  static java.util.List<Insertion> shifted(java.util.List<Insertion> marks, int offset, int removed, int inserted) {
    int changeEnd = offset + removed;
    java.util.List<Insertion> out = new java.util.ArrayList<>(marks.size());
    for (Insertion i : marks) {
      if (i.offset() >= changeEnd) {
        out.add(inserted == removed ? i : new Insertion(i.offset() + inserted - removed, i.color()));
      } else if (i.offset() <= offset) {
        out.add(i);
      }
    }
    return java.util.List.copyOf(out);
  }

  java.util.List<Insertion> insertions() {
    return insertions;
  }

  PaneStyle.Frame selection() {
    return selection;
  }

  PaneStyle style() {
    return style;
  }

  void setStyle(PaneStyle newStyle) {
    setStyle(newStyle, false);
  }

  /**
   * {@code changedLinesOnly}: after an edit, which changes neither colours nor appearance, only the lines whose style
   * differs (and their neighbours, which share a border) are painted again. A redraw makes StyledText lay out each
   * line it covers again, and a long line with many syntax colours costs the UI thread a lot for that.
   */
  void setStyle(PaneStyle newStyle, boolean changedLinesOnly) {
    PaneStyle old = this.style;
    this.style = newStyle;
    if (!changedLinesOnly || old == null || old.lines().length != newStyle.lines().length
        || !old.emptyMarkers().equals(newStyle.emptyMarkers()) || !old.errorFrames().equals(newStyle.errorFrames())) {
      text.redraw();
      return;
    }
    int first = -1;
    int last = -1;
    for (int i = 0; i < newStyle.lines().length; i++) {
      if (!java.util.Objects.equals(old.lines()[i], newStyle.lines()[i])) {
        first = first < 0 ? i : first;
        last = i;
      }
    }
    if (first >= 0) {
      redrawLines(first - 1, last + 1);
    }
  }

  /** Paints lines [from, to] again, as far as they are visible. */
  private void redrawLines(int from, int to) {
    Rectangle area = text.getClientArea();
    int top = Math.max(0, text.getLinePixel(Math.max(0, from)));
    int bottom = Math.min(area.height, text.getLinePixel(Math.min(text.getLineCount(), to + 1)));
    if (bottom > top) {
      text.redraw(0, top, area.width, bottom - top, false);
    }
  }

  private int textLine(int offset) {
    return offset <= text.getCharCount() ? text.getLineAtOffset(offset) : -1;
  }

  /** First and last (exclusive) line whose borders a paint visits: the visible ones. */
  int[] borderLines() {
    int count = Math.min(style.lines().length, text.getLineCount());
    int from = Math.max(0, text.getTopIndex() - 1); // a partly visible line above the top index
    int to = Math.min(count, text.getLineIndex(Math.max(0, text.getClientArea().height - 1)) + 1);
    return new int[] {Math.min(from, to), to};
  }

  private void paintBorders(GC gc) {
    int width = text.getClientArea().width;
    LineStyle[] lines = style.lines();
    int[] range = borderLines();
    for (int i = range[0]; i < range[1]; i++) {
      LineStyle s = lines[i];
      int top = text.getLinePixel(i);
      int height = text.getLinePixel(i + 1) - top;
      Color fill = fillColor(s);
      if (fill != null) {
        // StyledText leaves the left margin in the widget background: the fill reaches the edge
        gc.setBackground(fill);
        gc.fillRectangle(0, top, text.getLeftMargin(), height);
      }
      if (s.hatched() && Appearance.hatch()) {
        paintHatch(gc, i, top, height, width);
      }
      Color c = Palette.strong(s.kind());
      if (c == null || !(s.topBorder() || s.bottomBorder())) {
        continue;
      }
      int inset = framed(i) ? Appearance.currentFrame() : 0; // the block's own borders stay inside the current frame
      gc.setForeground(c);
      gc.setLineWidth(Appearance.border());
      gc.setLineStyle(switch (s.border()) {
        case DOT -> SWT.LINE_DOT;
        case DASH -> SWT.LINE_DASH;
        case SOLID -> SWT.LINE_SOLID;
      });
      if (s.topBorder()) {
        // a wider line is centred below its y: inside the block, as the bottom border
        int y = top + inset + Appearance.border() - 1;
        gc.drawLine(0, y, width, y);
      }
      if (s.bottomBorder()) {
        int y = top + height - 1 - inset;
        gc.drawLine(0, y, width, y);
      }
    }
    gc.setLineStyle(SWT.LINE_SOLID);
    gc.setLineWidth(1);
    for (PaneStyle.Marker m : style.emptyMarkers()) {
      Color c = Palette.strong(m.kind());
      if (c == null) {
        continue;
      }
      gc.setBackground(c);
      int y = text.getLinePixel(Math.min(m.line(), text.getLineCount()));
      gc.fillRectangle(0, Math.max(0, y - 1), width, 2);
    }
    if (!style.errorFrames().isEmpty()) {
      int height = text.getClientArea().height;
      Color foreground = gc.getForeground();
      gc.setForeground(Palette.error()); // the editors' error annotation color
      gc.setLineWidth(Appearance.errorFrame());
      for (PaneStyle.Frame f : style.errorFrames()) {
        int top = text.getLinePixel(Math.min(f.start(), text.getLineCount()));
        int bottom = text.getLinePixel(Math.min(f.end(), text.getLineCount()));
        if (bottom < 0 || top > height) {
          continue; // not visible
        }
        int left = CURRENT_BAR + 2;
        gc.drawRectangle(left, top + ERROR_INSET, Math.max(1, width - left - ERROR_INSET - 1),
            Math.max(1, bottom - top - 2 * ERROR_INSET - 1));
      }
      gc.setLineWidth(1);
      gc.setForeground(foreground);
    }
    paintInsertions(gc);
    paintCurrent(gc, width);
  }

  /** Each insertion mark: a bar {@link #INSERTION} px wide in front of the character at its offset. */
  private void paintInsertions(GC gc) {
    int count = text.getCharCount();
    int height = text.getClientArea().height;
    for (Insertion i : insertions) {
      if (i.offset() < 0 || i.offset() > count || i.color() == null) {
        continue; // stale until the next refresh
      }
      int line = text.getLineAtOffset(i.offset());
      int top = text.getLinePixel(line);
      int lineHeight = text.getLinePixel(line + 1) - top;
      if (top + lineHeight < 0 || top > height) {
        continue;
      }
      int x = text.getLocationAtOffset(i.offset()).x;
      gc.setBackground(i.color());
      gc.fillRectangle(x - INSERTION / 2, top + INSERTION_INSET, INSERTION,
          Math.max(1, lineHeight - 2 * INSERTION_INSET));
    }
  }

  private boolean framed(int line) {
    return selection != null && selection.start() <= line && line < selection.end();
  }

  /** 2 px (3 px thick) frame and 5 px bar of the current block in the current colour; an empty block: a thin box at its marker. */
  private void paintCurrent(GC gc, int width) {
    if (selection == null) {
      return;
    }
    int count = text.getLineCount();
    int top = text.getLinePixel(Math.min(selection.start(), count));
    Color current = Palette.current();
    if (selection.end() == selection.start()) {
      gc.setForeground(current);
      gc.setLineStyle(SWT.LINE_SOLID);
      gc.setLineWidth(1);
      gc.drawRectangle(0, Math.max(0, top - 2), Math.max(1, width - 1), 3);
      return;
    }
    int bottom = text.getLinePixel(Math.min(selection.end(), count));
    int frame = Appearance.currentFrame();
    gc.setBackground(current);
    gc.fillRectangle(0, top, width, frame);
    gc.fillRectangle(0, bottom - frame, width, frame);
    gc.fillRectangle(width - frame, top, frame, bottom - top);
    gc.fillRectangle(0, top, CURRENT_BAR, bottom - top);
  }

  /** auto.hatch stripes over the auto.light fill, only where the line has no text: every glyph stays on the fill. */
  private void paintHatch(GC gc, int line, int top, int height, int width) {
    int[] gaps = hatchGaps(line, width);
    Region damaged = new Region(); // stay inside the area this paint repairs
    gc.getClipping(damaged);
    try {
      Rectangle bounds = damaged.getBounds();
      // the pattern belongs to the document, not the viewport: scrolled and copied pixels keep matching
      int dx = text.getHorizontalPixel();
      int first = firstStripe(top + text.getTopPixel(), height) - dx;
      gc.setBackground(Palette.autoHatch());
      for (int g = 0; g + 1 < gaps.length; g += 2) {
        Rectangle gap = new Rectangle(gaps[g], top, gaps[g + 1] - gaps[g], height).intersection(bounds);
        if (gap.isEmpty()) {
          continue;
        }
        gc.setClipping(gap);
        // only the stripes that reach into the gap
        int from = gap.x - height - HATCH;
        int x = first + Math.floorDiv(from - first, 2 * HATCH) * 2 * HATCH;
        for (; x < gap.x + gap.width; x += 2 * HATCH) {
          gc.fillPolygon(stripe(x, top, height));
        }
      }
      gc.setClipping(damaged);
    } finally {
      damaged.dispose();
    }
  }

  /**
   * x of the first stripe's lower left corner of a line at {@code top} (in document pixels), so that stripes run on
   * from one line into the next.
   */
  static int firstStripe(int top, int height) {
    int phase = Math.floorMod(-(top + height), 2 * HATCH);
    return phase - 2 * HATCH * (height / (2 * HATCH) + 1);
  }

  /** One stripe rising at 45 degrees to the right, its lower left corner at (x, top + height). */
  static int[] stripe(int x, int top, int height) {
    int bottom = top + height;
    return new int[] {x, bottom, x + HATCH, bottom, x + HATCH + height, top, x + height, top};
  }

  /** [from, to) pairs of the line without text: up to its first non-blank character, and after its end. */
  int[] hatchGaps(int line, int width) {
    String s = text.getLine(line);
    int first = 0;
    while (first < s.length() && Character.isWhitespace(s.charAt(first))) {
      first++;
    }
    if (first == s.length()) {
      return new int[] {0, width};
    }
    int offset = text.getOffsetAtLine(line);
    int before = text.getLocationAtOffset(offset + first).x;
    int after = text.getLocationAtOffset(offset + s.length()).x + 1;
    return new int[] {0, before, after, width};
  }
}
