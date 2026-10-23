package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.TextMatePanes;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Languages design, Part 1: the grammar Eclipse uses for a file type; none for Java files and unknown types. */
class GrammarsTest {
  @AfterEach
  void tearDown() {
    LspTestHooks.setGrammarLookup(null);
  }

  @Test
  void theFileTypesOfTheTargetHaveTheirGrammars() {
    assertEquals("source.ts", Grammars.scopeOf("a.ts"));
    assertEquals("source.json", Grammars.scopeOf("a.json"));
    assertEquals("source.yaml", Grammars.scopeOf("a.yaml"));
    assertEquals("source.css", Grammars.scopeOf("a.css"));
    assertEquals("text.xml", Grammars.scopeOf("a.xml"));
  }

  @Test
  void markdownHasNoneBecauseItsGrammarDoesNotCompileWithTheJoniOfEclipse() {
    assertNull(Grammars.scopeOf("a.md"));
    assertNull(TextMatePanes.forFile("a.md", false, true));
  }

  @Test
  void javaFilesHaveNoneEvenThoughTheLanguagePackHasAJavaGrammar() {
    assertTrue(Grammars.isJava("A.java"));
    assertTrue(Grammars.isJava("B.JAVA"));
    assertNull(Grammars.scopeOf("A.java"));
  }

  @Test
  void aTypeWithoutAGrammarHasNone() {
    assertNull(Grammars.scopeOf("a.kt"));
    assertNull(Grammars.scopeOf("a.unknownext"));
    assertNull(Grammars.scopeOf(""));
    assertNull(Grammars.scopeOf(null));
    assertFalse(Grammars.isJava(null));
  }

  @Test
  void aJavaGrammarIsNeverGivenOut() {
    IGrammar java = (IGrammar) Proxy.newProxyInstance(IGrammar.class.getClassLoader(), new Class<?>[] {IGrammar.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getScopeName" -> "source.java@org.eclipse.tm4e.language_pack";
          case "toString" -> "a Java grammar";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> null;
        });
    LspTestHooks.setGrammarLookup(name -> java);
    assertNull(Grammars.scopeOf("a.jav"), "Java stays on JDT whatever the registry finds");
  }

  @Test
  void aBrokenRegistryGivesNoneAndAWarning() {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> {
      if (Grammars.PLUGIN_ID.equals(status.getPlugin())) {
        logged.add(status);
      }
    };
    Platform.addLogListener(listener);
    try {
      LspTestHooks.setGrammarLookup(name -> {
        throw new StackOverflowError("a grammar too deep for the stack");
      });
      assertNull(Grammars.scopeOf("a.ts"));
    } finally {
      Platform.removeLogListener(listener);
    }
    assertEquals(1, logged.size(), logged.toString());
    assertEquals(IStatus.WARNING, logged.get(0).getSeverity());
    assertFalse(logged.get(0).getException() instanceof VirtualMachineError, "never a raw StackOverflowError in the log");
  }
}
