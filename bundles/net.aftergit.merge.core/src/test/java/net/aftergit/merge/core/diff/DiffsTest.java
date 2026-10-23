package net.aftergit.merge.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffsTest {
  @Test
  void identicalListsAreOneUnchangedRange() {
    assertEquals(List.of(new Range(0, 3, 0, 3)), Diffs.unchanged(List.of("a", "b", "c"), List.of("a", "b", "c")));
  }

  @Test
  void insertionSplitsUnchangedRanges() {
    assertEquals(List.of(new Range(0, 1, 0, 1), new Range(1, 2, 2, 3)),
        Diffs.unchanged(List.of("a", "c"), List.of("a", "X", "c")));
  }

  @Test
  void deletionSplitsUnchangedRanges() {
    assertEquals(List.of(new Range(0, 1, 0, 1), new Range(2, 3, 1, 2)),
        Diffs.unchanged(List.of("a", "X", "c"), List.of("a", "c")));
  }

  @Test
  void emptyInputs() {
    assertEquals(List.of(), Diffs.unchanged(List.of(), List.of()));
    assertEquals(List.of(), Diffs.unchanged(List.of("a"), List.of()));
    assertEquals(List.of(), Diffs.unchanged(List.of(), List.of("a")));
  }

  @Test
  void rangesHaveEqualLengthsAndEqualContent() {
    List<String> a = List.of("1", "2", "3", "4", "5", "6");
    List<String> b = List.of("0", "2", "3", "x", "5", "6", "7");
    for (Range r : Diffs.unchanged(a, b)) {
      assertEquals(r.end1() - r.start1(), r.end2() - r.start2());
      assertEquals(a.subList(r.start1(), r.end1()), b.subList(r.start2(), r.end2()));
    }
  }
}
