package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.members.Member;
import net.aftergit.merge.core.members.TypeBody;
import net.aftergit.merge.jdt.JavaMemberLocator;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design §1: the members around a block, parsed with the JDT parser without bindings. */
class JavaMemberLocatorTest {
  static final String SRC = String.join("\n",
      "package p;",                                            // 0
      "",                                                      // 1
      "public class A {",                                      // 2
      "  int count;",                                          // 3
      "",                                                      // 4
      "  /** Doubles. */",                                     // 5
      "  @Deprecated",                                         // 6
      "  int twice(java.util.List<String> xs, int... more) {", // 7
      "    return count * 2;",                                 // 8
      "  }",                                                   // 9
      "",                                                      // 10
      "  // the size",                                         // 11
      "  int a, b;",                                           // 12
      "",                                                      // 13
      "  static {",                                            // 14
      "  }",                                                   // 15
      "",                                                      // 16
      "  A(String s[]) {",                                     // 17
      "  }",                                                   // 18
      "",                                                      // 19
      "  class Inner {",                                       // 20
      "    void run() {",                                      // 21
      "    }",                                                 // 22
      "  }",                                                   // 23
      "}",                                                     // 24
      "");

  @AfterEach
  void resetHook() {
    JdtTestHooks.setMemberLocatorHook(null);
  }

  static Optional<TypeBody> locate(String text, int from, int to) {
    return JavaMemberLocator.INSTANCE.locate(text, from, to);
  }

  @Test
  void membersAreKeyedAndCarryTheirJavadocCommentsAndAnnotations() {
    assertEquals(new TypeBody(3, 24, List.of(
        new Member(List.of("field:count"), 3, 4),
        new Member(List.of("method:twice(java.util.List,int[])"), 5, 10),
        new Member(List.of("field:a", "field:b"), 11, 13),
        new Member(List.of("init:0"), 14, 16),
        new Member(List.of("ctor:(String[])"), 17, 19),
        new Member(List.of("type:Inner"), 20, 24))), locate(SRC, 8, 9).orElseThrow());
  }

  @Test
  void theInnermostTypeBodyHoldsTheBlock() {
    assertEquals(new TypeBody(21, 23, List.of(new Member(List.of("method:run()"), 21, 23))),
        locate(SRC, 22, 22).orElseThrow());
  }

  @Test
  void linesOutsideEveryTypeBodyFindNothing() {
    assertEquals(Optional.empty(), locate(SRC, 0, 1));
    assertEquals(Optional.empty(), locate(SRC, 23, 25), "the closing brace of A is not inside its body");
  }

  @Test
  void aSyntaxErrorInTheTypeFindsNothing() {
    assertEquals(Optional.empty(), locate(SRC.replace("count * 2", "count * "), 8, 9));
  }

  @Test
  void enumConstantsComeFirst() {
    String e = "enum E {\n  RED,\n  GREEN;\n\n  int x;\n}\n";
    assertEquals(new TypeBody(1, 5, List.of(new Member(List.of("const:RED"), 1, 2),
        new Member(List.of("const:GREEN"), 2, 3), new Member(List.of("field:x"), 4, 5))), locate(e, 2, 3).orElseThrow());
  }

  @Test
  void aConstantWithACommentAfterItsSeparatorFindsNothing() {
    assertEquals(Optional.empty(), locate("enum E {\n  RED, // warm\n  GREEN\n}\n", 2, 3));
  }

  @Test
  void recordsAndTheirCompactConstructor() {
    String r = "record R(int a) {\n  R {\n  }\n\n  int twice() {\n    return a * 2;\n  }\n}\n";
    assertEquals(new TypeBody(1, 7, List.of(new Member(List.of("ctor:compact"), 1, 3),
        new Member(List.of("method:twice()"), 4, 7))), locate(r, 5, 6).orElseThrow());
  }

  @Test
  void twoMembersOnOneLineFindNothing() {
    assertEquals(Optional.empty(), locate("class A {\n  int a; int b;\n}\n", 1, 2));
  }

  @Test
  void aLoneCarriageReturnFindsNothing() {
    assertEquals(Optional.empty(), locate("class A {\r  int x;\n}\n", 1, 1));
  }

  @Test
  void aSyntaxErrorAnywhereInTheTopLevelTypeFindsNothing() {
    assertEquals(Optional.empty(), locate(SRC.replace("count * 2", "count * "), 22, 22),
        "the error is in A, outside Inner, which holds the block");
  }

  @Test
  void enumConstantsWithBodiesAreOneMemberAndNoBody() {
    String e = String.join("\n",
        "enum E {",          // 0
        "  RED {",           // 1
        "    int x() {",     // 2
        "      return 1;",   // 3
        "    }",             // 4
        "  },",              // 5
        "  GREEN;",          // 6
        "",                  // 7
        "  int x() {",       // 8
        "    return 0;",     // 9
        "  }",               // 10
        "}",                 // 11
        "");
    TypeBody body = new TypeBody(1, 11, List.of(new Member(List.of("const:RED"), 1, 6),
        new Member(List.of("const:GREEN"), 6, 7), new Member(List.of("method:x()"), 8, 11)));
    assertEquals(body, locate(e, 9, 10).orElseThrow());
    assertEquals(body, locate(e, 3, 4).orElseThrow(), "the body of a constant is not a type body of its own");
  }

  @Test
  void interfaceBodies() {
    String i = String.join("\n",
        "interface I {",                 // 0
        "  int A = 1;",                  // 1
        "",                              // 2
        "  void run();",                 // 3
        "",                              // 4
        "  default int twice(int k) {",  // 5
        "    return k * 2;",             // 6
        "  }",                           // 7
        "}",                             // 8
        "");
    assertEquals(new TypeBody(1, 8, List.of(new Member(List.of("field:A"), 1, 2),
        new Member(List.of("method:run()"), 3, 4), new Member(List.of("method:twice(int)"), 5, 8))),
        locate(i, 6, 7).orElseThrow());
  }

  @Test
  void localAndAnonymousClassesAreNoBodiesOfTheirOwn() {
    String a = String.join("\n",
        "class A {",                          // 0
        "  void run() {",                     // 1
        "    class Local {",                  // 2
        "      int x;",                       // 3
        "    }",                              // 4
        "    Runnable r = new Runnable() {",  // 5
        "      public void run() {",          // 6
        "      }",                            // 7
        "    };",                             // 8
        "  }",                                // 9
        "",                                   // 10
        "  int y;",                           // 11
        "}",                                  // 12
        "");
    TypeBody body = new TypeBody(1, 12, List.of(new Member(List.of("method:run()"), 1, 10),
        new Member(List.of("field:y"), 11, 12)));
    assertEquals(body, locate(a, 3, 4).orElseThrow(), "a local class");
    assertEquals(body, locate(a, 7, 7).orElseThrow(), "an anonymous class");
  }

  @Test
  void crLfLineEnds() {
    assertEquals(new TypeBody(1, 4, List.of(new Member(List.of("field:x"), 1, 2),
        new Member(List.of("field:y"), 3, 4))), locate("class A {\r\n  int x;\r\n\r\n  int y;\r\n}\r\n", 2, 3)
        .orElseThrow());
  }

  @Test
  void commentsOverSeveralLinesDirectlyAboveBelongToTheMember() {
    String a = String.join("\n",
        "class A {",       // 0
        "  int x;",        // 1
        "",                // 2
        "  /*",            // 3
        "   * the y",      // 4
        "   */",           // 5
        "  // and more",   // 6
        "  int y;",        // 7
        "}",               // 8
        "");
    assertEquals(new TypeBody(1, 8, List.of(new Member(List.of("field:x"), 1, 2),
        new Member(List.of("field:y"), 3, 8))), locate(a, 2, 3).orElseThrow());
  }

  @Test
  void aVirtualMachineErrorFindsNothingAndIsNotLogged() {
    List<IStatus> logged = logDuring(() -> {
      JdtTestHooks.setMemberLocatorHook(() -> {
        throw new InternalError("injected");
      });
      assertEquals(Optional.empty(), locate(SRC, 8, 9));
    });
    assertEquals(List.of(), logged.stream().filter(s -> s.getException() instanceof InternalError).toList());
  }

  @Test
  void aRuntimeExceptionFindsNothingAndIsLogged() {
    IllegalStateException injected = new IllegalStateException("injected");
    List<IStatus> logged = logDuring(() -> {
      JdtTestHooks.setMemberLocatorHook(() -> {
        throw injected;
      });
      assertEquals(Optional.empty(), locate(SRC, 8, 9));
    });
    assertEquals(1, logged.stream().filter(s -> s.getException() == injected).count());
  }

  private static List<IStatus> logDuring(Runnable action) {
    List<IStatus> logged = new ArrayList<>();
    ILogListener listener = (status, plugin) -> {
      synchronized (logged) {
        logged.add(status);
      }
    };
    Platform.addLogListener(listener);
    try {
      action.run();
    } finally {
      Platform.removeLogListener(listener);
    }
    synchronized (logged) {
      return new ArrayList<>(logged);
    }
  }
}
