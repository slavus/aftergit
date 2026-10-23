package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.LinesResultDocument;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class BlockLocatorTest {
  // two conflicts: lines 1 and 3
  static MergeModel model() {
    return HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n",
        "a\nR1\nc\nR3\ne\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
  }

  @Test
  void blockAtLine() {
    MergeModel m = model();
    assertEquals(0, BlockLocator.blockAt(m, PaneKind.RESULT, 1));
    assertEquals(1, BlockLocator.blockAt(m, PaneKind.LEFT, 3));
    assertEquals(-1, BlockLocator.blockAt(m, PaneKind.RESULT, 2));
  }

  @Test
  void nextAndPreviousConflict() {
    MergeModel m = model();
    assertEquals(0, BlockLocator.nextConflict(m, 0, true));
    assertEquals(1, BlockLocator.nextConflict(m, 1, true));
    assertEquals(-1, BlockLocator.nextConflict(m, 3, true));
    assertEquals(0, BlockLocator.nextConflict(m, 3, false));
    assertEquals(-1, BlockLocator.nextConflict(m, 1, false));
  }

  @Test
  void nextChangePerPane() {
    var m = BlockStylesTest.model();
    assertEquals(0, BlockLocator.nextChange(m, PaneKind.RIGHT, -1, true));
    assertEquals(1, BlockLocator.nextChange(m, PaneKind.RIGHT, 1, true));
    assertEquals(-1, BlockLocator.nextChange(m, PaneKind.LEFT, 1, true)); // block 1 is not on the left
    assertEquals(0, BlockLocator.nextChange(m, PaneKind.RIGHT, 3, false));
    // result: block 1 is resolved by git and invisible there
    assertEquals(-1, BlockLocator.nextChange(m, PaneKind.RESULT, 1, true));
  }

  @Test
  void nextAutoResolvedFindsOnlyAutoBlocks() {
    var m = BlockStylesTest.model();
    assertEquals(-1, BlockLocator.nextAutoResolved(m, -1, true));
    m.applyResolution(0, "S\n", net.aftergit.merge.core.model.BlockState.AUTO_RESOLVED,
        net.aftergit.merge.core.model.Strategy.TEXT, "test", null);
    assertEquals(0, BlockLocator.nextAutoResolved(m, -1, true));
    assertEquals(0, BlockLocator.nextAutoResolved(m, 3, false));
  }

  @Test
  void theCurrentBlockOfASidePaneIsOnlyABlockThatSideChanged() {
    var m = BlockStylesTest.model(); // conflict at line 1, right-only change at line 3
    assertEquals(0, BlockLocator.currentAt(m, PaneKind.LEFT, 1));
    assertEquals(-1, BlockLocator.currentAt(m, PaneKind.LEFT, 3), "the left side did not change line 3");
    assertEquals(1, BlockLocator.currentAt(m, PaneKind.RIGHT, 3));
    assertEquals(1, BlockLocator.currentAt(m, PaneKind.RESULT, 3), "git's own change counts in the result");
    assertEquals(-1, BlockLocator.currentAt(m, PaneKind.RESULT, 0), "outside every block");
  }

  @Test
  void anEmptyBlockIsCurrentOnTheLineOfItsMarker() {
    var m = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nc\n", "a\nB\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    assertEquals(0, BlockLocator.currentAt(m, PaneKind.LEFT, 1), "ours deleted the line: its marker is on line 1");
  }

  /**
   * Block 0 is a taken deletion: 0 lines in the left pane and the result, its marker on line 1. Block 1 is an open
   * conflict right behind it, lines 1-2 in the left pane and the result.
   */
  static MergeModel emptyNextToABlock() {
    ConflictType conflict = new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    List<MergeBlock> blocks = List.of(
        new MergeBlock(0, new MergeRange(1, 1, 1, 2, 1, 2), conflict, "a\n", 1, 1, BlockState.TAKEN_LEFT,
            Strategy.NONE, null),
        new MergeBlock(1, new MergeRange(1, 3, 2, 4, 2, 4), conflict, "b\nc\n", 1, 3, BlockState.UNRESOLVED,
            Strategy.NONE, null));
    return new MergeModel(LineText.of("x\nL\nL2\ny\n"), LineText.of("x\na\nb\nc\ny\n"),
        LineText.of("x\nR\nR2\nR3\ny\n"), new LinesResultDocument(List.of("x", "b", "c", "y")), blocks);
  }

  @Test
  void theCurrentBlockStaysWhileItCoversTheLineAnEmptyOneOnItsMarkerLine() {
    var m = emptyNextToABlock();
    for (PaneKind p : new PaneKind[] {PaneKind.RESULT, PaneKind.LEFT}) {
      assertEquals(0, BlockLocator.currentAt(m, p, 1, 0), p + ": the empty block keeps its marker line");
      assertEquals(1, BlockLocator.currentAt(m, p, 1, 1), p + ": the neighbour keeps its first line");
      assertEquals(1, BlockLocator.currentAt(m, p, 1, -1), p + ": without a current block the lines win");
      assertEquals(1, BlockLocator.currentAt(m, p, 2, 0), p + ": the empty block does not cover the next line");
      assertEquals(-1, BlockLocator.currentAt(m, p, 0, 1), p + ": outside every block");
    }
  }

  @Test
  void aBlockTheSideDidNotChangeDoesNotStayCurrentInThatSidePane() {
    var m = BlockStylesTest.model(); // right-only change at line 3
    assertEquals(-1, BlockLocator.currentAt(m, PaneKind.LEFT, 3, 1));
    assertEquals(1, BlockLocator.currentAt(m, PaneKind.RIGHT, 3, 1));
  }

  @Test
  void theConflictWalkVisitsEveryConflictInAnyState() {
    MergeModel m = model(); // conflicts at lines 1 and 3
    m.take(0, net.aftergit.merge.core.merge.ThreeSide.LEFT);
    assertEquals(0, BlockLocator.nextAnyConflict(m, -1, 0, true), "a taken conflict is on the walk");
    assertEquals(0, BlockLocator.nextAnyConflict(m, -1, 3, false), "backwards without a current block");
    assertEquals(1, BlockLocator.nextConflict(m, 0, true), "the unresolved walk skips the taken one");
    assertEquals(1, BlockLocator.nextAnyConflict(m, 0, 1, true), "from the current block to the next one");
    assertEquals(-1, BlockLocator.nextAnyConflict(m, 1, 3, true));
    assertEquals(0, BlockLocator.nextAnyConflict(m, 1, 3, false));
    assertEquals(-1, BlockLocator.nextAnyConflict(m, 0, 1, false));
  }

  @Test
  void theConflictWalkSkipsChangesThatWereNeverConflicts() {
    var m = BlockStylesTest.model(); // conflict 0, git change 1
    assertEquals(-1, BlockLocator.nextAnyConflict(m, 0, 1, true));
    assertEquals(0, BlockLocator.nextAnyConflict(m, 1, 3, false), "from a git change back to the conflict");
  }

  @Test
  void theConflictWalkStepsByBlockFromTheCurrentBlockEvenWhenItIsEmpty() {
    MergeModel m = net.aftergit.merge.core.model.HybridInitializer.initialize(
        new net.aftergit.merge.core.model.MergeInput("a\nb\nc\nd\ne\n", "a\nc\ne\n", "a\nR1\nc\nR3\ne\n", null),
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT,
        net.aftergit.merge.core.resolve.ResolverPipeline.none()).model();
    assertEquals(2, m.blocks().size());
    m.take(0, net.aftergit.merge.core.merge.ThreeSide.LEFT); // ours deleted the line: the block has no lines now
    assertEquals(m.blocks().get(0).resultStart(), m.blocks().get(0).resultEnd());
    assertEquals(1, BlockLocator.nextAnyConflict(m, 0, m.blocks().get(0).resultStart(), true));
    assertEquals(0, BlockLocator.nextAnyConflict(m, 1, m.blocks().get(1).resultStart(), false));
  }
}
