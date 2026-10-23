package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavacCheckTest {
  @BeforeEach
  void needsCompiler() {
    assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no system Java compiler");
  }

  @Test
  void aCleanResultHasNoNewErrors() {
    String ok = "class A { int x() { return 1; } }\n";
    assertEquals(java.util.List.of(), JavacCheck.errors("A.java", ok).orElseThrow());
    assertTrue(JavacCheck.noNewErrors("A.java", ok, ok, ok).orElseThrow());
  }

  @Test
  void anErrorOursHasIsInherited() {
    // a type of the project javac cannot see: ours has the same error, so the merge did not cause it
    String side = "class A { B b; }\n";
    String result = "class A { B b; int y; }\n";
    assertFalse(JavacCheck.errors("A.java", result).orElseThrow().isEmpty());
    assertTrue(JavacCheck.noNewErrors("A.java", result, side, "class A { }\n").orElseThrow());
  }

  @Test
  void aNewErrorIsCounted() {
    String side = "class A { int x() { return 1; } }\n";
    String result = "class A { int x() { return y; } }\n";
    assertFalse(JavacCheck.noNewErrors("A.java", result, side, side).orElseThrow());
  }

  @Test
  void typesOfTheHarnessClassPathDoNotResolve() {
    String source = "class A { org.junit.jupiter.api.Test t; net.aftergit.merge.core.text.LineText l; }\n";
    assertEquals(2, JavacCheck.errors("A.java", source).orElseThrow().size());
  }

  @Test
  void anErrorCountsAsOftenAsOursHasIt() {
    String one = "class A { B b; }\n";
    String two = "class A { B b; B c; }\n";
    assertTrue(JavacCheck.noNewErrors("A.java", two, two, one).orElseThrow());
    assertFalse(JavacCheck.noNewErrors("A.java", two, one, one).orElseThrow());
  }

  @Test
  void aTimeoutIsReportedNotSwallowed() {
    assertThrows(IllegalStateException.class,
        () -> JavacCheck.errors("A.java", "class A { }\n", 1, java.util.concurrent.TimeUnit.NANOSECONDS));
  }
}
