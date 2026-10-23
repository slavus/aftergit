package net.aftergit.merge.core.merge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WordMergeTest {
  @Test
  void separateInsertionsGiveTwoFragments() {
    List<MergeRange> f = WordMerge.threeWay("x Y x x", "x x x", "x x Z x");
    assertEquals(2, f.size());
    assertEquals(" Y ", "x Y x x".substring(f.get(0).start1(), f.get(0).end1()));
    assertEquals(" ", "x x x".substring(f.get(0).start2(), f.get(0).end2()));
  }

  @Test
  void supplementaryCharactersStayWhole() {
    String left = "x 😁 x";
    String base = "x 😀 x";
    List<MergeRange> f = WordMerge.threeWay(left, base, base);
    assertEquals(1, f.size());
    assertEquals(" 😁 ", left.substring(f.get(0).start1(), f.get(0).end1()));
    assertEquals(" 😀 ", base.substring(f.get(0).start2(), f.get(0).end2()));
  }

  @Test
  void twoWayDeletionAbsorbsTrailingWhitespace() {
    List<WordMerge.Fragment> f = WordMerge.twoWay("x X x", "x x");
    assertEquals(List.of(new WordMerge.Fragment(2, 4, 2, 2)), f);
  }

  @Test
  void twoWayInsertionAtEndAbsorbsLeadingWhitespace() {
    List<WordMerge.Fragment> f = WordMerge.twoWay("x", "x Y");
    assertEquals(List.of(new WordMerge.Fragment(1, 1, 1, 3)), f);
  }

  @Test
  void twoWayInsertionBeforeAdjacentTokenTakesLeadingWhitespace() {
    assertEquals(List.of(new WordMerge.Fragment(3, 3, 3, 7)), WordMerge.twoWay("foo\n", "foo bar\n"));
  }

  @Test
  void twoWayDeletionBeforeAdjacentTokenTakesLeadingWhitespace() {
    assertEquals(List.of(new WordMerge.Fragment(1, 3, 1, 1)), WordMerge.twoWay("x x;", "x;"));
  }

  @Test
  void twoWayReportsWhitespaceOnlyChangeUnlessIgnored() {
    assertEquals(List.of(new WordMerge.Fragment(1, 2, 1, 2)), WordMerge.twoWay("a b", "a\tb"));
    assertEquals(List.of(), WordMerge.twoWay("a b", "a\tb",
        net.aftergit.merge.core.diff.ComparisonPolicy.IGNORE_WHITESPACES));
  }

  @Test
  void twoWayReplacementNeverSplitsATokenOrSurrogatePair() {
    assertEquals(List.of(new WordMerge.Fragment(0, 5, 0, 7)), WordMerge.twoWay("count", "counter"));
    assertEquals(List.of(new WordMerge.Fragment(2, 4, 2, 4)), WordMerge.twoWay("x \uD83D\uDE00 x", "x \uD83D\uDE01 x"));
  }

  @Test
  void twoWayReplacementDropsCommonWhitespace() {
    assertEquals(List.of(new WordMerge.Fragment(2, 3, 2, 3)), WordMerge.twoWay("a b c", "a B c"));
    assertEquals(List.of(new WordMerge.Fragment(1, 3, 1, 5)), WordMerge.twoWay("a  b", "a X  b"));
  }

  @Test
  void tooManyTokensThrows() {
    String huge = "a ".repeat(WordMerge.MAX_TOKENS + 1);
    assertThrows(WordMerge.TooBigException.class, () -> WordMerge.threeWay(huge, "a", "a"));
  }
}
