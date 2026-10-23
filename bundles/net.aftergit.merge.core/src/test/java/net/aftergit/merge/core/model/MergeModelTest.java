package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class MergeModelTest {
  // left changes line 1, right changes line 1 differently (conflict) and line 3 (non-conflict)
  static MergeModel model() {
    LineText l = LineText.of("a\nL1\nc\nd\n");
    LineText b = LineText.of("a\nb\nc\nd\n");
    LineText r = LineText.of("a\nR1\nc\nR3\n");
    return MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
  }

  static String text(MergeModel m) {
    return ((LinesResultDocument) m.document()).text();
  }

  @Test
  void blocksKeepTheRegionOfTheirChange() {
    LineText l = LineText.of("a\nL1\nc\nd\n");
    LineText b = LineText.of("a\nb\nc\nd\n");
    LineText r = LineText.of("a\nR1\nc\nR3\n");
    List<MergeChange> changes = new java.util.ArrayList<>(LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    changes.set(0, new MergeChange(changes.get(0).range(), changes.get(0).type(), Region.IMPORTS));
    MergeModel m = MergeModel.fromChanges(l, b, r, changes);
    assertEquals(Region.IMPORTS, m.blocks().get(0).region());
    assertEquals(Region.BODY, m.blocks().get(1).region());
    assertEquals(Region.BODY, model().blocks().get(0).region(), "changes without a region are body changes");
  }

  @Test
  void fromChangesAppliesNonConflictingAndKeepsBaseForConflicts() {
    MergeModel m = model();
    assertEquals("a\nb\nc\nR3\n", text(m));
    assertEquals(2, m.blocks().size());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals(1, m.unresolvedConflicts());
    assertTrue(m.isAutoResolvable(0));
  }

  @Test
  void takeLeftReplacesAndShiftsLaterBlocks() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\nL1\nc\nR3\n", text(m));
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    assertEquals(0, m.unresolvedConflicts());
    assertEquals("R3\n", m.resultText(1));
  }

  @Test
  void secondTakeFromOtherSideAppends() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    m.take(0, ThreeSide.RIGHT);
    assertEquals("a\nL1\nR1\nc\nR3\n", text(m));
    assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    assertEquals(4, m.blocks().get(1).resultStart());
  }

  @Test
  void takeBothOrdersSides() {
    MergeModel m = model();
    m.takeBoth(0, ThreeSide.RIGHT);
    assertEquals("a\nR1\nL1\nc\nR3\n", text(m));
  }

  @Test
  void revertRestoresBase() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    m.revert(0);
    assertEquals("a\nb\nc\nR3\n", text(m));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
  }

  @Test
  void applyResolutionNeedsReview() {
    MergeModel m = model();
    m.applyResolution(0, "X\n", BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    assertTrue(m.blocks().get(0).needsReview());
    assertEquals(1, m.needsReviewCount());
    m.markReviewed(0);
    assertEquals(0, m.needsReviewCount());
  }

  @Test
  void externalEditMarksEdited() {
    MergeModel m = model();
    LinesResultDocument d = (LinesResultDocument) m.document();
    d.replaceLines(1, 2, "typed\n");
    m.onExternalEdit(1, 2, 1);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertFalse(m.isAutoResolvable(0));
  }

  @Test
  void externalInsertAboveShiftsBlocks() {
    MergeModel m = model();
    LinesResultDocument d = (LinesResultDocument) m.document();
    d.replaceLines(0, 0, "new\n");
    m.onExternalEdit(0, 0, 1);
    assertEquals(2, m.blocks().get(0).resultStart());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals("R3\n", m.resultText(1));
  }

  @Test
  void snapshotRestore() {
    MergeModel m = model();
    ModelSnapshot s = m.snapshot();
    m.take(0, ThreeSide.LEFT);
    m.restore(s);
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(2, m.blocks().get(0).resultEnd());
    assertFalse(m.blocks().get(0).needsReview());

    ((LinesResultDocument) m.document()).replaceLines(1, 2, "b\n"); // caller restores the text first
    m.applyResolution(0, "X\nY\n", BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    assertTrue(m.blocks().get(0).needsReview());
    assertEquals(3, m.blocks().get(0).resultEnd());
    ((LinesResultDocument) m.document()).replaceLines(1, 3, "b\n");
    m.restore(s);
    assertEquals(2, m.blocks().get(0).resultEnd());
    assertFalse(m.blocks().get(0).needsReview());
    assertEquals(4, m.blocks().get(1).resultEnd());
  }

  @Test
  void restoreRejectsSnapshotOfAnotherModel() {
    MergeModel m = model();
    ModelSnapshot other = model("a\nL1\nL2\nd\n", "a\nb1\nb2\nd\n", "a\nR1\nR2\nd\n").snapshot();
    assertEquals(1, other.entries().size());
    assertThrows(IllegalArgumentException.class, () -> m.restore(other));
  }

  @Test
  void listenersFireOnOperations() {
    MergeModel m = model();
    int[] calls = {0};
    m.addChangeListener(() -> calls[0]++);
    m.take(0, ThreeSide.LEFT);
    m.ignore(1);
    assertEquals(2, calls[0]);
  }

  @Test
  void rebindRequiresSameText() {
    MergeModel m = model();
    assertThrows(IllegalArgumentException.class, () -> m.rebindDocument(new LinesResultDocument(List.of("x"))));
    m.rebindDocument(new LinesResultDocument(List.of("a", "b", "c", "R3")));
  }

  static MergeModel model(String l, String b, String r) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    return MergeModel.fromChanges(lt, bt, rt, LineMerger.merge(lt, bt, rt, ComparisonPolicy.DEFAULT));
  }

  static void edit(MergeModel m, int start, int end, String chunk) {
    ((LinesResultDocument) m.document()).replaceLines(start, end, chunk);
    m.onExternalEdit(start, end, LineText.chunkLines(chunk).size());
  }

  @Test
  void insertionAtNonEmptyBlockStartBelongsToTheNeighbour() {
    MergeModel m = model();
    edit(m, 1, 1, "new\n");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(2, m.blocks().get(0).resultStart());
    assertEquals(3, m.blocks().get(0).resultEnd());
  }

  @Test
  void insertionAtNonEmptyBlockEndBelongsToTheNeighbour() {
    MergeModel m = model();
    edit(m, 2, 2, "new\n");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(2, m.blocks().get(0).resultEnd());
  }

  @Test
  void insertionStrictlyInsideBlockEditsAndGrowsIt() {
    MergeModel m = model("a\nL1\nL2\nd\n", "a\nb1\nb2\nd\n", "a\nR1\nR2\nd\n");
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(3, m.blocks().get(0).resultEnd());
    edit(m, 2, 2, "new\n");
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(4, m.blocks().get(0).resultEnd());
  }

  @Test
  void insertionIntoEmptyConflictBlockEditsIt() {
    MergeModel m = model("a\nX\nz\n", "a\nz\n", "a\nY\nz\n");
    MergeBlock b = m.blocks().get(0);
    assertTrue(b.isConflict());
    assertEquals(1, b.resultStart());
    assertEquals(1, b.resultEnd());
    edit(m, 1, 1, "typed\n");
    assertEquals(BlockState.EDITED, b.state());
    assertEquals(1, b.resultStart());
    assertEquals(2, b.resultEnd());
    assertEquals("typed\n", m.resultText(0));
    assertFalse(m.isAutoResolvable(0));
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\nX\nz\n", text(m));
  }

  @Test
  void damagingPartialOverlapEditsBlock() {
    MergeModel m = model("a\nL1\nL2\nd\n", "a\nb1\nb2\nd\n", "a\nR1\nR2\nd\n");
    edit(m, 0, 2, "x\n");
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
  }

  @Test
  void typingIntoAnAutomaticResolutionMakesItEditedAndLeavesNothingToReview() {
    for (BlockState auto : new BlockState[] {BlockState.AUTO_RESOLVED, BlockState.AI_RESOLVED}) {
      MergeModel m = model();
      m.applyResolution(0, "X\n", auto, Strategy.TEXT, "simple", "why");
      assertEquals(1, m.needsReviewCount(), auto.name());
      m.document().replaceLines(1, 2, "typed\n");
      m.onExternalEdit(1, 2, 1);
      assertEquals(BlockState.EDITED, m.blocks().get(0).state(), auto.name());
      assertFalse(m.blocks().get(0).needsReview(), auto.name() + ": the user's own text is not to review");
      assertEquals(0, m.needsReviewCount(), auto.name());
      assertNull(m.blocks().get(0).resolvedBy(), auto.name() + ": no resolver stands behind typed text");
      assertNull(m.blocks().get(0).rationale(), auto.name());
    }
  }

  @Test
  void typingIntoAnEmptyConflictOfAnAutomaticResolutionLeavesNothingToReview() {
    LineText l = LineText.of("a\nc\n");
    LineText b = LineText.of("a\nb\nc\n");
    LineText r = LineText.of("a\nR\nc\n");
    MergeModel m = MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    m.applyResolution(0, "", BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    assertEquals(m.blocks().get(0).resultStart(), m.blocks().get(0).resultEnd(), "the resolution left no lines");
    int at = m.blocks().get(0).resultStart();
    m.document().replaceLines(at, at, "typed\n");
    m.onExternalEdit(at, at, 1);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertFalse(m.blocks().get(0).needsReview());
  }

  @Test
  void takingASideOfAnAutomaticOrAiResolutionMakesItManual() {
    for (BlockState auto : new BlockState[] {BlockState.AUTO_RESOLVED, BlockState.AI_RESOLVED}) {
      MergeModel m = model();
      m.applyResolution(0, "X\n", auto, Strategy.TEXT, "simple", "why");
      m.take(0, ThreeSide.LEFT);
      MergeBlock b = m.blocks().get(0);
      assertEquals(BlockState.TAKEN_LEFT, b.state(), auto.name());
      assertFalse(b.needsReview(), auto.name());
      assertNull(b.resolvedBy(), auto.name());
      assertNull(b.rationale(), auto.name());
      assertEquals("L1\n", m.resultText(0), auto.name());
      m.take(0, ThreeSide.RIGHT);
      assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state(), auto.name() + ": the other side next appends");
      assertEquals("L1\nR1\n", m.resultText(0), auto.name());
    }
  }

  @Test
  void takeBothOfAnAutomaticResolutionIsManual() {
    MergeModel m = model();
    m.applyResolution(0, "X\n", BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    m.takeBoth(0, ThreeSide.RIGHT);
    assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    assertFalse(m.blocks().get(0).needsReview());
    assertEquals("R1\nL1\n", m.resultText(0));
  }

  @Test
  void restoringASnapshotBringsBackTheAutomaticResolutionAndItsReviewState() {
    MergeModel m = model();
    m.applyResolution(0, "X\n", BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    ModelSnapshot pending = m.snapshot();
    m.take(0, ThreeSide.LEFT);
    m.document().replaceLines(1, 2, "X\n"); // the caller restores the text first
    m.restore(pending);
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertTrue(m.blocks().get(0).needsReview());
    assertEquals("simple", m.blocks().get(0).resolvedBy());

    m.markReviewed(0);
    ModelSnapshot reviewed = m.snapshot();
    m.take(0, ThreeSide.RIGHT);
    m.document().replaceLines(1, 2, "X\n");
    m.restore(reviewed);
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertFalse(m.blocks().get(0).needsReview(), "a reviewed resolution comes back reviewed");
  }
}
