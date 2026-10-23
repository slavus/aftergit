package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import net.aftergit.merge.core.model.BlockState;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preferences design §3 "Signs" and Ruling 12: the appearance switches as the painters draw them. */
class AppearanceTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
    MergePreferences.reset();
  }

  private MergeEditorInput twoConflicts(String project) throws Exception {
    repos.conflict("a.txt", "a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n");
    IFile file = repos.plainProject(project, "a.txt");
    return UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
  }

  @Test
  void theDefaultsAreSignsHatchANormalConnectorAndNormalFrames() {
    assertTrue(Appearance.signs());
    assertTrue(Appearance.hatch());
    assertEquals(Appearance.ConnectorWidth.NORMAL, Appearance.connector());
    assertEquals(70, Appearance.connector().pixels());
    assertEquals(Appearance.Frames.NORMAL, Appearance.frames());
    assertEquals(2, Appearance.currentFrame());
    assertEquals(1, Appearance.border());
  }

  @Test
  void aNarrowOrWideConnectorReachesAnOpenResolverAndItsTakeButtonsStillWork() throws Exception {
    MergeEditorInput input = twoConflicts("appearance-connector");
    MergeViewer v = input.viewer();
    assertEquals(70, UiDriver.paint(v, PaneKind.LEFT).getSize().x);
    Appearance.setConnector(Appearance.ConnectorWidth.NARROW);
    ConflictRepos.waitUntil(() -> UiDriver.paint(v, PaneKind.LEFT).getSize().x == 40, 10, "the narrow connector");
    UiDriver.clickConnector(v, PaneKind.LEFT, 1, true, 0);
    assertEquals(BlockState.TAKEN_LEFT, input.session().model().blocks().get(1).state());
    Appearance.setConnector(Appearance.ConnectorWidth.WIDE);
    ConflictRepos.waitUntil(() -> UiDriver.paint(v, PaneKind.RIGHT).getSize().x == 100, 10, "the wide connector");
  }

  @Test
  void withoutSignsTheOriginColumnKeepsOnlyItsBars() throws Exception {
    MergeEditorInput input = twoConflicts("appearance-signs");
    OriginColumn column = input.viewer().pane(PaneKind.RESULT).originColumn();
    assertEquals(OriginColumn.WIDTH, column.getWidth());
    Appearance.setSigns(false);
    ConflictRepos.waitUntil(() -> column.getWidth() == OriginColumn.BARS_ONLY
        && column.getControl().getSize().x == OriginColumn.BARS_ONLY, 10, "the narrow origin column");
    Image image = new Image(Display.getCurrent(), OriginColumn.WIDTH, 20);
    GC gc = new GC(image);
    try {
      gc.setBackground(Display.getCurrent().getSystemColor(SWT.COLOR_WHITE));
      gc.fillRectangle(0, 0, OriginColumn.WIDTH, 20);
      column.setGutters(List.of(new PaneStyle.Gutter(0, 0, 1, PaneStyle.Bar.AUTO, PaneStyle.Origin.LEFT,
          PaneStyle.Badge.PENDING)));
      column.paintLine(gc, 0, 0, 0, 20);
    } finally {
      gc.dispose();
    }
    ImageData d = image.getImageData();
    image.dispose();
    RGB white = new RGB(255, 255, 255);
    assertEquals(Palette.strong(LineStyle.Kind.AUTO).getRGB(), d.palette.getRGB(d.getPixel(1, 10)), "the bar stays");
    for (int x = OriginColumn.BADGE_X; x < OriginColumn.WIDTH; x++) {
      assertEquals(white, d.palette.getRGB(d.getPixel(x, 10)), "no badge or glyph at x=" + x);
    }
  }

  @Test
  void withoutHatchingAPendingAutoBlockIsPlainTeal() throws Exception {
    Shell shell = new Shell(Display.getCurrent());
    try {
      shell.setSize(300, 80);
      StyledText t = new StyledText(shell, SWT.NONE);
      t.setBounds(0, 0, 300, 80);
      t.setText("\n\n");
      PanePainter p = new PanePainter(t);
      LineStyle hatched = new LineStyle(LineStyle.Kind.AUTO, LineStyle.Fill.LIGHT, false, false,
          LineStyle.Border.DOT, true);
      p.setStyle(new PaneStyle(new LineStyle[] {hatched, hatched, LineStyle.NONE}, List.of(), List.of(), List.of()));
      shell.open();
      UiDriver.flush();
      RGB stripe = Palette.autoHatch().getRGB();
      int row = t.getLinePixel(0) + 3;
      ImageData on = Screens.capture(t, "appearance-hatch");
      boolean striped = false;
      for (int x = 0; x < 300 && !striped; x++) {
        striped = stripe.equals(Screens.pixel(on, x, row));
      }
      assertTrue(striped, "with hatching the row crosses a stripe");
      Appearance.setHatch(false);
      UiDriver.flush();
      t.redraw();
      UiDriver.flush();
      ImageData d = Screens.capture(t, "appearance-no-hatch");
      for (int x = 0; x < 300; x++) {
        assertNotEquals(stripe, Screens.pixel(d, x, row), "no stripe at x=" + x);
      }
    } finally {
      shell.dispose();
    }
  }

  @Test
  void thickFramesDrawTheCurrentFrameThreePixelsHigh() throws Exception {
    Shell shell = new Shell(Display.getCurrent());
    try {
      shell.setSize(300, 80);
      StyledText t = new StyledText(shell, SWT.NONE);
      t.setBounds(0, 0, 300, 80);
      t.setText("x\ny\nz\n");
      PanePainter p = new PanePainter(t);
      p.setStyle(new PaneStyle(new LineStyle[] {LineStyle.NONE, LineStyle.NONE, LineStyle.NONE}, List.of(), List.of(),
          List.of()));
      p.setSelection(new PaneStyle.Frame(0, 2));
      shell.open();
      RGB current = Palette.current().getRGB();
      UiDriver.flush();
      ImageData normal = Screens.capture(t, "appearance-frame-normal");
      assertNotEquals(current, normal.palette.getRGB(normal.getPixel(150, 2)), "2 px: row 2 is inside");
      Appearance.setFrames(Appearance.Frames.THICK);
      t.redraw();
      UiDriver.flush();
      ImageData thick = Screens.capture(t, "appearance-frame-thick");
      assertEquals(current, thick.palette.getRGB(thick.getPixel(150, 2)), "3 px: row 2 is frame");
    } finally {
      shell.dispose();
    }
  }

  @Test
  void thickFramesDrawABlocksBordersTwoPixelsHighInsideTheBlock() throws Exception {
    Shell shell = new Shell(Display.getCurrent());
    try {
      shell.setSize(300, 100);
      StyledText t = new StyledText(shell, SWT.NONE);
      t.setBounds(0, 0, 300, 100);
      t.setText("x\ny\nz\n");
      PanePainter p = new PanePainter(t);
      LineStyle block = new LineStyle(LineStyle.Kind.CONFLICT, LineStyle.Fill.NONE, true, true,
          LineStyle.Border.SOLID, false);
      p.setStyle(new PaneStyle(new LineStyle[] {LineStyle.NONE, block, LineStyle.NONE, LineStyle.NONE}, List.of(),
          List.of(), List.of()));
      shell.open();
      Appearance.setFrames(Appearance.Frames.THICK);
      UiDriver.flush();
      t.redraw();
      UiDriver.flush();
      ImageData d = Screens.capture(t, "appearance-border-thick");
      RGB strong = Palette.strong(LineStyle.Kind.CONFLICT).getRGB();
      int top = t.getLinePixel(1);
      int bottom = t.getLinePixel(2) - 1;
      int x = 250; // right of the text
      assertNotEquals(strong, Screens.pixel(d, x, top - 1), "the line above keeps its background");
      assertEquals(strong, Screens.pixel(d, x, top), "top border, first row");
      assertEquals(strong, Screens.pixel(d, x, top + 1), "top border, second row");
      assertNotEquals(strong, Screens.pixel(d, x, top + 2), "inside the block");
      assertNotEquals(strong, Screens.pixel(d, x, bottom - 2), "inside the block");
      assertEquals(strong, Screens.pixel(d, x, bottom - 1), "bottom border, first row");
      assertEquals(strong, Screens.pixel(d, x, bottom), "bottom border, second row");
      assertNotEquals(strong, Screens.pixel(d, x, bottom + 1), "the line below keeps its background");
    } finally {
      shell.dispose();
    }
  }

  @Test
  void thickFramesDrawTheCurrentEdgesOfABandOnTheSameThreeRowsAsThePanes() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      shell.setLayout(new org.eclipse.swt.layout.FillLayout());
      shell.setSize(1200, 800);
      MergeViewer v = new MergeViewer(shell);
      net.aftergit.merge.core.model.MergeModel m = net.aftergit.merge.core.model.HybridInitializer.initialize(
          new net.aftergit.merge.core.model.MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
          net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT,
          net.aftergit.merge.core.resolve.ResolverPipeline.none()).model();
      v.setInput(new MergeSession(m, new net.aftergit.merge.git.Labels("ours", "theirs"), "\n", true));
      shell.open();
      shell.layout(true, true);
      Appearance.setFrames(Appearance.Frames.THICK);
      v.selectBlock(0);
      UiDriver.flush();
      RGB current = Palette.current().getRGB();
      for (PaneKind side : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
        ConnectorCanvas c = v.connector(side);
        c.redraw();
        c.update();
        UiDriver.flush();
        ImageData d = Screens.capture(c, "appearance-connector-thick-" + side.name().toLowerCase(java.util.Locale.ROOT));
        ConnectorGeometry.Band b = c.bands().get(0);
        int x = 4; // the straight part in the button column
        for (int y = b.lt(); y < b.lt() + 3; y++) {
          assertEquals(current, Screens.pixel(d, x, y), side + ": top edge at row " + y);
        }
        assertNotEquals(current, Screens.pixel(d, x, b.lt() - 1), side + ": nothing above the top edge");
        assertNotEquals(current, Screens.pixel(d, x, b.lt() + 3), side + ": nothing below the top edge");
        for (int y = b.lb() - 3; y < b.lb(); y++) {
          assertEquals(current, Screens.pixel(d, x, y), side + ": bottom edge at row " + y);
        }
        assertNotEquals(current, Screens.pixel(d, x, b.lb()), side + ": nothing below the bottom edge");
        assertNotEquals(current, Screens.pixel(d, x, b.lb() - 4), side + ": nothing above the bottom edge");
      }
    } finally {
      shell.dispose();
    }
  }
}
