package net.aftergit.merge.core.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MergeTypesTest {
  /** Classifies a triple of plain strings (empty string = empty side). */
  private static ConflictType classify(String l, String b, String r) {
    Map<ThreeSide, String> t = Map.of(ThreeSide.LEFT, l, ThreeSide.BASE, b, ThreeSide.RIGHT, r);
    return MergeTypes.classify(s -> t.get(s).isEmpty(),
        (x, y) -> t.get(x).strip().equals(t.get(y).strip()),
        (x, y) -> t.get(x).equals(t.get(y)));
  }

  @Test
  void table() {
    assertEquals(new ConflictType(ConflictType.Kind.INSERTED, false, true), classify("", "", "x"));
    assertEquals(new ConflictType(ConflictType.Kind.INSERTED, true, false), classify("x", "", ""));
    assertEquals(new ConflictType(ConflictType.Kind.INSERTED, true, true), classify("x", "", "x"));
    assertEquals(new ConflictType(ConflictType.Kind.CONFLICT, true, true), classify("x", "", "y"));
    assertEquals(new ConflictType(ConflictType.Kind.DELETED, true, true), classify("", "b", ""));
    assertEquals(new ConflictType(ConflictType.Kind.DELETED, true, false), classify("", "b", "b"));
    assertEquals(new ConflictType(ConflictType.Kind.MODIFIED, false, true), classify("b", "b", "y"));
    assertEquals(new ConflictType(ConflictType.Kind.MODIFIED, true, true), classify("y", "b", "y"));
    assertEquals(new ConflictType(ConflictType.Kind.CONFLICT, true, true), classify("x", "b", "y"));
    // whitespace-only on both sides: equal under the (strip) policy, truly different on both sides
    assertEquals(new ConflictType(ConflictType.Kind.MODIFIED, true, true), classify(" b", "b", "b "));
    // whitespace-only on the left side only
    assertEquals(new ConflictType(ConflictType.Kind.MODIFIED, true, false), classify(" b", "b", "b"));
  }

  @Test
  void aChangeNeedsARegion() {
    MergeRange range = new MergeRange(0, 1, 0, 1, 0, 1);
    ConflictType type = new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    assertThrows(NullPointerException.class, () -> new MergeChange(range, type, null));
    assertEquals(Region.BODY, new MergeChange(range, type).region());
  }

  @Test
  void aBlockNeedsARegion() {
    MergeRange range = new MergeRange(0, 1, 0, 1, 0, 1);
    ConflictType type = new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    assertThrows(NullPointerException.class, () -> new net.aftergit.merge.core.model.MergeBlock(0, range, type,
        null, "", 0, 1, net.aftergit.merge.core.model.BlockState.UNRESOLVED, null, null));
  }
}
