package net.aftergit.merge.core.merge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class LineMergerTest {
  static String fixture(String name) {
    try (InputStream in = LineMergerTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void calcHasTwoConflictsAndFiveNonConflictingChanges() {
    List<MergeChange> changes = LineMerger.merge(LineText.of(fixture("calc/ours.txt")),
        LineText.of(fixture("calc/base.txt")), LineText.of(fixture("calc/theirs.txt")), ComparisonPolicy.DEFAULT);
    long conflicts = changes.stream().filter(c -> c.type().isConflict()).count();
    assertEquals(2, conflicts);
    assertEquals(7, changes.size());
    // first conflict is the precision line: base line index 9 ("    private int precision = 2;")
    MergeChange first = changes.stream().filter(c -> c.type().isConflict()).findFirst().orElseThrow();
    assertEquals(9, first.range().start2());
    assertEquals(10, first.range().end2());
  }

  @Test
  void addAddWithEmptyBase() {
    List<MergeChange> changes = LineMerger.merge(LineText.of("a\nb\n"), LineText.of(""), LineText.of("a\nc\n"),
        ComparisonPolicy.DEFAULT);
    assertEquals(1, changes.size());
    assertTrue(changes.get(0).type().isConflict());
    assertEquals(new MergeRange(0, 2, 0, 0, 0, 2), changes.get(0).range());
  }

  @Test
  void identicalChangeOnBothSidesIsNotAConflict() {
    List<MergeChange> changes = LineMerger.merge(LineText.of("a\nX\nc\n"), LineText.of("a\nb\nc\n"),
        LineText.of("a\nX\nc\n"), ComparisonPolicy.DEFAULT);
    assertEquals(1, changes.size());
    assertEquals(new ConflictType(ConflictType.Kind.MODIFIED, true, true), changes.get(0).type());
  }

  @Test
  void whitespaceOnlyChangesUnderIgnorePolicyAreModifiedNotConflict() {
    List<MergeChange> changes = LineMerger.merge(LineText.of("x\n y \nz\n"), LineText.of("x\ny\nz\n"),
        LineText.of("x\n  y\n  z\n"), ComparisonPolicy.IGNORE_WHITESPACES);
    assertEquals(1, changes.size());
    assertEquals(ConflictType.Kind.MODIFIED, changes.get(0).type().kind());
    assertEquals(new MergeRange(1, 3, 1, 3, 1, 3), changes.get(0).range());
  }
}
