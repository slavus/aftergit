package net.aftergit.merge.core.diff;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ComparisonPolicyTest {
  @Test
  void defaultIsExact() {
    assertTrue(ComparisonPolicy.DEFAULT.equalTexts("a b", "a b"));
    assertFalse(ComparisonPolicy.DEFAULT.equalTexts("a b", "a  b"));
  }

  @Test
  void trimIgnoresOnlyOuterWhitespace() {
    assertTrue(ComparisonPolicy.TRIM_WHITESPACES.equalTexts("  a b ", "a b"));
    assertFalse(ComparisonPolicy.TRIM_WHITESPACES.equalTexts("a b", "a  b"));
  }

  @Test
  void ignoreRemovesAllWhitespace() {
    assertTrue(ComparisonPolicy.IGNORE_WHITESPACES.equalTexts(" a \t b\n", "ab"));
    assertEquals("ab", ComparisonPolicy.IGNORE_WHITESPACES.normalize(" a b "));
  }
}
