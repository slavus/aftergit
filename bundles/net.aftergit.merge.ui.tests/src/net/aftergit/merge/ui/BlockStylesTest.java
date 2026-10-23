package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

class BlockStylesTest {
  // block 0: conflict on line 1; block 1: right-only change on line 3 (resolved by git)
  static MergeModel model() {
    return HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\n", "a\nL\nc\nd\n", "a\nR\nc\nR3\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
  }

  private static MergeModel autoResolved(String base, String left, String right) {
    return HybridInitializer.initialize(new MergeInput(base, left, right, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false)).model();
  }

  /** Word conflict that the simple resolver merges into a text equal to neither side. */
  private static MergeModel wordMerge() {
    return autoResolved("int a = 0; int b = 0;\n", "int a = 1; int b = 0;\n", "int a = 0; int b = 3;\n");
  }

  private static LineStyle line(MergeModel m, PaneKind pane, int lineCount, int line) {
    return BlockStyles.compute(m, pane, lineCount).lines()[line];
  }

  @Test
  void unresolvedConflictIsStrongInAllPanes() {
    MergeModel m = model();
    for (PaneKind p : PaneKind.values()) {
      PaneStyle s = BlockStyles.compute(m, p, 4);
      assertEquals(LineStyle.Kind.CONFLICT, s.lines()[1].kind(), p.name());
      assertEquals(LineStyle.Fill.STRONG, s.lines()[1].fill(), p.name());
      assertEquals(LineStyle.Kind.NONE, s.lines()[0].kind(), p.name());
    }
    assertTrue(BlockStyles.compute(m, PaneKind.RESULT, 4).gutters().isEmpty(), "an open conflict has no origin");
  }

  @Test
  void emptyRangeBecomesMarker() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nc\n", "a\nb\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
    PaneStyle left = BlockStyles.compute(m, PaneKind.LEFT, 2);
    assertEquals(1, left.emptyMarkers().size());
    assertEquals(1, left.emptyMarkers().get(0).line());
    assertEquals(LineStyle.Kind.REMOVED, left.emptyMarkers().get(0).kind());
  }

  @Test
  void gitResolvedChangeKeepsTodaysLookAndHasNoOrigin() {
    MergeModel m = model();
    LineStyle right = line(m, PaneKind.RIGHT, 4, 3);
    assertEquals(LineStyle.Kind.MODIFIED, right.kind());
    assertEquals(LineStyle.Fill.LIGHT, right.fill());
    assertFalse(right.topBorder() || right.bottomBorder() || right.dotted() || right.hatched());
    assertEquals(LineStyle.Kind.NONE, line(m, PaneKind.LEFT, 4, 3).kind());
    LineStyle result = line(m, PaneKind.RESULT, 4, 3);
    assertEquals(LineStyle.Kind.MODIFIED, result.kind(), "git's own resolution is shown in the result too");
    assertEquals(LineStyle.Fill.LIGHT, result.fill());
    assertEquals(PaneStyle.Origin.NONE, BlockStyles.origin(m, m.blocks().get(1)), "not a conflict: no origin");
  }

  @Test
  void takeLeftFillsOursAndTheResultInTheTakenColourAndDropsTheirs() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    LineStyle left = line(m, PaneKind.LEFT, 4, 1);
    assertEquals(LineStyle.Kind.MODIFIED, left.kind());
    assertEquals(LineStyle.Fill.LIGHT, left.fill());
    assertFalse(left.topBorder() || left.bottomBorder());
    LineStyle right = line(m, PaneKind.RIGHT, 4, 1);
    assertEquals(LineStyle.Kind.DROPPED, right.kind());
    assertEquals(LineStyle.Fill.LIGHT, right.fill());
    assertTrue(right.topBorder() && right.bottomBorder());
    assertEquals(LineStyle.Border.DASH, right.border());
    LineStyle result = line(m, PaneKind.RESULT, 4, 1);
    assertEquals(LineStyle.Kind.MODIFIED, result.kind());
    assertEquals(LineStyle.Fill.LIGHT, result.fill());
    assertFalse(result.topBorder() || result.bottomBorder() || result.hatched());
    assertEquals(List.of(new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT,
        PaneStyle.Badge.NONE)), BlockStyles.compute(m, PaneKind.RESULT, 4).gutters());
  }

  @Test
  void takeRightMirrors() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    assertEquals(LineStyle.Kind.DROPPED, line(m, PaneKind.LEFT, 4, 1).kind());
    assertEquals(LineStyle.Kind.MODIFIED, line(m, PaneKind.RIGHT, 4, 1).kind());
    assertEquals(PaneStyle.Origin.RIGHT, BlockStyles.compute(m, PaneKind.RESULT, 4).gutters().get(0).origin());
  }

  @Test
  void takingBothSidesKeepsBothInTheTakenColour() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    m.take(0, ThreeSide.RIGHT);
    assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    assertEquals(LineStyle.Kind.MODIFIED, line(m, PaneKind.LEFT, 4, 1).kind());
    assertEquals(LineStyle.Kind.MODIFIED, line(m, PaneKind.RIGHT, 4, 1).kind());
    assertEquals(List.of(new PaneStyle.Gutter(0, 1, 3, PaneStyle.Bar.TAKEN, PaneStyle.Origin.BOTH,
        PaneStyle.Badge.NONE)), BlockStyles.compute(m, PaneKind.RESULT, 5).gutters());
  }

  @Test
  void anEditedConflictDropsBothSidesAndGetsTheEditedBar() {
    MergeModel m = model();
    m.document().replaceLines(1, 2, "typed\n");
    m.onExternalEdit(1, 2, 1);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals(LineStyle.Kind.DROPPED, line(m, PaneKind.LEFT, 4, 1).kind());
    assertEquals(LineStyle.Kind.DROPPED, line(m, PaneKind.RIGHT, 4, 1).kind());
    LineStyle result = line(m, PaneKind.RESULT, 4, 1);
    assertEquals(LineStyle.Kind.MODIFIED, result.kind(), "the taken fill");
    assertEquals(List.of(new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.EDITED, PaneStyle.Origin.EDITED,
        PaneStyle.Badge.NONE)), BlockStyles.compute(m, PaneKind.RESULT, 4).gutters());
  }

  @Test
  void anAutoResolvedBlockThatNeedsReviewIsHatchedFramedAndBadgedInTheAutoColour() {
    MergeModel m = wordMerge();
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertTrue(m.blocks().get(0).needsReview());
    LineStyle r = line(m, PaneKind.RESULT, 1, 0);
    assertEquals(LineStyle.Kind.AUTO, r.kind());
    assertEquals(LineStyle.Fill.LIGHT, r.fill());
    assertTrue(r.hatched());
    assertTrue(r.topBorder() && r.bottomBorder());
    assertEquals(LineStyle.Border.DOT, r.border());
    for (PaneKind side : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
      LineStyle s = line(m, side, 1, 0);
      assertEquals(LineStyle.Kind.AUTO, s.kind(), side + " contributed: the auto colour");
      assertEquals(LineStyle.Fill.LIGHT, s.fill(), side.name());
      assertFalse(s.hatched() || s.topBorder(), side.name());
    }
    assertEquals(List.of(new PaneStyle.Gutter(0, 0, 1, PaneStyle.Bar.AUTO, PaneStyle.Origin.BOTH,
        PaneStyle.Badge.PENDING)), BlockStyles.compute(m, PaneKind.RESULT, 1).gutters());
  }

  @Test
  void aReviewedAutoBlockLosesHatchingAndFrameAndKeepsAnOutlinedBadge() {
    MergeModel m = wordMerge();
    m.markReviewed(0);
    LineStyle r = line(m, PaneKind.RESULT, 1, 0);
    assertEquals(LineStyle.Kind.AUTO, r.kind());
    assertFalse(r.hatched());
    assertFalse(r.topBorder() || r.bottomBorder());
    assertEquals(PaneStyle.Badge.REVIEWED, BlockStyles.compute(m, PaneKind.RESULT, 1).gutters().get(0).badge());
  }

  @Test
  void anAutoResultEqualToOursDropsTheirs() {
    MergeModel m = model();
    m.applyResolution(0, m.sideText(0, ThreeSide.LEFT), BlockState.AUTO_RESOLVED, Strategy.TEXT, "test", null);
    assertEquals(PaneStyle.Origin.LEFT, BlockStyles.origin(m, m.blocks().get(0)));
    assertEquals(LineStyle.Kind.AUTO, line(m, PaneKind.LEFT, 4, 1).kind());
    assertEquals(LineStyle.Kind.DROPPED, line(m, PaneKind.RIGHT, 4, 1).kind());
  }

  @Test
  void notCompilingWinsOverTheReviewFrame() {
    MergeModel m = wordMerge();
    PaneStyle s = BlockStyles.compute(m, PaneKind.RESULT, 1, i -> false, i -> true);
    assertEquals(List.of(new PaneStyle.Frame(0, 1)), s.errorFrames());
    assertFalse(s.lines()[0].topBorder() || s.lines()[0].bottomBorder(), "the error frame instead of the dots");
    assertTrue(s.lines()[0].hatched(), "it still needs review");
    assertEquals(PaneStyle.Badge.PENDING, s.gutters().get(0).badge());
  }

  @Test
  void anAiBlockKeepsItsLookAndShowsAnOriginOnlyWhenItEqualsASide() {
    MergeModel m = model();
    m.applyResolution(0, m.sideText(0, ThreeSide.RIGHT), BlockState.AI_RESOLVED, Strategy.TEXT, "agent", "why");
    LineStyle r = line(m, PaneKind.RESULT, 4, 1);
    assertEquals(LineStyle.Kind.AI, r.kind());
    assertEquals(LineStyle.Border.SOLID, r.border());
    assertTrue(r.topBorder() && r.bottomBorder());
    LineStyle left = line(m, PaneKind.LEFT, 4, 1);
    assertNotEquals(LineStyle.Kind.DROPPED, left.kind(), "AI blocks keep their side look");
    assertEquals(LineStyle.Fill.NONE, left.fill());
    assertTrue(left.dotted());
    assertEquals(List.of(new PaneStyle.Gutter(0, 1, 2, PaneStyle.Bar.NONE, PaneStyle.Origin.RIGHT,
        PaneStyle.Badge.NONE)), BlockStyles.compute(m, PaneKind.RESULT, 4).gutters(), "no A badge on AI blocks");

    m.applyResolution(0, "new text\n", BlockState.AI_RESOLVED, Strategy.TEXT, "agent", "why");
    assertTrue(BlockStyles.compute(m, PaneKind.RESULT, 4).gutters().isEmpty(), "new text: no origin");
  }

  @Test
  void ignoredBlockIsFilledInTheSidePanesAndLeftAloneInTheResult() {
    MergeModel m = model();
    m.ignore(0);
    assertEquals(BlockState.IGNORED, m.blocks().get(0).state());
    LineStyle left = line(m, PaneKind.LEFT, 4, 1);
    assertEquals(LineStyle.Kind.MODIFIED, left.kind());
    assertEquals(LineStyle.Fill.LIGHT, left.fill());
    PaneStyle result = BlockStyles.compute(m, PaneKind.RESULT, 4);
    assertEquals(LineStyle.NONE, result.lines()[1]);
    assertTrue(result.emptyMarkers().isEmpty());
    assertTrue(result.gutters().isEmpty());
  }

  @Test
  void addedLinesAreAddedInTheSidePaneAndTheResult() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nc\n", "a\nb\nc\n", "a\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(0).state());
    assertEquals(LineStyle.Kind.ADDED, line(m, PaneKind.LEFT, 3, 1).kind());
    assertEquals(LineStyle.Kind.ADDED, line(m, PaneKind.RESULT, 3, 1).kind());
    assertEquals(LineStyle.Fill.LIGHT, line(m, PaneKind.RESULT, 3, 1).fill());
  }

  @Test
  void aWhitespaceIgnoredResolutionHasTheReviewDotsUntilReviewed() {
    MergeModel m = autoResolved("a b c\n", "a\tb\tc\n", "a  b c\n");
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertTrue(BlockStyles.whitespaceIgnored(m.blocks().get(0)));
    LineStyle s = line(m, PaneKind.RESULT, 1, 0);
    assertEquals(LineStyle.Kind.AUTO, s.kind());
    assertTrue(s.dotted());
    assertTrue(s.topBorder() && s.bottomBorder());
    m.markReviewed(0);
    LineStyle reviewed = line(m, PaneKind.RESULT, 1, 0);
    assertFalse(reviewed.dotted());
    assertEquals(PaneStyle.Bar.AUTO, BlockStyles.compute(m, PaneKind.RESULT, 1).gutters().get(0).bar(),
        "the bar stays: the block is still auto-resolved");
  }

  @Test
  void unresolvedConflictIsNotDotted() {
    PaneStyle s = BlockStyles.compute(model(), PaneKind.LEFT, 4);
    assertFalse(s.lines()[1].dotted());
  }

  @Test
  void aBlockThatDoesNotCompileIsFramedInTheResultOnly() {
    MergeModel m = model();
    assertEquals(List.of(new PaneStyle.Frame(1, 2)),
        BlockStyles.compute(m, PaneKind.RESULT, 4, i -> false, i -> i == 0).errorFrames());
    assertTrue(BlockStyles.compute(m, PaneKind.LEFT, 4, i -> false, i -> i == 0).errorFrames().isEmpty());
    assertTrue(BlockStyles.compute(m, PaneKind.RESULT, 4).errorFrames().isEmpty());
  }

  @Test
  void originsHaveOneEntryPerBlock() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    assertArrayEquals(new PaneStyle.Origin[] {PaneStyle.Origin.RIGHT, PaneStyle.Origin.NONE}, BlockStyles.origins(m));
  }
}
