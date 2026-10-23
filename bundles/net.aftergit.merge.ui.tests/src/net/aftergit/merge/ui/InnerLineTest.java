package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.WordMerge;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

class InnerLineTest {
  static MergeModel model() {
    return HybridInitializer.initialize(new MergeInput("a\nx = 0;\nc\n", "a\nx = 1;\nc\n", "a\nx = 2;\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
  }

  @Test
  void changedWordsOfAConflict() {
    InnerLine.Ranges r = InnerLine.compute(model(), 0);
    assertArrayEquals(new int[] {4, 1}, r.left().get(0));
    assertArrayEquals(new int[] {4, 1}, r.base().get(0));
    assertArrayEquals(new int[] {4, 1}, r.right().get(0));
  }

  @Test
  void aResolvedBlockComparesEachSideWithTheResult() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    InnerLine.Ranges r = InnerLine.compute(m, 0);
    assertTrue(r.left().isEmpty(), "ours is the result: nothing differs");
    assertArrayEquals(new int[] {4, 1}, r.right().get(0));
    assertArrayEquals(new int[] {4, 1}, r.base().get(0), "the result against theirs");
  }

  @Test
  void anIgnoredBlockHasNoRanges() {
    MergeModel m = model();
    m.ignore(0);
    assertTrue(InnerLine.compute(m, 0).isEmpty());
  }

  private static final String OURS = "int total = price * qty + tax;\n";
  private static final String THEIRS = "long total = price * qty;\n";

  private static int[] at(String text, String words) {
    int i = text.indexOf(words);
    assertTrue(i >= 0, words);
    return new int[] {i, words.length()};
  }

  private static void assertRanges(List<int[]> actual, int[]... expected) {
    assertEquals(expected.length, actual.size(), () -> "ranges " + actual.stream().map(Arrays::toString).toList());
    for (int i = 0; i < expected.length; i++) {
      assertArrayEquals(expected[i], actual.get(i));
    }
  }

  @Test
  void takeLeftHighlightsOnlyWhatDiffersFromTheResult() {
    InnerLine.Ranges r = InnerLine.againstResult(OURS, OURS, THEIRS);
    assertRanges(r.left());
    assertEquals(List.of(), r.leftMarks(), "ours is the result");
    assertRanges(r.right(), at(THEIRS, "long"));
    assertEquals(List.of(THEIRS.indexOf(';')), r.rightMarks(), "theirs lacks + tax");
    assertRanges(r.base(), at(OURS, "int"), at(OURS, "+ tax"));
    assertEquals(List.of(), r.baseMarks());
  }

  @Test
  void takeRightMarksWhereTheResultLacksWhatOursHas() {
    InnerLine.Ranges r = InnerLine.againstResult(OURS, THEIRS, THEIRS);
    assertRanges(r.left(), at(OURS, "int"), at(OURS, "+ tax"));
    assertEquals(List.of(), r.leftMarks());
    assertRanges(r.right());
    assertRanges(r.base(), at(THEIRS, "long"));
    assertEquals(List.of(THEIRS.indexOf(';')), r.baseMarks());
  }

  @Test
  void takeBothOfBothEditsInOneLine() {
    String both = "long total = price * qty + tax;\n";
    InnerLine.Ranges r = InnerLine.againstResult(OURS, both, THEIRS);
    assertRanges(r.left(), at(OURS, "int"));
    assertRanges(r.right());
    assertEquals(List.of(THEIRS.indexOf(';')), r.rightMarks());
    assertRanges(r.base(), at(both, "long"), at(both, "+ tax"));
    assertEquals(List.of(), r.baseMarks());
  }

  @Test
  void anEditedResultDiffersFromBothSides() {
    String edited = "double total = price * qty * 1.25;\n";
    InnerLine.Ranges r = InnerLine.againstResult(OURS, edited, THEIRS);
    assertRanges(r.left(), at(OURS, "int"), at(OURS, "+ tax"));
    assertRanges(r.right(), at(THEIRS, "long"));
    assertEquals(List.of(THEIRS.indexOf(';')), r.rightMarks());
    assertRanges(r.base(), at(edited, "double"), at(edited, "* 1.25"));
    assertEquals(List.of(), r.baseMarks());
  }

  @Test
  void anEmptyLineIsOneMarkAndTheResultsWordsAreHighlighted() {
    InnerLine.Ranges r = InnerLine.againstResult("\n", "x = 1;\n", "x = 1;\n");
    assertRanges(r.left());
    assertEquals(List.of(0), r.leftMarks(), "on the empty line itself, not on the next one");
    assertRanges(r.base(), new int[] {0, 6});
    assertRanges(r.right());
  }

  @Test
  void aMultiLineBlockHasRangesOnEachLine() {
    String ours = "x = 1;\ny = 5;\n";
    String theirs = "x = 2;\ny = 6;\n";
    InnerLine.Ranges r = InnerLine.againstResult(ours, ours, theirs);
    assertRanges(r.right(), new int[] {4, 1}, new int[] {11, 1});
    assertRanges(r.base(), new int[] {4, 1}, new int[] {11, 1});
    assertRanges(r.left());
  }

  @Test
  void takeBothOfWholeLinesMarksTheEndOfTheShorterSideInsideTheBlock() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nx = 0;\nc\n", "a\nx = 1;\nc\n",
        "a\nx = 2;\nc\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    m.takeBoth(0, ThreeSide.LEFT);
    assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    InnerLine.Ranges r = InnerLine.compute(m, 0);
    for (int mark : r.leftMarks()) {
      assertTrue(mark < "x = 1;\n".length() && mark >= 0, "mark " + mark + " stays on the block's line");
    }
    assertFalse(r.leftMarks().isEmpty(), "ours lacks theirs' line");
    assertFalse(r.base().isEmpty());
  }

  @Test
  void aTooBigBlockHasNoRanges() {
    String big = "w ".repeat(WordMerge.MAX_TOKENS) + "\n";
    assertTrue(InnerLine.againstResult(big, "x\n", "x\n").isEmpty());
  }

  @Test
  void textEqualToTheResultHasNothing() {
    assertTrue(InnerLine.againstResult("x = 1;\n", "x = 1;\n", "x = 1;\n").isEmpty());
  }

  @Test
  void sideWordsOfAResolvedBlockAgainstTheBase() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    var left = InnerLine.sideWords(m, 0, ThreeSide.LEFT);
    assertEquals(1, left.size());
    assertArrayEquals(new int[] {4, 1}, left.get(0));
  }

  @Test
  void anAddedSideIsOneRangeOverItsWords() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nc\n", "a\nb b\nc\n", "a\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    var left = InnerLine.sideWords(m, 0, ThreeSide.LEFT);
    assertEquals(1, left.size());
    assertArrayEquals(new int[] {0, 3}, left.get(0), "the words, without the line end");
    assertTrue(InnerLine.sideWords(m, 0, ThreeSide.RIGHT).isEmpty(), "the unchanged side has no words");
  }
}
