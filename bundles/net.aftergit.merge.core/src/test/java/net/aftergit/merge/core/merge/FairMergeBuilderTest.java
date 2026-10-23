package net.aftergit.merge.core.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.aftergit.merge.core.diff.Diffs;
import org.junit.jupiter.api.Test;

class FairMergeBuilderTest {
  private static List<MergeRange> merge(List<String> l, List<String> b, List<String> r,
                                        FairMergeBuilder.SideEquality trueEq) {
    return FairMergeBuilder.build(Diffs.unchanged(b, l), Diffs.unchanged(b, r), l.size(), b.size(), r.size(), trueEq);
  }

  @Test
  void noChanges() {
    assertEquals(List.of(), merge(List.of("a", "b"), List.of("a", "b"), List.of("a", "b"), null));
  }

  @Test
  void leftOnlyModification() {
    assertEquals(List.of(new MergeRange(1, 2, 1, 2, 1, 2)),
        merge(List.of("a", "B", "c"), List.of("a", "b", "c"), List.of("a", "b", "c"), null));
  }

  @Test
  void nonOverlappingChangesOnBothSidesAreSeparate() {
    assertEquals(List.of(new MergeRange(0, 1, 0, 1, 0, 1), new MergeRange(2, 3, 2, 3, 2, 3)),
        merge(List.of("X", "y", "z"), List.of("x", "y", "z"), List.of("x", "y", "Z"), null));
  }

  @Test
  void insertionIntoEmptyBase() {
    assertEquals(List.of(new MergeRange(0, 0, 0, 0, 0, 1)),
        merge(List.of(), List.of(), List.of("x"), null));
  }

  @Test
  void deletionOnLeft() {
    assertEquals(List.of(new MergeRange(1, 1, 1, 2, 1, 2)),
        merge(List.of("x"), List.of("x", "y"), List.of("x", "y"), null));
  }

  @Test
  void ignoredWhitespaceChangesAreEmittedSeparately() {
    // IntelliJ LineComparisonMergeUtilTest.testIgnoreWhitespaces, second merge case
    List<String> left = List.of("x", " y ", "z");
    List<String> base = List.of("x", "Y", "z");
    List<String> right = List.of("x", "  y", "  z");
    List<String> nl = left.stream().map(s -> s.replaceAll("\\s", "")).toList();
    List<String> nb = base.stream().map(s -> s.replaceAll("\\s", "")).toList();
    List<String> nr = right.stream().map(s -> s.replaceAll("\\s", "")).toList();
    FairMergeBuilder.SideEquality trueEq =
        (li, bi, ri) -> base.get(bi).equals(left.get(li)) && base.get(bi).equals(right.get(ri));
    List<MergeRange> ranges = FairMergeBuilder.build(Diffs.unchanged(nb, nl), Diffs.unchanged(nb, nr),
        left.size(), base.size(), right.size(), trueEq);
    assertEquals(List.of(new MergeRange(1, 2, 1, 2, 1, 2), new MergeRange(2, 3, 2, 3, 2, 3)), ranges);
  }
}
