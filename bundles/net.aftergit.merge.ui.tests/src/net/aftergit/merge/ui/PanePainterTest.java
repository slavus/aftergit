package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanePainterTest {
  Shell shell;
  StyledText text;
  PanePainter painter;
  Color blue;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent());
    shell.setLayout(new FillLayout());
    shell.setSize(300, 200);
    text = new StyledText(shell, SWT.NONE);
    text.setText("one\ntwo\nthree\n");
    painter = new PanePainter(text);
    LineStyle[] lines = new LineStyle[4];
    Arrays.fill(lines, LineStyle.NONE);
    lines[1] = new LineStyle(LineStyle.Kind.CONFLICT, LineStyle.Fill.LIGHT, false, false, false);
    painter.setStyle(new PaneStyle(lines, List.of()));
    blue = Display.getCurrent().getSystemColor(SWT.COLOR_BLUE);
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  private int middle(int line) {
    return text.getLinePixel(line) + text.getLineHeight() / 2;
  }

  @Test
  void blockFillsWinOverALineBackgroundListenerAddedLater() throws Exception {
    // the current-line highlight of JFace registers its listener lazily, after ours
    text.addLineBackgroundListener(e -> e.lineBackground = blue);
    shell.open();
    UiDriver.flush();
    var shot = Screens.capture(text, "pane-painter-current-line");
    RGB light = Palette.light(LineStyle.Kind.CONFLICT).getRGB();
    int x = text.getClientArea().width - 3;
    assertEquals(light, Screens.pixel(shot, x, middle(1)), "the block keeps its colour");
    assertEquals(light, Screens.pixel(shot, 1, middle(1)), "in the left margin too");
    assertEquals(blue.getRGB(), Screens.pixel(shot, x, middle(0)), "outside blocks the other listener shows");
  }

  @Test
  void theCurrentBlockIsFramedTwoPixelsWithABarInTheCurrentColour() throws Exception {
    shell.open();
    painter.setSelection(new PaneStyle.Frame(1, 2));
    var shot = Screens.capture(text, "pane-painter-current");
    RGB current = Palette.current().getRGB();
    int x = text.getClientArea().width / 2;
    int top = text.getLinePixel(1);
    int bottom = text.getLinePixel(2);
    assertEquals(current, Screens.pixel(shot, x, top), "top edge");
    assertEquals(current, Screens.pixel(shot, x, top + 1), "2 px");
    assertEquals(current, Screens.pixel(shot, x, bottom - 1), "bottom edge");
    assertEquals(current, Screens.pixel(shot, x, bottom - 2), "2 px");
    assertEquals(current, Screens.pixel(shot, PanePainter.CURRENT_BAR - 1, middle(1)), "the 5 px bar");
    assertNotEquals(current, Screens.pixel(shot, PanePainter.CURRENT_BAR, middle(1)), "not wider than 5 px");
    assertNotEquals(current, Screens.pixel(shot, x, top + PanePainter.CURRENT_FRAME), "not thicker than 2 px");
    assertNotEquals(current, Screens.pixel(shot, x, bottom - PanePainter.CURRENT_FRAME - 1), "not thicker below");
    assertEquals(current, Screens.pixel(shot, text.getClientArea().width - 1, middle(1)), "right edge");
    assertEquals(Palette.light(LineStyle.Kind.CONFLICT).getRGB(),
        Screens.pixel(shot, text.getClientArea().width - 3, middle(1)), "inside: the fill, not a selection fill");
  }

  @Test
  void anEmptyCurrentBlockGetsAThinBoxInTheCurrentColour() throws Exception {
    shell.open();
    painter.setSelection(new PaneStyle.Frame(2, 2));
    var shot = Screens.capture(text, "pane-painter-current-empty");
    int x = text.getClientArea().width / 2;
    assertEquals(Palette.current().getRGB(), Screens.pixel(shot, x, text.getLinePixel(2) - 2), "the box top");
  }

  @Test
  void aHatchedLineHasStripesOnlyWhereThereIsNoText() throws Exception {
    LineStyle[] lines = new LineStyle[4];
    java.util.Arrays.fill(lines, LineStyle.NONE);
    lines[1] = new LineStyle(LineStyle.Kind.AUTO, LineStyle.Fill.LIGHT, false, false, LineStyle.Border.SOLID, true);
    painter.setStyle(new PaneStyle(lines, List.of()));
    shell.open();
    var shot = Screens.capture(text, "pane-painter-hatch");
    RGB hatch = Palette.autoHatch().getRGB();
    RGB light = Palette.light(LineStyle.Kind.AUTO).getRGB();
    int width = text.getClientArea().width;
    java.util.Set<RGB> after = new java.util.HashSet<>();
    for (int x = width - 40; x < width; x++) {
      after.add(Screens.pixel(shot, x, middle(1)));
    }
    assertTrue(after.contains(hatch) && after.contains(light), "stripes right of the text: " + after);
    int[] gaps = painter.hatchGaps(1, width);
    for (int x = gaps[1]; x < gaps[2]; x++) {
      for (int y = text.getLinePixel(1); y < text.getLinePixel(2); y++) {
        assertNotEquals(hatch, Screens.pixel(shot, x, y), "no stripe behind the text at " + x + "," + y);
      }
    }
  }

  @Test
  void stripesContinueFromOneLineIntoTheNext() {
    int h = 17;
    int[] upper = PanePainter.stripe(PanePainter.firstStripe(0, h), 0, h);
    int[] lower = PanePainter.stripe(PanePainter.firstStripe(h, h), h, h);
    // the upper line's stripes end at its bottom where some stripe of the lower line begins at its top
    assertEquals(0, Math.floorMod(upper[0] - (lower[6]), 2 * PanePainter.HATCH));
  }

  @Test
  void aDroppedBlockHasDashedTopAndBottomBorders() throws Exception {
    LineStyle[] lines = new LineStyle[4];
    java.util.Arrays.fill(lines, LineStyle.NONE);
    lines[1] = new LineStyle(LineStyle.Kind.DROPPED, LineStyle.Fill.LIGHT, true, true, LineStyle.Border.DASH, false);
    painter.setStyle(new PaneStyle(lines, List.of()));
    shell.open();
    var shot = Screens.capture(text, "pane-painter-dropped");
    RGB dash = Palette.strong(LineStyle.Kind.DROPPED).getRGB();
    int on = 0;
    int off = 0;
    for (int x = 10; x < text.getClientArea().width - 10; x++) {
      if (Screens.pixel(shot, x, text.getLinePixel(1)).equals(dash)) {
        on++;
      } else {
        off++;
      }
    }
    assertTrue(on > 0 && off > 0, "dashes: " + on + " on, " + off + " off");
  }

  @Test
  void theErrorFrameStaysInsideTheCurrentFrame() throws Exception {
    LineStyle[] lines = new LineStyle[4];
    java.util.Arrays.fill(lines, LineStyle.NONE);
    lines[1] = new LineStyle(LineStyle.Kind.AUTO, LineStyle.Fill.LIGHT, false, false, false);
    painter.setStyle(new PaneStyle(lines, List.of(), List.of(new PaneStyle.Frame(1, 2))));
    painter.setSelection(new PaneStyle.Frame(1, 2));
    shell.open();
    var shot = Screens.capture(text, "pane-painter-error-in-current");
    int x = text.getClientArea().width / 2;
    assertEquals(Palette.current().getRGB(), Screens.pixel(shot, x, text.getLinePixel(1)));
    assertEquals(Palette.error().getRGB(), Screens.pixel(shot, x, text.getLinePixel(1) + PanePainter.ERROR_INSET),
        "the error frame inside");
    RGB error = Palette.error().getRGB();
    int left = PanePainter.CURRENT_BAR + 2;
    int right = text.getClientArea().width - PanePainter.ERROR_INSET - 1;
    int y = middle(1);
    assertTrue(java.util.stream.IntStream.rangeClosed(left - 1, left + 1)
        .anyMatch(i -> Screens.pixel(shot, i, y).equals(error)), "the error frame's left edge behind the bar");
    assertTrue(java.util.stream.IntStream.rangeClosed(right - 1, right + 1)
        .anyMatch(i -> Screens.pixel(shot, i, y).equals(error)), "the error frame's right edge 4 px inside");
    assertNotEquals(error, Screens.pixel(shot, PanePainter.CURRENT_BAR, y), "the frames do not touch on the left");
    assertNotEquals(error, Screens.pixel(shot, x, text.getLinePixel(1) + PanePainter.CURRENT_FRAME),
        "the frames do not touch at the top");
  }

  @Test
  void theStripesStayWithTheDocumentWhenThePaneScrolls() throws Exception {
    Shell own = new Shell(Display.getCurrent());
    try {
      own.setLayout(new FillLayout());
      own.setSize(300, 200);
      StyledText t = new StyledText(own, SWT.H_SCROLL | SWT.V_SCROLL);
      StringBuilder doc = new StringBuilder("x".repeat(200)); // wide enough to scroll sideways
      for (int i = 0; i < 40; i++) {
        doc.append('\n');
      }
      t.setText(doc.toString());
      PanePainter p = new PanePainter(t);
      LineStyle[] lines = new LineStyle[t.getLineCount()];
      java.util.Arrays.fill(lines, new LineStyle(LineStyle.Kind.AUTO, LineStyle.Fill.LIGHT, false, false,
          LineStyle.Border.DOT, true));
      p.setStyle(new PaneStyle(lines, List.of()));
      own.open();
      int top = t.getLinePixel(3); // empty lines: stripes everywhere
      var before = Screens.capture(t, "pane-painter-hatch-before-scroll");
      int dy = 5;
      int dx = 4; // dx + dy not a multiple of the 12 px pattern: a viewport-bound phase would show
      t.setTopPixel(dy);
      t.setHorizontalPixel(dx);
      t.redraw();
      UiDriver.flush();
      assertEquals(dy, t.getTopPixel());
      assertEquals(dx, t.getHorizontalPixel());
      var after = Screens.capture(t, "pane-painter-hatch-after-scroll");
      RGB hatch = Palette.autoHatch().getRGB();
      int stripes = 0;
      for (int y = top; y < top + 30; y++) {
        for (int x = 60; x < 120; x++) {
          RGB was = Screens.pixel(before, x, y);
          assertEquals(was, Screens.pixel(after, x - dx, y - dy), "the same document point at " + x + "," + y);
          stripes += was.equals(hatch) ? 1 : 0;
        }
      }
      assertTrue(stripes > 0, "the area is hatched");
    } finally {
      own.dispose();
    }
  }

  @Test
  void afterAnEditOnlyTheLinesWhoseStyleChangedAreLaidOutAgain() throws Exception {
    shell.setSize(300, 600);
    text.setText("l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n");
    LineStyle[] lines = new LineStyle[12];
    Arrays.fill(lines, LineStyle.NONE);
    painter.setStyle(new PaneStyle(lines, List.of()));
    shell.open();
    UiDriver.flush();
    java.util.Set<Integer> laidOut = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // StyledText asks for the styles of a line whenever it lays the line out
    text.addLineStyleListener(e -> laidOut.add(text.getLineAtOffset(e.lineOffset)));
    LineStyle[] changed = lines.clone();
    changed[5] = new LineStyle(LineStyle.Kind.CONFLICT, LineStyle.Fill.LIGHT, false, false, false);
    painter.setStyle(new PaneStyle(changed, List.of()), true);
    RGB light = Palette.light(LineStyle.Kind.CONFLICT).getRGB();
    int x = text.getClientArea().width - 3;
    ConflictRepos.waitUntil(() -> light.equals(shownAt(x, middle(5))), 10, "the changed line painted");
    UiDriver.flush();
    assertTrue(laidOut.contains(5), "the changed line: " + laidOut);
    // its neighbours share a border; StyledText also takes the line at the bottom edge of the area it redraws
    assertTrue(java.util.Set.of(4, 5, 6, 7).containsAll(laidOut), "only it and the lines next to it: " + laidOut);

    laidOut.clear();
    LineStyle[] again = changed.clone();
    again[5] = LineStyle.NONE;
    painter.setStyle(new PaneStyle(again, List.of()));
    ConflictRepos.waitUntil(() -> laidOut.contains(9), 10, "a full redraw lays out every visible line");
  }

  private RGB shownAt(int x, int y) {
    try {
      return Screens.pixel(Screens.capture(text, "pane-painter-edit"), x, y);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void insertionMarksMoveWithAnEdit() {
    Color c = Display.getCurrent().getSystemColor(SWT.COLOR_RED);
    List<PanePainter.Insertion> marks = List.of(new PanePainter.Insertion(2, c), new PanePainter.Insertion(5, c),
        new PanePainter.Insertion(9, c));
    assertEquals(List.of(new PanePainter.Insertion(2, c), new PanePainter.Insertion(7, c),
        new PanePainter.Insertion(11, c)), PanePainter.shifted(marks, 5, 0, 2), "typed at a mark: it moves on");
    assertEquals(List.of(new PanePainter.Insertion(2, c), new PanePainter.Insertion(6, c)),
        PanePainter.shifted(marks, 4, 3, 0), "the mark inside the removed text goes");
  }
}
