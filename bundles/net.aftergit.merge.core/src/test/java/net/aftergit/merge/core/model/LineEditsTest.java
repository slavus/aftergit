package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.aftergit.merge.core.model.LineEdits.Hunk;
import org.junit.jupiter.api.Test;

class LineEditsTest {
  @Test
  void equalRunsHaveNoHunks() {
    assertEquals(List.of(), LineEdits.between(List.of("a", "b"), List.of("a", "b")));
  }

  @Test
  void reindentedLinesArePairedLineForLine() {
    assertEquals(List.of(new Hunk(0, 1, 0, 1), new Hunk(1, 2, 1, 2)),
        LineEdits.between(List.of("  a;", "  b;"), List.of("\ta;", "\tb;")));
  }

  @Test
  void aKForKReplacementIsPairedLineForLine() {
    assertEquals(List.of(new Hunk(1, 2, 1, 2), new Hunk(2, 3, 2, 3)),
        LineEdits.between(List.of("{", "a();", "b();", "}"), List.of("{", "x();", "y();", "}")));
  }

  @Test
  void joinedLinesStayOneHunk() {
    assertEquals(List.of(new Hunk(0, 2, 0, 1)), LineEdits.between(List.of("f(a,", "  b);"), List.of("f(a, b);")));
  }

  @Test
  void aSplitLineNextToAReindentedLine() {
    assertEquals(List.of(new Hunk(1, 2, 1, 3), new Hunk(2, 3, 3, 4)),
        LineEdits.between(List.of("{", "  int a;int b;", "  int c;"), List.of("{", "\tint a;", "\tint b;", "\tint c;")));
  }

  @Test
  void hunksReplayToTheNewLines() {
    Random random = new Random(11);
    for (int n = 0; n < 2000; n++) {
      List<String> a = randomLines(random);
      List<String> b = randomLines(random);
      List<Hunk> hunks = LineEdits.between(a, b);
      List<String> replay = new ArrayList<>(a);
      for (int k = hunks.size() - 1; k >= 0; k--) {
        Hunk h = hunks.get(k);
        if (k > 0) {
          assertTrue(hunks.get(k - 1).end() <= h.start(), "ascending, not overlapping: " + hunks);
        }
        replay.subList(h.start(), h.end()).clear();
        replay.addAll(h.start(), b.subList(h.newStart(), h.newEnd()));
      }
      assertEquals(b, replay, a + " -> " + b);
    }
  }

  private static List<String> randomLines(Random random) {
    String[] pool = {"{", "}", "  a;", "\ta;", "b;", "", "  c(1);", "c(1);"};
    List<String> out = new ArrayList<>();
    for (int i = random.nextInt(9); i > 0; i--) {
      out.add(pool[random.nextInt(pool.length)]);
    }
    return out;
  }
}
