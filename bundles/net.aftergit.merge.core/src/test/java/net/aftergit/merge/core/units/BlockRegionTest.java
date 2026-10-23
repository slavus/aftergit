package net.aftergit.merge.core.units;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.BlockContext;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design: a block is widened to whole units, merged, and only its own lines change. */
class BlockRegionTest {
  /** "# key" starts a unit up to the next "# "; lines above the first header belong to the first; "!" is unparsable. */
  static final Segmenter HEADERS = (text, from, to) -> {
    List<String> lines = LineText.chunkLines(text);
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("!")) {
        return Optional.empty();
      }
      if (lines.get(i).startsWith("# ")) {
        starts.add(i);
      }
    }
    if (starts.isEmpty()) {
      return Optional.empty();
    }
    List<Unit> units = new ArrayList<>();
    for (int k = 0; k < starts.size(); k++) {
      int start = k == 0 ? 0 : starts.get(k);
      int end = k + 1 < starts.size() ? starts.get(k + 1) : lines.size();
      units.add(new Unit(lines.get(starts.get(k)).substring(2), start, end));
    }
    return Optional.of(new Body(0, lines.size(), units));
  };

  static final BlockRegion.Merger STRICT = (b, o, t) -> UnitMerge.merge(b, o, t, UnitMerge.Policy.STRICT);

  /** A model whose only block is the conflict {@code range} (lines outside it must be equal in all three texts). */
  static MergeModel conflict(String l, String b, String r, MergeRange range) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    return MergeModel.fromChanges(lt, bt, rt,
        List.of(new MergeChange(range, new ConflictType(ConflictType.Kind.CONFLICT, true, true))));
  }

  static Optional<String> resolve(MergeModel m, BlockRegion.Merger merger) {
    return resolve(m, HEADERS, merger);
  }

  static Optional<String> resolve(MergeModel m, Segmenter segmenter, BlockRegion.Merger merger) {
    MergeBlock block = m.blocks().get(0);
    return BlockRegion.resolve(new BlockContext(m, block, m.sideText(0, ThreeSide.LEFT), block.baseText(),
        m.sideText(0, ThreeSide.RIGHT)), segmenter, merger);
  }

  static final String BASE = "# a\n1\n# b\n2\n";
  static final String OURS = "# a\n1\n# c\n3\n# b\n2\n";
  static final String THEIRS = "# a\n1\n# d\n4\n# b\n2\n";

  @Test
  void aBlockOnUnitBoundariesIsMergedByUnit() {
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(2, 4, 2, 2, 2, 4));
    assertEquals(Optional.of("# c\n3\n# d\n4\n"), resolve(m, STRICT));
  }

  @Test
  void aBlockCuttingAUnitIsWidenedToWholeUnits() {
    // the block starts on the second line of unit a: a is merged whole, but only the block's lines are returned
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(1, 4, 1, 2, 1, 4));
    assertEquals(Optional.of("1\n# c\n3\n# d\n4\n"), resolve(m, STRICT));
  }

  @Test
  void aBlockEndingInsideAUnitIsWidenedDownwards() {
    // the block ends on the header of b; b's second line stays outside the block and unchanged
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(2, 5, 2, 3, 2, 5));
    assertEquals(Optional.of("# c\n3\n# d\n4\n# b\n"), resolve(m, STRICT));
  }

  @Test
  void aUnitChangedOnBothSidesStaysAConflict() {
    MergeModel m = conflict("# a\nL\n", "# a\n1\n", "# a\nR\n", new MergeRange(1, 2, 1, 2, 1, 2));
    assertEquals(Optional.empty(), resolve(m, STRICT));
  }

  @Test
  void anUnparsableSideLeavesTheBlockAlone() {
    MergeModel m = conflict(OURS, BASE, "# a\n1\n# d\n!\n# b\n2\n", new MergeRange(2, 4, 2, 2, 2, 4));
    assertEquals(Optional.empty(), resolve(m, STRICT));
  }

  @Test
  void aMergeThatRewritesLinesOutsideTheBlockIsRefused() {
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(1, 4, 1, 2, 1, 4));
    assertEquals(Optional.empty(), resolve(m, (b, o, t) -> Optional.of(List.of(new Keyed("z", "other\n")))));
  }

  @Test
  void anEmptyBlockTakesTheUnitsOfTheSideThatAddedThem() {
    MergeModel m = conflict(OURS, BASE, BASE, new MergeRange(2, 4, 2, 2, 2, 2));
    assertEquals(Optional.of("# c\n3\n"), resolve(m, STRICT));
  }

  @Test
  void aKeyAddedOnBothSidesOnceOutsideTheRegionStaysAConflict() {
    // ours adds c next to a (the conflict) and k at the end (merged by git); theirs adds k next to a
    String b = "# a\n1\n# b\n2\n";
    String l = "# a\n1\n# c\n3\n# b\n2\n# k\n7\n";
    String r = "# a\n1\n# k\n4\n# b\n2\n";
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    MergeModel m = MergeModel.fromChanges(lt, bt, rt, List.of(
        new MergeChange(new MergeRange(2, 4, 2, 2, 2, 4), new ConflictType(ConflictType.Kind.CONFLICT, true, true)),
        new MergeChange(new MergeRange(6, 8, 4, 4, 4, 4), new ConflictType(ConflictType.Kind.INSERTED, true, false))));
    String before = m.document().chunk(0, m.document().lineCount());
    assertEquals(Optional.empty(), resolve(m, STRICT));
    assertEquals(before, m.document().chunk(0, m.document().lineCount()));
  }

  @Test
  void aKeyTwiceInABodyLeavesTheBlockAlone() {
    MergeModel m = conflict("# a\n1\n# a\n5\n# c\n3\n# b\n2\n", "# a\n1\n# a\n5\n# b\n2\n",
        "# a\n1\n# a\n5\n# d\n4\n# b\n2\n", new MergeRange(4, 6, 4, 4, 4, 6));
    assertEquals(Optional.empty(), resolve(m, STRICT));
  }

  @Test
  void aMergedUnitWithoutAFinalNewlineIsRefused() {
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(2, 4, 2, 2, 2, 4));
    assertEquals(Optional.empty(), resolve(m,
        (b, o, t) -> Optional.of(List.of(new Keyed("c", "# c\n3"), new Keyed("d", "# d\n4\n")))));
  }

  @Test
  void aBodyStartingBeforeTheTextIsRefused() {
    Segmenter negative = (text, from, to) -> {
      int n = LineText.chunkLines(text).size();
      return Optional.of(new Body(-1, n, List.of(new Unit("a", -1, n))));
    };
    MergeModel m = conflict(OURS, BASE, THEIRS, new MergeRange(2, 4, 2, 2, 2, 4));
    assertEquals(Optional.empty(), resolve(m, negative, STRICT));
  }

  /** A model with the blocks of {@code changes}. */
  static MergeModel blocks(String l, String b, String r, MergeChange... changes) {
    return MergeModel.fromChanges(LineText.of(l), LineText.of(b), LineText.of(r), List.of(changes));
  }

  /** A change at base [b0, b1), ours [o0, o1), theirs [t0, t1); a non-conflict is a change of the side that differs. */
  static MergeChange change(int b0, int b1, int o0, int o1, int t0, int t1, ConflictType.Kind kind) {
    boolean conflict = kind == ConflictType.Kind.CONFLICT;
    return new MergeChange(new MergeRange(o0, o1, b0, b1, t0, t1),
        new ConflictType(kind, conflict || o1 - o0 != b1 - b0, conflict || t1 - t0 != b1 - b0));
  }

  @Test
  void theSameKeyAddedOnBothSidesInTwoConflictsStaysAConflict() {
    // ours adds f next to a, theirs adds d there (conflict 1); at the end theirs adds f, ours adds g (conflict 2)
    String b = "# a\n1\n# b\n2\n";
    String l = "# a\n1\n# f\nx\n# b\n2\n# g\n7\n";
    String r = "# a\n1\n# d\n4\n# b\n2\n# f\ny\n";
    MergeModel m = blocks(l, b, r, change(2, 2, 2, 4, 2, 4, ConflictType.Kind.CONFLICT),
        change(4, 4, 6, 8, 6, 8, ConflictType.Kind.CONFLICT));
    assertEquals(Optional.empty(), resolve(m, STRICT));
    MergeBlock second = m.blocks().get(1);
    assertEquals(Optional.empty(), BlockRegion.resolve(new BlockContext(m, second, m.sideText(1, ThreeSide.LEFT),
        second.baseText(), m.sideText(1, ThreeSide.RIGHT)), HEADERS, STRICT));
  }

  @Test
  void aDeleteMeetingTheKeyReAddedElsewhereStaysAConflict() {
    // ours deletes c; theirs puts d in its place and re-adds c, changed, at the end (merged by git)
    String b = "# a\n1\n# c\n3\n# b\n2\n";
    String l = "# a\n1\n# b\n2\n";
    String r = "# a\n1\n# d\n4\n# b\n2\n# c\n9\n";
    MergeModel m = blocks(l, b, r, change(2, 4, 2, 2, 2, 4, ConflictType.Kind.CONFLICT),
        change(6, 6, 4, 4, 6, 8, ConflictType.Kind.INSERTED));
    assertEquals(Optional.empty(), resolve(m, STRICT));
  }
}
