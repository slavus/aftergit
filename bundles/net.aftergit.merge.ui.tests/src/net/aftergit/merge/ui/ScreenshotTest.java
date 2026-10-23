package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec §10.7: screenshots for docs/visual-checklist.md and the checks that need no human eye. */
class ScreenshotTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().setBounds(0, 0, 1600, 1100);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
    MergePreferences.reset();
  }

  private MergeEditorInput openCalc() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject("calc-shots", "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    IWorkbenchPage page = ConflictRepos.page();
    page.setPartState(page.getActivePartReference(), IWorkbenchPage.STATE_MAXIMIZED);
    UiDriver.activate(input.viewer().getControl());
    return input;
  }

  private static int firstOpenConflict(MergeEditorInput input) {
    for (var b : input.session().model().blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        return b.index();
      }
    }
    throw new AssertionError("Calc keeps one open conflict");
  }

  private static Optional<ConnectorGeometry.Band> band(ConnectorCanvas c, int block) {
    c.redraw();
    c.update();
    UiDriver.flush();
    return c.bands().stream().filter(b -> b.block() == block).findFirst();
  }

  /** Middle of the band at the connector's horizontal middle (the S-curves pass the mean of their ends there). */
  private static int midY(ConnectorGeometry.Band b) {
    return ((b.lt() + b.rt()) / 2 + (b.lb() + b.rb()) / 2) / 2;
  }

  @Test
  void geometryFollowsSmartGit() throws Exception {
    MergeViewer v = openCalc().viewer();
    assertEquals(MergeLayout.CONNECTOR, v.connector(PaneKind.LEFT).getSize().x);
    assertEquals(MergeLayout.CONNECTOR, v.connector(PaneKind.RIGHT).getSize().x);
    for (PaneKind k : PaneKind.values()) {
      assertEquals(StripeGeometry.WIDTH, v.pane(k).stripe().getSize().x, k + " stripe");
      Rectangle pane = v.pane(k).getBounds();
      Rectangle stripe = v.pane(k).getDisplay().map(v.pane(k).stripe().getParent(), v.pane(k),
          v.pane(k).stripe().getBounds());
      if (k == PaneKind.LEFT) {
        assertTrue(stripe.x <= 4, "left pane: stripe at the outer left edge, x=" + stripe.x);
      } else {
        assertTrue(pane.width - (stripe.x + stripe.width) <= 4, k + ": stripe at the outer right edge");
      }
    }
    int l = v.pane(PaneKind.LEFT).getSize().x;
    int r = v.pane(PaneKind.RIGHT).getSize().x;
    int res = v.pane(PaneKind.RESULT).getSize().x;
    assertTrue(Math.abs(l - r) <= 1, "equal side panes: " + l + " / " + r);
    assertTrue(res - l >= 0 && res - l <= 2, "the result takes only the rounding rest: " + res + " / " + l);
    MergePane left = v.pane(PaneKind.LEFT);
    int header = left.headerHeight() - ((GridLayout) left.getLayout()).verticalSpacing;
    assertTrue(header >= 20 && header <= 34, "header height " + header + " (SmartGit: 28)");
    assertTrue(Screens.painted(Screens.capture(v.getControl(), "calc-all-light")));
  }

  @Test
  void bandsFollowTheOriginAndOnlyOpenConflictsAndIgnoredBlocksAreOutlined() throws Exception {
    MergeEditorInput input = openCalc();
    MergeViewer v = input.viewer();
    int block = firstOpenConflict(input);
    v.selectBlock(block);
    ConnectorCanvas c = v.connector(PaneKind.LEFT);
    ConnectorGeometry.Band open = band(c, block).orElseThrow();
    int x = c.getClientArea().width / 2;
    assertEquals(Palette.light(LineStyle.Kind.CONFLICT).getRGB(),
        Screens.pixel(Screens.capture(c, "calc-left-connector-light"), x, midY(open)), "open conflict: conflict light");
    UiDriver.click(v, Messages.takeRight);
    MergeBlock taken = input.session().model().blocks().get(block);
    assertEquals(BlockState.TAKEN_RIGHT, taken.state());
    v.pane(PaneKind.RESULT).text().setCaretOffset(0); // the frame of the current block stays off the band
    ConnectorGeometry.Band dropped = band(c, block).orElseThrow();
    var droppedShot = Screens.capture(c, "calc-left-connector-after-take-right");
    assertEquals(c.getBackground().getRGB(), Screens.pixel(droppedShot, x, midY(dropped)),
        "ours was not taken: its band is only a dashed outline");
    assertTrue(ConnectorCanvasTest.dashedTop(droppedShot, c, dropped),
        "ours was not taken: the dashed outline is drawn");
    ConnectorCanvas rc = v.connector(PaneKind.RIGHT);
    ConnectorGeometry.Band takenBand = band(rc, block).orElseThrow();
    assertEquals(Palette.strong(LineStyle.Kind.MODIFIED).getRGB(),
        Screens.pixel(Screens.capture(rc, "calc-right-connector-after-take-right"), rc.getClientArea().width / 2,
            midY(takenBand)), "theirs was taken: its band is filled in the taken colour");
    assertTrue(Screens.painted(Screens.capture(v.getControl(), "calc-after-take-right-light")));
    v.undo();
    v.ignoreBlock(block);
    assertEquals(BlockState.IGNORED, input.session().model().blocks().get(block).state());
    ConnectorGeometry.Band ignored = band(c, block).orElseThrow();
    RGB inside = Screens.pixel(Screens.capture(c, "calc-left-connector-ignored"), x, midY(ignored));
    assertEquals(c.getBackground().getRGB(), inside, "an ignored band is only outlined");
    assertTrue(Screens.painted(Screens.capture(v.getControl(), "calc-ignored-light")));
  }

  @Test
  void blocksAreFilledToTheFullLineInEveryPane() throws Exception {
    MergeEditorInput input = openCalc();
    MergeViewer v = input.viewer();
    MergeBlock b = input.session().model().blocks().get(firstOpenConflict(input));
    RGB light = Palette.light(LineStyle.Kind.CONFLICT).getRGB();
    for (PaneKind k : PaneKind.values()) {
      StyledText t = v.pane(k).text();
      t.setCaretOffset(0); // the current-line color must not cover the block
    }
    UiDriver.flush();
    for (PaneKind k : PaneKind.values()) {
      StyledText t = v.pane(k).text();
      int line = BlockStyles.start(b, k);
      int y = t.getLinePixel(line) + t.getLineHeight() / 2;
      int x = t.getClientArea().width - 3; // right of any text on the line
      var shot = Screens.capture(t, "calc-fill-" + k.name().toLowerCase(Locale.ROOT));
      assertEquals(light, Screens.pixel(shot, x, y), k + ": the fill reaches the end of the line");
      assertEquals(light, Screens.pixel(shot, 1, y), k + ": the fill covers the left margin too");
    }
  }

  @Test
  void theCaretLineOfASelectedBlockKeepsTheBlockColourInsideTheOutline() throws Exception {
    MergeEditorInput input = openCalc();
    MergeViewer v = input.viewer();
    int index = firstOpenConflict(input);
    MergeBlock b = input.session().model().blocks().get(index);
    v.selectBlock(index); // puts the result's caret on the block's first line
    StyledText result = v.pane(PaneKind.RESULT).text();
    assertEquals(b.resultStart(), result.getLineAtOffset(result.getCaretOffset()), "the caret is inside the block");
    v.pane(PaneKind.RESULT).text().redraw();
    UiDriver.flush();
    RGB light = Palette.light(LineStyle.Kind.CONFLICT).getRGB();
    for (PaneKind k : PaneKind.values()) {
      StyledText t = v.pane(k).text();
      int line = BlockStyles.start(b, k);
      int top = t.getLinePixel(line);
      int x = t.getClientArea().width - 3;
      var shot = Screens.capture(t, "calc-selected-" + k.name().toLowerCase(Locale.ROOT));
      assertEquals(light, Screens.pixel(shot, x, top + t.getLineHeight() / 2),
          k + ": the block colour, not the current-line colour");
      assertEquals(Palette.current().getRGB(), Screens.pixel(shot, x, top), k + ": the current block's frame");
    }
  }

  @Test
  void anAutoResolvedBlockIsHatchedUntilReviewedAndShowsItsBadge() throws Exception {
    repos.conflict("a.txt", "x a z b y\n", "x A z b y\n", "x a z B y\n");
    IFile file = repos.plainProject("auto-bar", "a.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeBlock b = input.session().model().blocks().get(0);
    assertEquals(BlockState.AUTO_RESOLVED, b.state(), "auto-resolve on open resolves the word conflict");
    StyledText t = input.viewer().pane(PaneKind.RESULT).text();
    t.setCaretOffset(t.getCharCount());
    UiDriver.flush();
    int y = t.getLinePixel(b.resultStart()) + t.getLineHeight() / 2;
    var shot = Screens.capture(t, "auto-bar-light");
    // the stripes and the fill between them
    java.util.Set<RGB> fill = java.util.Set.of(Palette.light(LineStyle.Kind.AUTO).getRGB(),
        Palette.autoHatch().getRGB());
    assertNotEquals(Palette.strong(LineStyle.Kind.AUTO).getRGB(), Screens.pixel(shot, 1, y),
        "no bar in the text any more: it moves to the ruler (block-states §1)");
    java.util.Set<RGB> right = new java.util.HashSet<>();
    for (int x = t.getClientArea().width - 40; x < t.getClientArea().width; x++) {
      right.add(Screens.pixel(shot, x, y));
    }
    assertTrue(right.containsAll(fill), "the auto fill, hatched while it needs review: " + right);
    OriginColumn column = input.viewer().pane(PaneKind.RESULT).originColumn();
    var ruler = Screens.capture(column.getControl(), "auto-ruler-light");
    int rulerY = column.getControl().toControl(t.toDisplay(0, y)).y;
    assertEquals(Palette.strong(LineStyle.Kind.AUTO).getRGB(), Screens.pixel(ruler, 1, rulerY), "the bar in the ruler");
    assertEquals(PaneStyle.Badge.PENDING, column.gutters().get(0).badge());
    input.viewer().selectBlock(0);
    input.viewer().markReviewed();
    t.setCaretOffset(t.getCharCount());
    UiDriver.flush();
    var reviewed = Screens.capture(t, "auto-reviewed-light");
    for (int x = t.getClientArea().width - 40; x < t.getClientArea().width - 3; x++) {
      assertEquals(Palette.light(LineStyle.Kind.AUTO).getRGB(), Screens.pixel(reviewed, x, y), "reviewed: no stripes");
    }
    assertEquals(PaneStyle.Badge.REVIEWED, column.gutters().get(0).badge());
  }

  @Test
  void everyLayout() throws Exception {
    MergeEditorInput input = openCalc();
    for (PaneLayout pl : PaneLayout.values()) {
      UiDriver.click(input.viewer(), MergeViewer.layoutLabel(pl));
      assertEquals(pl, input.viewer().paneLayout());
      assertTrue(Screens.painted(Screens.capture(input.viewer().getControl(),
          "calc-" + pl.name().toLowerCase(Locale.ROOT) + "-light")));
    }
  }

  @Test
  void aJavaSidePaneShowsTheSideNotTakenGreyAndStruckThrough() throws Exception {
    MergeEditorInput input = openCalc();
    MergeViewer v = input.viewer();
    int block = firstOpenConflict(input);
    v.selectBlock(block);
    UiDriver.click(v, Messages.takeLeft);
    MergeBlock b = input.session().model().blocks().get(block);
    StyledText right = v.pane(PaneKind.RIGHT).text();
    int offset = right.getOffsetAtLine(BlockStyles.start(b, PaneKind.RIGHT));
    String line = right.getLine(BlockStyles.start(b, PaneKind.RIGHT));
    int at = offset + Math.max(0, line.length() - line.stripLeading().length()); // the first non-blank character
    UiDriver.flush();
    org.eclipse.swt.custom.StyleRange r = right.getStyleRangeAtOffset(at);
    assertNotNull(r, "a style on the dropped text of the Java pane");
    assertTrue(r.strikeout, "struck through over the Java syntax colours");
    assertEquals(Palette.strong(LineStyle.Kind.DROPPED), r.foreground);
    OriginColumn column = v.pane(PaneKind.RESULT).originColumn();
    assertNotNull(column, "the Java result has the origin column");
    assertTrue(column.gutters().contains(new PaneStyle.Gutter(block, b.resultStart(), b.resultEnd(),
        PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT, PaneStyle.Badge.NONE)), "the take in its ruler: " + column.gutters());
    assertTrue(Screens.painted(Screens.capture(v.getControl(), "calc-after-take-left-light")));
  }

  @Test
  void everyBlockStateLight() throws Exception {
    MergeEditorInput input = UiDriver.openEveryBlockState(repos, "states-light");
    checkEveryBlockState(input, "block-states-light");
  }

  /** The ruler marks of every look, and the screenshot of all of them together. */
  static void checkEveryBlockState(MergeEditorInput input, String shot) throws Exception {
    var model = input.session().model();
    var states = model.blocks().stream().filter(b -> b.isConflict()).map(b -> b.state()).toList();
    assertEquals(java.util.List.of(BlockState.AUTO_RESOLVED, BlockState.TAKEN_LEFT, BlockState.EDITED,
        BlockState.UNRESOLVED, BlockState.AUTO_RESOLVED), states);
    var gutters = input.viewer().pane(PaneKind.RESULT).originColumn().gutters();
    assertEquals(java.util.List.of(PaneStyle.Bar.AUTO, PaneStyle.Bar.TAKEN, PaneStyle.Bar.EDITED, PaneStyle.Bar.AUTO),
        gutters.stream().map(PaneStyle.Gutter::bar).toList());
    assertEquals(java.util.List.of(PaneStyle.Badge.PENDING, PaneStyle.Badge.NONE, PaneStyle.Badge.NONE,
        PaneStyle.Badge.REVIEWED), gutters.stream().map(PaneStyle.Gutter::badge).toList());
    assertEquals(PaneStyle.Origin.LEFT, gutters.get(1).origin());
    assertEquals(PaneStyle.Origin.EDITED, gutters.get(2).origin());
    assertNotNull(input.viewer().pane(PaneKind.RESULT).painter().selection(), "the open conflict is current");
    StyledText t = input.viewer().pane(PaneKind.RESULT).text();
    var text = Screens.capture(t, shot + "-result");
    int open = model.blocks().stream().filter(b -> b.isConflict() && b.state() == BlockState.UNRESOLVED)
        .findFirst().orElseThrow().resultStart();
    assertEquals(Palette.current().getRGB(), Screens.pixel(text, t.getClientArea().width / 2, t.getLinePixel(open)),
        "the current block's frame");
    OriginColumn column = input.viewer().pane(PaneKind.RESULT).originColumn();
    var ruler = Screens.capture(column.getControl(), shot + "-ruler");
    int y = t.getLinePixel(gutters.get(1).start()) + t.getLineHeight() / 2;
    assertEquals(Palette.takenStrong().getRGB(), Screens.pixel(ruler, 1, column.getControl().toControl(t.toDisplay(0, y)).y),
        "the taken bar in the ruler");
    assertTrue(Screens.painted(Screens.capture(input.viewer().getControl(), shot)));
  }
}
