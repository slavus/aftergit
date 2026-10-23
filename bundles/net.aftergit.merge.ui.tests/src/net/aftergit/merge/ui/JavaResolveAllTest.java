package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.ResolveAll;
import net.aftergit.merge.git.ResolveAllReport;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve All on Java files: pass 1 and the compile check without an editor (spec §5.5). */
class JavaResolveAllTest {
  static final String PATH = "src/p/M.java";
  static final String BASE = JavaCompileMarksTest.BASE;
  static final ResolveAll.Options OPTIONS = new ResolveAll.Options(ComparisonPolicy.DEFAULT, false);

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaBridge.setAvailableForTests(null);
    JavaBridge.setErrorsHookForTests(null);
    repos.dispose();
  }

  private FileOutcome run(String path, String base, String ours, String theirs) throws Exception {
    repos.conflict(path, base, ours, theirs);
    IFile file = repos.javaProject("resolve-all-java", path);
    ResolveAllReport r = ResolveAll.run(List.of(file), OPTIONS, JavaBridge::javaChecks, Set.of(),
        new NullProgressMonitor());
    return r.files().get(0);
  }

  @Test
  void aBlockThatDoesNotCompileMakesTheFilePartlyResolved() throws Exception {
    // ours renames the field, theirs uses the old name: the word-level merge does not compile
    String ours = BASE.replace("int first;", "int one;").replace("return first + second;", "return one + second;");
    String theirs = BASE.replace("return first + second;", "return first + first * 2;");
    FileOutcome o = run(PATH, BASE, ours, theirs);
    assertEquals(FileOutcome.Result.PARTIAL, o.result());
    assertEquals(1, o.notCompiling());
    assertEquals(0, o.conflicts() - o.resolved(), "no conflict is left: the file is partly resolved for the error");
    String text = repos.read(PATH);
    assertTrue(text.contains("    return one + first * 2;\n"), text);
    assertFalse(text.contains("<<<<<<< "), text);
  }

  @Test
  void anInheritedErrorKeepsTheFileFullyResolved() throws Exception {
    String ours = BASE.replace("return first + second;", "return first + second + 1;");
    String theirs = BASE.replace("return first + second;", "return undefinedName + second;");
    FileOutcome o = run(PATH, BASE, ours, theirs);
    assertEquals(FileOutcome.Result.FULL, o.result(), "theirs has the same error in that block");
    assertTrue(repos.read(PATH).contains("return undefinedName + second + 1;"));
  }

  @Test
  void theImportsTheResolvedBodyNeedsAreAdded() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    String line = "  Object a = null; Object b = null;\n";
    FileOutcome o = run("src/p/I.java", source(imports, line),
        source("import java.util.List;\n", line.replace("a = null", "a = \"x\"")),
        source(imports, line.replace("b = null", "b = Set.of()")));
    assertEquals(FileOutcome.Result.FULL, o.result());
    String text = repos.read("src/p/I.java");
    assertTrue(text.contains("Object a = \"x\"; Object b = Set.of();"), text);
    assertTrue(text.contains("import java.util.Set;\n"), text);
  }

  static String source(String imports, String body) {
    return "package p;\n\n" + imports + "\npublic class I {\n" + body + "}\n";
  }

  @Test
  void withoutTheJavaIntegrationOnlyConflictsCount() throws Exception {
    JavaBridge.setAvailableForTests(false);
    String ours = BASE.replace("int first;", "int one;").replace("return first + second;", "return one + second;");
    String theirs = BASE.replace("return first + second;", "return first + first * 2;");
    FileOutcome o = run(PATH, BASE, ours, theirs);
    assertEquals(FileOutcome.Result.FULL, o.result(), "nothing checks the compile without the JDT");
    assertEquals(0, o.notCompiling());
  }

  @Test
  void aFailingCompileIsUnknownAndLoggedSafely() throws Exception {
    JavaBridge.setErrorsHookForTests(() -> {
      throw new StackOverflowError("an overwhelmed compiler");
    });
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      String ours = BASE.replace("int first;", "int one;").replace("return first + second;", "return one + second;");
      String theirs = BASE.replace("return first + second;", "return first + first * 2;");
      FileOutcome o = run(PATH, BASE, ours, theirs);
      assertEquals(FileOutcome.Result.FULL, o.result(), "an unknown compile marks nothing");
    } finally {
      Platform.removeLogListener(l);
    }
    assertEquals(1, logged.stream().filter(s -> JavaImportFixTest.logs(s, StackOverflowError.class)).count(),
        "logged once per file");
    assertTrue(logged.stream().noneMatch(s -> s.getException() instanceof StackOverflowError),
        "never a raw StackOverflowError");
  }

  @Test
  void aCancelledCompileIsNotLoggedAsAFailure() throws Exception {
    repos.conflict(PATH, BASE, BASE, BASE.replace("int first;", "int one;"));
    IFile file = repos.javaProject("resolve-all-java", PATH);
    var checks = JavaBridge.javaChecks(file);
    assertNotNull(checks);
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      JavaBridge.setErrorsHookForTests(() -> {
        throw new org.eclipse.core.runtime.OperationCanceledException();
      });
      NullProgressMonitor cancelled = new NullProgressMonitor();
      cancelled.setCanceled(true);
      assertNull(checks.errors(BASE, cancelled));
      assertEquals(List.of(), logged, "a cancelled compile is no failure");
      JavaBridge.setErrorsHookForTests(() -> {
        throw new IllegalStateException("a broken compiler");
      });
      assertNull(checks.errors(BASE, new NullProgressMonitor()));
    } finally {
      Platform.removeLogListener(l);
    }
    assertEquals(1, logged.size(), "a real failure after the cancel is still logged");
  }
}
