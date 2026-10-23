package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.members.MembersResolver;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.resolve.SimpleTextResolver;
import net.aftergit.merge.jdt.JavaMembersResolver;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design §1 with the JDT parser, on the line merges git really makes. */
class JavaMembersResolverTest {
  static final String BASE = "package p;\n\npublic class M {\n  int first;\n  int second;\n\n  int value() {\n"
      + "    return first + second;\n  }\n}\n";
  static final String TWICE = "\n  int twice() {\n    return first * 2;\n  }\n";
  static final String HALF = "\n  int half() {\n    return second / 2;\n  }\n";
  static final String VALUE = "\n  int value() {\n    return first + second;\n  }\n";

  /** {@code base} with {@code added} at the end of class M. */
  static String with(String base, String... added) {
    return base.replace("  }\n}\n", "  }\n" + String.join("", added) + "}\n");
  }

  static MergeModel resolve(String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        new ResolverPipeline(List.of(new JavaMembersResolver(), new SimpleTextResolver())).forFile("M.java")).model();
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  @Test
  void methodsAddedOnBothSidesAreBothTaken() {
    MergeModel m = resolve(BASE, with(BASE, TWICE), with(BASE, HALF));
    assertEquals(with(BASE, TWICE, HALF), text(m));
    MergeBlock b = m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow();
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(MembersResolver.ID, b.resolvedBy());
  }

  @Test
  void javadocCommentsAndAnnotationsTravelWithTheirMethod() {
    String twice = "\n  /** Twice. */\n  @Deprecated\n  int twice() {\n    return first * 2;\n  }\n";
    String half = "\n  // half of it\n  int half() {\n    return second / 2;\n  }\n";
    assertEquals(with(BASE, twice, half), text(resolve(BASE, with(BASE, twice), with(BASE, half))));
  }

  @Test
  void overloadsAreDifferentMembers() {
    String a = "\n  int twice(int k) {\n    return k * 2;\n  }\n";
    String b = "\n  int twice(long k) {\n    return (int) k * 2;\n  }\n";
    assertEquals(with(BASE, a, b), text(resolve(BASE, with(BASE, a), with(BASE, b))));
  }

  @Test
  void aMethodChangedOnBothSidesStaysAConflict() {
    MergeModel m = resolve(BASE, BASE.replace("first + second", "first * second"),
        BASE.replace("first + second", "first / second"));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void theSameMethodAddedWithDifferentBodiesStaysAConflict() {
    assertEquals(1, resolve(BASE, with(BASE, TWICE), with(BASE, TWICE.replace("first * 2", "second * 2")))
        .unresolvedConflicts());
  }

  @Test
  void aDeletedMethodMeetingAChangeStaysAConflict() {
    assertEquals(1, resolve(BASE, BASE.replace(VALUE, ""), BASE.replace("first + second", "first - second"))
        .unresolvedConflicts());
  }

  @Test
  void aMethodDeletedOnOneSideAndUntouchedOnTheOtherIsDeleted() {
    MergeModel m = resolve(BASE, BASE.replace(VALUE, ""), with(BASE, HALF));
    assertEquals("package p;\n\npublic class M {\n  int first;\n  int second;\n" + HALF + "}\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void initializersDeletedDifferentlyOnBothSidesStayAConflict() {
    String base = "package p;\n\npublic class M {\n  static int x;\n\n  static {\n    x = 1;\n  }\n\n  static {\n"
        + "    x = 2;\n  }\n}\n";
    String ours = base.replace("\n  static {\n    x = 1;\n  }\n", "");
    String theirs = base.replace("\n  static {\n    x = 2;\n  }\n", "");
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        new ResolverPipeline(List.of(new JavaMembersResolver())).forFile("M.java")).model();
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aSyntaxErrorInTheTypeLeavesTheBlockAlone() {
    assertEquals(1, resolve(BASE, with(BASE, TWICE), with(BASE, HALF.replace("second / 2", "second / ")))
        .unresolvedConflicts());
  }

  @Test
  void aBlockOutsideEveryTypeIsLeftAlone() {
    String x = "\n  class X {\n  }\n";
    String y = "\n  class Y {\n  }\n";
    assertEquals(0, resolve(BASE, with(BASE, x), with(BASE, y)).unresolvedConflicts(), "inside M both are taken");
    assertEquals(1, resolve(BASE, BASE + x.replace("\n  ", "\n"), BASE + y.replace("\n  ", "\n"))
        .unresolvedConflicts(), "the same types after M stay a conflict");
  }

  @Test
  void enumConstantsAddedOnBothSides() {
    String e = "package p;\n\nenum Color {\n  RED,\n  GREEN;\n\n  int code() {\n    return ordinal();\n  }\n}\n";
    MergeModel m = resolve(e, e.replace("  GREEN;\n", "  GREEN,\n  BLUE;\n"),
        e.replace("  GREEN;\n", "  GREEN,\n  BLACK;\n"));
    assertEquals(e.replace("  GREEN;\n", "  GREEN,\n  BLUE,\n  BLACK;\n"), text(m));
  }

  @Test
  void twoMembersOnOneLineAreLeftAlone() {
    String base = BASE.replace("  int first;\n  int second;\n", "  int first; int second;\n");
    assertEquals(1, resolve(base, with(base, TWICE), with(base, HALF)).unresolvedConflicts());
  }

  @Test
  void otherFilesAreNotParsed() {
    assertEquals(List.of("simple"), new ResolverPipeline(List.of(new JavaMembersResolver(), new SimpleTextResolver()))
        .forFile("M.txt").ids());
  }
}
