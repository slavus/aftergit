package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.members.Member;
import net.aftergit.merge.core.members.MembersResolver;
import net.aftergit.merge.core.members.TypeBody;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

/** The corpus stand-in for the JDT member locator keys members as the resolver does. */
class JavacMemberLocatorTest {
  static final String SRC = String.join("\n",
      "package p;",
      "",
      "public class A {",
      "  int count;",
      "",
      "  /** Doubles. */",
      "  @Deprecated",
      "  int twice(java.util.List<String> xs, int... more) {",
      "    return count * 2;",
      "  }",
      "",
      "  // the size",
      "  int size;",
      "",
      "  static {",
      "  }",
      "",
      "  A(String s[]) {",
      "  }",
      "",
      "  class Inner {",
      "    void run() {",
      "    }",
      "  }",
      "}",
      "");

  @Test
  void membersAreKeyedLikeTheResolversLocator() {
    assertEquals(new TypeBody(3, 24, List.of(
        new Member(List.of("field:count"), 3, 4),
        new Member(List.of("method:twice(java.util.List,int[])"), 5, 10),
        new Member(List.of("field:size"), 11, 13),
        new Member(List.of("init:0"), 14, 16),
        new Member(List.of("ctor:(String[])"), 17, 19),
        new Member(List.of("type:Inner"), 20, 24))), JavacMemberLocator.INSTANCE.locate(SRC, 8, 9).orElseThrow());
  }

  @Test
  void enumConstantsComeFirst() {
    String e = "enum E {\n  RED,\n  GREEN;\n\n  int x;\n}\n";
    assertEquals(new TypeBody(1, 5, List.of(new Member(List.of("const:RED"), 1, 2),
        new Member(List.of("const:GREEN"), 2, 3), new Member(List.of("field:x"), 4, 5))),
        JavacMemberLocator.INSTANCE.locate(e, 2, 3).orElseThrow());
  }

  @Test
  void whatTheStandInDoesNotHandleFindsNothing() {
    assertEquals(Optional.empty(), JavacMemberLocator.INSTANCE.locate(SRC.replace("count * 2", "count * "), 8, 9));
    assertEquals(Optional.empty(), JavacMemberLocator.INSTANCE.locate("class A {\n  int a, b;\n}\n", 1, 2));
    assertEquals(Optional.empty(), JavacMemberLocator.INSTANCE.locate("record R(int a) {\n  int x() {\n    return a;\n  }\n}\n", 1, 2));
  }

  @Test
  void initializersDeletedDifferentlyOnBothSidesStayAConflict() {
    String base = "package p;\n\npublic class A {\n  static int x;\n\n  static {\n    x = 1;\n  }\n\n  static {\n"
        + "    x = 2;\n  }\n}\n";
    String ours = base.replace("\n  static {\n    x = 1;\n  }\n", "");
    String theirs = base.replace("\n  static {\n    x = 2;\n  }\n", "");
    assertEquals(1, HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        new ResolverPipeline(List.of(new MembersResolver(JavacMemberLocator.INSTANCE))).forFile("A.java")).model()
        .unresolvedConflicts());
  }
}
