package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A run of letters and digits joined by '.', '-' or ':' without whitespace (a version, a qualified
 * name, Maven coordinates) is one unit: the word resolvers never combine edits of both sides inside
 * it, so they never make up a version or a name that no side has.
 */
class CompoundTokenTest {
  /** Both word resolvers, argument order base, left, right. */
  private static void test(String base, String left, String right, String expected) {
    assertEquals(Optional.ofNullable(expected), SimpleResolver.tryResolve(left, base, right),
        () -> "simple: base=" + base + " left=" + left + " right=" + right);
    assertEquals(Optional.ofNullable(expected), GreedyResolver.tryResolve(left, base, right),
        () -> "greedy: base=" + base + " left=" + left + " right=" + right);
  }

  @Test
  void versionChangedInDifferentPartsByBothSidesStaysAConflict() {
    test("    <version>6.6.0</version>\n", "    <version>6.7.0</version>\n", "    <version>6.6.1</version>\n", null);
    test("version: 1.0.0", "version: 2.0.0", "version: 1.0.4", null);
  }

  @Test
  void manifestBundleVersionChangedByBothSidesStaysAConflict() {
    test("Bundle-Version: 6.6.0.qualifier\n", "Bundle-Version: 6.7.0.qualifier\n",
        "Bundle-Version: 6.6.1.qualifier\n", null);
  }

  @Test
  void qualifiedNameChangedInDifferentSegmentsStaysAConflict() {
    test("import org.eclipse.a.Foo;\n", "import org.eclipse.b.Foo;\n", "import org.eclipse.a.Bar;\n", null);
    test("x = a.b.c;\n", "x = z.b.c;\n", "x = a.b.y;\n", null);
    test("x = a.b;\n", "x = y.z;\n", "x = a.c;\n", null);
  }

  @Test
  void coordinatesChangedInDifferentPartsStayAConflict() {
    test("dep org.foo:bar:1.0\n", "dep org.foo:baz:1.0\n", "dep org.foo:bar:2.0\n", null);
  }

  @Test
  void snapshotVersionAndReleaseStayAConflict() {
    test("<version>1.2.3-SNAPSHOT</version>", "<version>1.2.4-SNAPSHOT</version>", "<version>1.2.3</version>", null);
    test("<version>1.2.3-SNAPSHOT</version>", "<version>1.2.4-SNAPSHOT</version>", "<version>1.3.3-SNAPSHOT</version>",
        null);
  }

  @Test
  void extendingTheSameCompoundDifferentlyStaysAConflict() {
    test("x a.b y", "x a.b.c y", "x a.b.d y", null);
    test("x a.b y", "x a.b.c y", "x a.z y", null);
    test("x a.b y", "x 1.a.b y", "x a.z y", null);
  }

  @Test
  void insertingNextToACompoundWithoutExtendingItStillMerges() {
    test("a.b.c;\n", "a.b.c(x);\n", "z.b.c;\n", "z.b.c(x);\n");
  }

  @Test
  void editsInDifferentCompoundsStillMerge() {
    test("a.b = 1.0\n", "a.c = 1.0\n", "a.b = 2.0\n", "a.c = 2.0\n");
    test("<version>6.6.0</version> <x>a.b</x>", "<version>6.7.0</version> <x>a.b</x>",
        "<version>6.6.0</version> <x>a.c</x>", "<version>6.7.0</version> <x>a.c</x>");
  }

  @Test
  void oneSideChangingACompoundStillMerges() {
    test("x 6.6.0\n", "x 6.7.0\n", "y 6.6.0\n", "y 6.7.0\n");
    test("v 6.6.0", "v 6.7.0", "v 6.6.0", "v 6.7.0");
  }

  @Test
  void theSameChangeOfACompoundOnBothSidesStillMerges() {
    test("x 6.6.0 y", "x 6.7.0 z", "w 6.7.0 y", "w 6.7.0 z");
  }
}
