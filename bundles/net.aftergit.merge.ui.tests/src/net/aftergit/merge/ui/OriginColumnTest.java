package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** block-states design §1, §4: bars, origins and the "A" badge live in the result's ruler, not in the text. */
class OriginColumnTest {
  Shell shell;
  SourceViewer viewer;
  OriginColumn column;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent());
    shell.setLayout(new FillLayout());
    shell.setSize(400, 200);
    CompositeRuler ruler = new CompositeRuler();
    ruler.addDecorator(0, new LineNumberRulerColumn());
    column = new OriginColumn();
    ruler.addDecorator(1, column);
    viewer = new SourceViewer(shell, ruler, SWT.MULTI);
    viewer.setDocument(new Document("one\ntwo\nthree\nfour\n"));
    shell.open();
    UiDriver.flush();
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  private int middle(int line) {
    StyledText t = viewer.getTextWidget();
    return t.getLinePixel(line) + t.getLineHeight() / 2;
  }

  private int count(ImageData shot, RGB rgb, int x0, int x1, int line) {
    StyledText t = viewer.getTextWidget();
    int n = 0;
    for (int y = t.getLinePixel(line); y < t.getLinePixel(line + 1); y++) {
      for (int x = x0; x < x1; x++) {
        if (Screens.pixel(shot, x, y).equals(rgb)) {
          n++;
        }
      }
    }
    return n;
  }

  @Test
  void theGutterOfALineIsTheBlockCoveringItOrAnEmptyBlockOnItsTop() {
    var full = new PaneStyle.Gutter(0, 1, 3, PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT, PaneStyle.Badge.NONE);
    var empty = new PaneStyle.Gutter(1, 3, 3, PaneStyle.Bar.TAKEN, PaneStyle.Origin.RIGHT, PaneStyle.Badge.NONE);
    List<PaneStyle.Gutter> g = List.of(full, empty);
    assertNull(OriginColumn.at(g, 0));
    assertEquals(full, OriginColumn.at(g, 1));
    assertEquals(full, OriginColumn.at(g, 2));
    assertEquals(empty, OriginColumn.at(g, 3), "an empty block shows on the line under its marker");
  }

  @Test
  void theBarTheBadgeAndTheGlyphArePaintedInTheirColumns() throws Exception {
    assertEquals(OriginColumn.WIDTH, column.getWidth());
    column.setGutters(List.of(
        new PaneStyle.Gutter(0, 1, 3, PaneStyle.Bar.AUTO, PaneStyle.Origin.BOTH, PaneStyle.Badge.PENDING)));
    ImageData shot = Screens.capture(column.getControl(), "origin-column-auto");
    RGB auto = Palette.strong(LineStyle.Kind.AUTO).getRGB();
    RGB background = column.getControl().getBackground().getRGB();
    assertEquals(auto, Screens.pixel(shot, 1, middle(1)), "the bar on the first line");
    assertEquals(auto, Screens.pixel(shot, 1, middle(2)), "the bar on the second line");
    assertEquals(background, Screens.pixel(shot, 1, middle(0)), "no bar outside the block");
    assertEquals(viewer.getTextWidget().getForeground().getRGB(),
        Screens.pixel(shot, OriginColumn.GLYPH_X + OriginColumn.GLYPH_WIDTH / 2, middle(1)), "the diamond");
    assertTrue(count(shot, auto, OriginColumn.BADGE_X, OriginColumn.BADGE_X + OriginColumn.BADGE_WIDTH, 1) > 40,
        "the filled badge");
    assertEquals(0, count(shot, auto, OriginColumn.BADGE_X, OriginColumn.BADGE_X + OriginColumn.BADGE_WIDTH, 2),
        "badge and glyph only on the first line");
  }

  @Test
  void aReviewedBadgeIsOnlyOutlined() throws Exception {
    column.setGutters(List.of(
        new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.AUTO, PaneStyle.Origin.LEFT, PaneStyle.Badge.PENDING)));
    RGB auto = Palette.strong(LineStyle.Kind.AUTO).getRGB();
    int filled = count(Screens.capture(column.getControl(), "origin-column-pending"), auto,
        OriginColumn.BADGE_X, OriginColumn.BADGE_X + OriginColumn.BADGE_WIDTH, 1);
    column.setGutters(List.of(
        new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.AUTO, PaneStyle.Origin.LEFT, PaneStyle.Badge.REVIEWED)));
    int outlined = count(Screens.capture(column.getControl(), "origin-column-reviewed"), auto,
        OriginColumn.BADGE_X, OriginColumn.BADGE_X + OriginColumn.BADGE_WIDTH, 1);
    assertTrue(outlined > 0, "the reviewed badge stays");
    assertTrue(filled > 2 * outlined, "filled " + filled + " vs outlined " + outlined);
  }

  @Test
  void eachBarHasItsColour() {
    assertEquals(Palette.takenStrong(), OriginColumn.barColor(PaneStyle.Bar.TAKEN));
    assertEquals(Palette.editedStrong(), OriginColumn.barColor(PaneStyle.Bar.EDITED));
    assertEquals(Palette.strong(LineStyle.Kind.AUTO), OriginColumn.barColor(PaneStyle.Bar.AUTO));
    assertNull(OriginColumn.barColor(PaneStyle.Bar.NONE));
  }

  @Test
  void aTakenBlockHasItsBarAndGlyphInTheTakenColour() throws Exception {
    column.setGutters(List.of(
        new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.TAKEN, PaneStyle.Origin.RIGHT, PaneStyle.Badge.NONE)));
    ImageData shot = Screens.capture(column.getControl(), "origin-column-taken");
    RGB taken = Palette.takenStrong().getRGB();
    assertEquals(taken, Screens.pixel(shot, 1, middle(1)), "the bar");
    assertEquals(taken, Screens.pixel(shot, OriginColumn.GLYPH_X + 2, middle(1)), "the base of the right triangle");
    assertEquals(0, count(shot, Palette.strong(LineStyle.Kind.AUTO).getRGB(), OriginColumn.BADGE_X,
        OriginColumn.BADGE_X + OriginColumn.BADGE_WIDTH, 1), "no badge on a take");
  }

  @Test
  void anEmptyBlockShowsItsGlyphButNoBar() throws Exception {
    column.setGutters(List.of(
        new PaneStyle.Gutter(0, 2, 2, PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT, PaneStyle.Badge.NONE)));
    ImageData shot = Screens.capture(column.getControl(), "origin-column-empty");
    RGB taken = Palette.takenStrong().getRGB();
    assertNotEquals(taken, Screens.pixel(shot, 1, middle(2)), "no bar");
    assertTrue(count(shot, taken, OriginColumn.GLYPH_X, OriginColumn.GLYPH_X + OriginColumn.GLYPH_WIDTH, 2) > 0,
        "the glyph on the line under the marker");
  }
}
