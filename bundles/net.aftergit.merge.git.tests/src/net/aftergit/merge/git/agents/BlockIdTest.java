package net.aftergit.merge.git.agents;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

/** Agents design §2: a block id survives a rebuild of the model and never matches another conflict. */
class BlockIdTest {
  static final String BASE = "a\nb\nc\nd\ne\n";
  static final String OURS = "a\nL1\nc\nL3\ne\n";
  static final String THEIRS = "a\nR1\nc\nR3\ne\n";

  static MergeModel model(String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
  }

  @Test
  void theIdOfABlockSurvivesARebuildOfTheModel() {
    BlockId id = BlockId.of(model(BASE, OURS, THEIRS), 1);
    MergeModel again = model(BASE, OURS, THEIRS);
    assertEquals(id, BlockId.of(again, 1));
    assertEquals(1, id.find(again));
  }

  @Test
  void findFollowsTheTextsWhenTheIndexMoved() {
    MergeModel m = model(BASE, OURS, THEIRS);
    BlockId id = BlockId.of(m, 1);
    assertEquals(1, new BlockId(0, id.hash()).find(m));
  }

  @Test
  void aChangedConflictIsNotFound() {
    BlockId id = BlockId.of(model(BASE, OURS, THEIRS), 0);
    assertEquals(-1, id.find(model(BASE, OURS, "a\nR2\nc\nR3\ne\n")));
  }

  @Test
  void identicalConflictsAreToldApartByIndex() {
    MergeModel m = model("x\nb\ny\nb\nz\n", "x\nL\ny\nL\nz\n", "x\nR\ny\nR\nz\n");
    BlockId first = BlockId.of(m, 0);
    BlockId second = BlockId.of(m, 1);
    assertEquals(first.hash(), second.hash());
    assertNotEquals(first, second);
    assertEquals(0, first.find(m));
    assertEquals(1, second.find(m));
  }

  @Test
  void blocksThatAreNoConflictAreNeverMatched() {
    MergeModel m = model("a\nb\nc\n", "a\nL\nc\n", "a\nb\nc\n"); // one-sided change only
    assertFalse(m.blocks().get(0).isConflict());
    assertEquals(-1, BlockId.of(m, 0).find(m));
  }

  @Test
  void textAndParseRoundTrip() {
    BlockId id = BlockId.of(model(BASE, OURS, THEIRS), 1);
    assertTrue(id.text().matches("1:[0-9a-f]{12}"), id.text());
    assertEquals(id, BlockId.parse(id.text()));
    assertEquals(id.text(), id.toString());
  }

  @Test
  void malformedIdsAreRefusedWithTheExpectedShape() {
    for (String bad : new String[] {null, "", "x", "1", "a:5d41402abc4b", "1:XYZ", "-1:5d41402abc4b",
        "1:5d41402abc4"}) {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BlockId.parse(bad),
          String.valueOf(bad));
      assertNotNull(e.getMessage());
    }
  }
}
