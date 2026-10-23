/*
 * Test cases derived from IntelliJ Community MergeResolveUtilTest,
 * Copyright 2000-2016 JetBrains s.r.o. Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GreedyResolverTest {
  /** Argument order as in IntelliJ tests: base, left, right. */
  private static void test(String base, String left, String right, String expected) {
    assertEquals(Optional.ofNullable(expected), GreedyResolver.tryResolve(left, base, right),
        () -> "base=" + base + " left=" + left + " right=" + right);
  }

  @Test
  void sameAsSimpleOnSimpleCases() {
    test("", "", "", "");
    test("x x x", "x Y x", "x x x", "x Y x");
    test("x x x", "x Y x x", "x x Z x", "x Y x Z x");
    test("x", "x Y", "Z x", "Z x Y");
    test("x x", "x", "Z x x", "Z x");
    test("x x x", "x Y x", "x Z x", null);
    // IDEA-200696 merges this to "version: 2.0.4", a version no side has; AfterGit keeps the conflict
    test("version: 1.0.0", "version: 2.0.0", "version: 1.0.4", null);
  }

  @Test
  void nonFailureConflicts() {
    test("x X x", "x x", "x X Y x", "x Y x");
    test("x X x", "x x", "x Y X x", "x Y x");
    test("x X Y x", "x X x", "x Y x", "x x");
    test("x X Y Z x", "x X x", "x Z x", "x x");
    test("x A B C D E F G H K x", "x C F K x", "x A D H x", "x x");
  }

  @Test
  void prefixInsertionExtensionTakesLonger() {
    // AfterGit extension: one insertion is a prefix of the other
    test("x x", "x Y x", "x Y Z x", "x Y Z x");
    test("x x", "x Y Z x", "x Y x", "x Y Z x");
  }

  @Test
  void suffixInsertionExtensionTakesLonger() {
    test("x x", "x Z x", "x Y Z x", "x Y Z x");
  }

  @Test
  void insertionBeforeAdjacentTokenKeepsItsWhitespace() {
    test("foo\n", "foo bar\n", "foo bar baz\n", "foo bar baz\n");
    test("foo\n", "foo bar\n", "foo\n", "foo bar\n");
    test("foo;\n", "foo bar;\n", "foo bar baz;\n", "foo bar baz;\n");
    test("(a)\n", "(a b)\n", "(a)\n", "(a b)\n");
    test("(a)\n", "( b a)\n", "(a)\n", "( b a)\n");
    test("a b\n", "a b\n", "a b c\n", "a b c\n");
  }

  @Test
  void insertionKeepsMultipleSpacesAndTabs() {
    test("foo\n", "foo  bar\n", "foo  bar  baz\n", "foo  bar  baz\n");
    test("foo\n", "foo\tbar\n", "foo\tbar\tbaz\n", "foo\tbar\tbaz\n");
    test("x  x\n", "x Y  x\n", "x  x\n", "x Y  x\n");
    test("x  x\n", "x  Y x\n", "x  x\n", "x  Y x\n");
  }

  @Test
  void deletionBeforeAdjacentTokenLeavesNoSpace() {
    test("x x\n", "x\n", "x x\n", "x\n");
    test("x X;\n", "x;\n", "x X;\n", "x;\n");
    test("x X;\n", "x;\n", "x X Y;\n", "x Y;\n");
  }

  @Test
  void whitespaceOnlyChangesAreKept() {
    test("a b\n", "a\tb\n", "a b\n", "a\tb\n");
    test("a b\n", "a b\n", "a  b\n", "a  b\n");
    test("a.b\n", "a. b\n", "a.b\n", "a. b\n");
    test("a b\n", "a  b\n", "a b c\n", "a  b c\n");
  }

  @Test
  void ignoreWhitespacesFallbackDropsOnlyConflictingWhitespace() {
    // DEFAULT conflicts on the gap after "a"; the fallback keeps base whitespace there
    assertEquals("a b c\n", GreedyResolver.resolve("a\tb\n", "a b\n", "a  b c\n",
        net.aftergit.merge.core.diff.ComparisonPolicy.IGNORE_WHITESPACES));
    assertEquals("foo bar baz\n", GreedyResolver.resolve("foo bar\n", "foo\n", "foo bar baz\n",
        net.aftergit.merge.core.diff.ComparisonPolicy.IGNORE_WHITESPACES));
  }

  @Test
  void reportedPipelineCaseKeepsSpace() {
    net.aftergit.merge.core.model.MergeModel m = net.aftergit.merge.core.model.HybridInitializer.initialize(
        new net.aftergit.merge.core.model.MergeInput("foo\n", "foo bar\n", "foo bar baz\n", null),
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    assertEquals(1, ResolverPipeline.defaults(true).run(m).resolved());
    assertEquals("foo bar baz\n", ((net.aftergit.merge.core.model.LinesResultDocument) m.document()).text());
  }

  @Test
  void editsInsideOneWordConflict() {
    test("count", "counter", "account", null);
    test("foo", "foo_old", "new_foo", null);
    test("x = 100;", "x = 101;", "x = 200;", null);
    test("abc", "abXc", "aYbc", null);
    test("call(a)", "call(ab)", "call(ba)", null);
  }

  @Test
  void insertionsIntoOneWhitespaceGapConflict() {
    test("a  b", "a X  b", "a  Y b", null);
  }

  @Test
  void whitespaceOnlyEditDoesNotExtendWordEdit() {
    assertEquals(null, GreedyResolver.resolve("a X b\n", "a b\n", "a  b\n",
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT));
    assertEquals(null, GreedyResolver.resolve("a  b\n", "a b\n", "a   b\n",
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT));
  }

  @Test
  void neighbouringWordEditsOnDifferentSidesMerge() {
    test("a b c", "a B c", "a b C", "a B C");
    test("int x = 1;\n", "long x = 1;\n", "int y = 1;\n", "long y = 1;\n");
    test("one two three\n", "one TWO three\n", "one two THREE\n", "one TWO THREE\n");
  }

  @Test
  void deletionNextToNeighbouringEditKeepsSingleSpace() {
    test("a b c d", "a c d", "a b C d", "a C d");
  }

  @Test
  void deletedWordModifiedOnOtherSideConflicts() {
    test("a b c d", "a d", "a b X d", null);
    test("a b c d", "a b X d", "a d", null);
  }

  @Test
  void extensionOnlyOnTokenBoundary() {
    test("x y", "x count y", "x account y", null);
    test("x\n", "x count\n", "x counter\n", null);
  }

  @Test
  void ignoreWhitespacesFallbackKeepsEditsThatChangeTokens() {
    test("x x a b c", "x Y x a bc", "x Y Z x a b C", null);
    test("x x int a;", "x Y x inta;", "x Y Z x int b;", null);
    test("x x a b", "x Y x a\nb", "x Y Z x a B", null);
  }

  @Test
  void sharedExtraWhitespaceBetweenEditsIsNotDuplicated() {
    test("a b c", "a B  c", "a b  C", null);
    test("int x = 1;", "long  x = 1;", "int  y = 1;", null);
    test("a b c", "a B  c", "a  b  C", null);
  }

  /** Every policy and the fallback either give up or give a result the oracle accepts, never {@code bad}. */
  private static void assertSafe(String base, String left, String right, String bad) {
    for (net.aftergit.merge.core.diff.ComparisonPolicy p : net.aftergit.merge.core.diff.ComparisonPolicy.values()) {
      if (p == net.aftergit.merge.core.diff.ComparisonPolicy.TRIM_WHITESPACES) {
        continue;
      }
      String r = GreedyResolver.resolve(left, base, right, p);
      org.junit.jupiter.api.Assertions.assertNotEquals(bad, r, p.toString());
      assertEquals(null, MergeOracle.violation(left, base, right, r), p + " -> " + r);
    }
    String r = GreedyResolver.tryResolve(left, base, right).orElse(null);
    org.junit.jupiter.api.Assertions.assertNotEquals(bad, r);
    assertEquals(null, MergeOracle.violation(left, base, right, r), "fallback -> " + r);
  }

  @Test
  void differentTrailingWhitespaceNeverGluesWords() {
    // the sides' fragments covered different base ranges and one side's text replaced both
    assertEquals(null, GreedyResolver.resolve("static  long  x;", "static int x;", "static\tlong x;",
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT));
    // the whitespace-insensitive pass sees one edit made by both sides and takes the shorter text whole
    test("static int x;", "static  long  x;", "static\tlong x;", "static\tlong x;");
    test("a b c", "a  X  c", "a\tX c", "a\tX c");
    assertSafe("static int x;", "static  long  x;", "static\tlong x;", "static\tlongx;");
    assertSafe("a b c", "a  X  c", "a\tX c", "a\tXc");
    assertSafe("a a  (", "a\tb a  (\t", "a X \tb (", "aX \tb (\t");
    assertSafe("(\nX\t)", "(X\t)\nab", "a\nX\t)  ", "aX\t)\nab");
    assertSafe("\ta \nab", "\t \nX\nab", " a ab", "\t \nXab");
  }

  @Test
  void whitespaceDeletedNextToOtherSidesInsertionNeverGluesWords() {
    assertEquals(null, GreedyResolver.resolve("foo(x)", "foo (x)", "foo bar(x)",
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT));
    // the whitespace-insensitive fallback drops the conflicting whitespace edit instead
    test("foo (x)", "foo(x)", "foo bar(x)", "foo bar(x)");
    test("return (a);", "return(a);", "return b (a);", "return b (a);");
    test("x = 1;", "x= 1;", "x y= 1;", "x y= 1;");
    test("a ) ", "a) ", "a ab ", "a ab ");
    assertSafe("foo (x)", "foo(x)", "foo bar(x)", "foobar(x)");
    assertSafe("return (a);", "return(a);", "return b (a);", "returnb (a);");
    assertSafe("x = 1;", "x= 1;", "x y= 1;", "xy= 1;");
    assertSafe("a ) ", "a) ", "a ab ", "aab ");
    assertSafe(";\tX", " a;\tX", "X\tX\na", " aX\tX\na");
  }

  @Test
  void deletionMeetingAdjacentEditAddsNoSpace() {
    assertEquals(null, GreedyResolver.resolve("a X b)", "a X)", "a b)",
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT));
    test("a X)", "a X b)", "a b)", "a b)");
    test("a X)", "a b)", "a X b)", "a b)");
  }

  @Test
  void whitespaceEditsInDifferentGapsBothKept() {
    test("  y = f(a, b)", "  y = f(a,b)", "    y = f(a, b)", "    y = f(a,b)");
    test("if (x) {", "if(x) {", "if (x)  {", "if(x)  {");
  }

  @Test
  void overlappingDeletionsAroundLoneJoinersAreUnited() {
    // a '.', '-' or ':' that joins no words is no compound token
    test("a: 1\nb: 2\nc: 3\n", "a: 1\nc: 3\n", "a: 1\n", "a: 1\n");
    test("- one\n- two\n- three\n", "- one\n- three\n", "- one\n", "- one\n");
    test("x = 1 - 2 - 3;", "x = 1 - 3;", "x = 1;", "x = 1;");
  }

  @Test
  void overlappingDeletionsOfAWholeQualifiedNameAreUnited() {
    test("foo();\nbar.baz();\nqux();\n", "foo();\nqux();\n", "foo();\n", "foo();\n");
  }
}
