/*
 * Test cases derived from IntelliJ Community MergeResolveUtilTest,
 * Copyright 2000-2016 JetBrains s.r.o. Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.WordMerge;
import org.junit.jupiter.api.Test;

class SimpleResolverTest {
  /** Argument order as in IntelliJ tests: base, left, right. */
  private static void test(String base, String left, String right, String expected) {
    assertEquals(Optional.ofNullable(expected), SimpleResolver.tryResolve(left, base, right),
        () -> "base=" + base + " left=" + left + " right=" + right);
  }

  @Test
  void simple() {
    test("", "", "", "");
    test("x x x", "x x x", "x x x", "x x x");
    test("x x x", "x Y x", "x x x", "x Y x");
    test("x x", "x x", "x Y x", "x Y x");
    test("x X x", "x x", "x X x", "x x");
  }

  @Test
  void sameModification() {
    test("x x x", "x Y x", "x Y x", "x Y x");
    test("x x", "x Y x", "x Y x", "x Y x");
    test("x X x", "x x", "x x", "x x");
  }

  @Test
  void nonConflictingChanges() {
    test("x x x", "x Y x x", "x x Z x", "x Y x Z x");
    test("x", "x Y", "Z x", "Z x Y");
    test("x x", "x", "Z x x", "Z x");
  }

  @Test
  void failure() {
    test("x x x", "x Y x", "x Z x", null);
    test("x x", "x Y x", "x Z x", null);
  }

  @Test
  void versionNumbers() {
    // IDEA-200696 merges this to "version: 2.0.4", a version no side has; AfterGit keeps the conflict
    test("version: 1.0.0", "version: 2.0.0", "version: 1.0.4", null);
  }

  @Test
  void calcDivideConflictIsResolvable() {
    String base = "        return (double) a / b;\n";
    String left = "        return b == 0 ? Double.NaN : (double) a / b;\n";
    String right = "        if (b == 0) {\n            throw new ArithmeticException(\"div by zero\");\n        }\n"
        + "        return (double) a / b;\n";
    Optional<String> r = SimpleResolver.tryResolve(left, base, right);
    assertTrue(r.isPresent());
    assertTrue(r.get().contains("throw new ArithmeticException"));
    assertTrue(r.get().contains("Double.NaN"));
  }

  @Test
  void givesUpOnHugeInput() {
    String huge = "a ".repeat(WordMerge.MAX_TOKENS + 10);
    long t0 = System.nanoTime();
    assertEquals(Optional.empty(), SimpleResolver.tryResolve(huge + "b", huge, huge + "c"));
    assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "must give up quickly");
  }

  @Test
  void whitespaceInsensitivePassResolvesWhatTheDefaultPassCannot() {
    // left only re-spaces the region right changes: a real conflict for the default pass
    assertNull(SimpleResolver.resolve("foo(a, b)", "foo(a,b)", "foo(a,c)", ComparisonPolicy.DEFAULT));
    test("foo(a,b)", "foo(a, b)", "foo(a,c)", "foo(a,c)");
  }

  @Test
  void ignoredWhitespaceMustNotChangeTokens() {
    test("a b c", "a bc", "a b C", null);
    test("x", "X", "x\n", null);
    assertEquals(null, SimpleResolver.resolve("a bc", "a b c", "a b C", ComparisonPolicy.IGNORE_WHITESPACES));
  }

  @Test
  void whitespaceEditsInDifferentGapsOfOneStretchAreBothKept() {
    // re-indent on one side, re-spacing inside the line on the other
    test("  y = f(a, b)", "  y = f(a,b)", "    y = f(a, b)", "    y = f(a,b)");
    test("  y = f(a, b)", "    y = f(a, b)", "  y = f(a,b)", "    y = f(a,b)");
    test("if (x) {", "if (x)  {", "if(x) {", "if(x)  {");
    test("if (x) {", "if(x) {", "if (x)  {", "if(x)  {");
    test("a b\nc d\n", "a b\n  c d\n", "a  b\nc d\n", "a  b\n  c d\n");
  }

  @Test
  void differentWhitespaceInOneGapConflicts() {
    assertNull(SimpleResolver.resolve("a\tb", "a b", "a  b", ComparisonPolicy.DEFAULT));
    // the whitespace-insensitive pass keeps base whitespace for that gap only
    test("a b c", "a\tb\tc", "a  b c", "a b\tc");
  }
}
