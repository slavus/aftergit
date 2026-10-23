package net.aftergit.merge.core.check;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class CompileCheckTest {
  static final List<LineProblem> BOOM = List.of(new LineProblem(0, "boom"));

  /** One block, auto-resolved at word level: line 0 of the result and of both sides. */
  static MergeModel model() {
    LineText l = LineText.of("int a = 1; int b = 0;\n");
    LineText b = LineText.of("int a = 0; int b = 0;\n");
    LineText r = LineText.of("int a = 0; int b = 3;\n");
    MergeModel m = MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    ResolverPipeline.defaults(false).run(m);
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    return m;
  }

  @Test
  void aNewErrorInAnAutoResolvedBlockMarksIt() {
    assertEquals(Set.of(0), CompileCheck.failingBlocks(model(), BOOM, List.of(), List.of()));
  }

  @Test
  void theNewErrorsOfEachBlockAreTheOnesNoSideHasThere() {
    List<LineProblem> result = List.of(new LineProblem(0, "boom"), new LineProblem(0, "other"));
    assertEquals(java.util.Map.of(0, List.of(new LineProblem(0, "other"))),
        CompileCheck.newErrors(model(), result, BOOM, List.of()));
    assertEquals(java.util.Map.of(), CompileCheck.newErrors(model(), result, null, List.of()), "unknown: none");
  }

  @Test
  void anErrorOursHasThereIsInherited() {
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), BOOM, BOOM, List.of()));
  }

  @Test
  void anErrorTheirsHasThereIsInherited() {
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), BOOM, List.of(), BOOM));
  }

  @Test
  void theSameErrorElsewhereInTheSideIsNotInherited() {
    assertEquals(Set.of(0), CompileCheck.failingBlocks(model(), BOOM, List.of(new LineProblem(7, "boom")), List.of()));
  }

  @Test
  void anotherErrorThereIsNotInherited() {
    assertEquals(Set.of(0), CompileCheck.failingBlocks(model(), BOOM, List.of(new LineProblem(0, "other")), List.of()));
  }

  @Test
  void withoutSideResultsNothingIsMarked() {
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), BOOM, null, List.of()));
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), BOOM, List.of(), null));
  }

  @Test
  void onlyAutoAndAiResolvedBlocksAreChecked() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, BOOM, List.of(), List.of()));
  }

  @Test
  void errorsOutsideTheBlockDoNotCount() {
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), List.of(new LineProblem(1, "boom")), List.of(), List.of()));
  }

  @Test
  void aMissingResultListIsLikeNoErrors() {
    assertEquals(Set.of(), CompileCheck.failingBlocks(model(), null, List.of(), List.of()));
  }

  /**
   * The conflict is at line 4 of the result and of ours, but at line 2 of theirs (ours added two lines on top):
   * the block's side ranges are [4, 5) in ours and [2, 3) in theirs.
   */
  static MergeModel shifted() {
    LineText l = LineText.of("n1\nn2\ntop\nmid\nint a = 1; int b = 0;\n");
    LineText b = LineText.of("top\nmid\nint a = 0; int b = 0;\n");
    LineText r = LineText.of("top\nmid\nint a = 0; int b = 3;\n");
    MergeModel m = MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    ResolverPipeline.defaults(false).run(m);
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(m.blocks().size() - 1).state());
    return m;
  }

  private static int resolved(MergeModel m) {
    return m.blocks().get(m.blocks().size() - 1).index();
  }

  @Test
  void resultAndSideLinesDifferWhenTheBlockIsNotAtLineZero() {
    MergeModel m = shifted();
    int k = resolved(m);
    int line = m.blocks().get(k).resultStart();
    assertEquals(4, line);
    List<LineProblem> result = List.of(new LineProblem(line, "boom"));
    assertEquals(Set.of(k), CompileCheck.failingBlocks(m, result, List.of(), List.of()));
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, result, List.of(new LineProblem(4, "boom")), List.of()),
        "ours has it on its own line 4");
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, result, List.of(), List.of(new LineProblem(2, "boom"))),
        "theirs has it on its line 2, not on line 4");
    assertEquals(Set.of(k), CompileCheck.failingBlocks(m, result, List.of(new LineProblem(2, "boom")),
        List.of(new LineProblem(4, "boom"))), "the lines of the other side do not count");
  }

  @Test
  void aProblemExactlyAtTheEndOfASideRangeIsOutsideIt() {
    MergeModel m = model(); // the sides' range is [0, 1)
    assertEquals(Set.of(0), CompileCheck.failingBlocks(m, BOOM, List.of(new LineProblem(1, "boom")), List.of()));
    assertEquals(Set.of(0), CompileCheck.failingBlocks(m, BOOM, List.of(), List.of(new LineProblem(1, "boom"))));
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, BOOM, List.of(new LineProblem(0, "boom")), List.of()),
        "the start is inside");
  }

  @Test
  void anAiResolvedBlockIsChecked() {
    MergeModel m = model();
    m.applyResolution(0, "int a = 1; int b = 3;\n", BlockState.AI_RESOLVED, Strategy.SEMANTIC, "ai", null);
    assertEquals(Set.of(0), CompileCheck.failingBlocks(m, BOOM, List.of(), List.of()));
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, BOOM, BOOM, List.of()));
  }

  @Test
  void aBlockWithItsOwnSideTextIsComparedWithTheWholeSide() {
    // the user edited inside git's markers before opening: the block keeps the hunk's text as its own side text
    String working = "<<<<<<< ours\nint a = 1; int b = 0; \n||||||| base\nint a = 0; int b = 0;\n=======\n"
        + "int a = 0; int b = 3;\n>>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("int a = 0; int b = 0;\n",
        "int a = 1; int b = 0;\n", "int a = 0; int b = 3;\n", working),
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    MergeModel m = r.model();
    assertFalse(r.usedFallback());
    assertNotNull(m.blocks().get(0).leftOverride());
    m.applyResolution(0, "int a = 1; int b = 3;\n", BlockState.AUTO_RESOLVED, Strategy.SEMANTIC, "auto", null);
    assertEquals(Set.of(0), CompileCheck.failingBlocks(m, BOOM, List.of(), List.of()));
    assertEquals(Set.of(), CompileCheck.failingBlocks(m, BOOM, List.of(new LineProblem(9, "boom")), List.of()),
        "an own side text has no line range: any equal message of the side counts");
    assertEquals(Set.of(0), CompileCheck.failingBlocks(m, BOOM, List.of(new LineProblem(9, "other")), List.of()));
  }
}
