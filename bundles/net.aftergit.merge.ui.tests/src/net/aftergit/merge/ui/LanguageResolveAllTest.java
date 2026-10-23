package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.ResolveAll;
import net.aftergit.merge.git.ResolveAllReport;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.SideChecks;
import net.aftergit.merge.lsp.diagnostics.SideServers;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: Resolve All's compile check asks the file's language server, as for Java. */
class LanguageResolveAllTest {
  static final ResolveAll.Options OPTIONS = new ResolveAll.Options(ComparisonPolicy.DEFAULT, false);
  /** A virtual document of Resolve All's check (languages design, Ruling 8 as amended: one name per opening). */
  static final String CHECK_URI = ".*/a\\.aftergit-check-\\d+\\.agdiag";

  @TempDir
  File dir;
  ConflictRepos repos;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener listener = (status, plugin) -> {
    if (Grammars.PLUGIN_ID.equals(status.getPlugin()) || "net.aftergit.merge.ui".equals(status.getPlugin())) {
      logged.add(status);
    }
  };

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    StandInLanguageServer.reset();
    LspTestHooks.setSideTimes(new long[] {-1, 3000, 50, 0, 3000});
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      ConflictRepos.waitUntil(() -> SideChecks.runningServers() == 0, 10, "no instance to outlive the test");
    } finally {
      Platform.removeLogListener(listener);
      SideServers.stopAll(); // a failed test leaves nothing to the next
      LspTestHooks.setSideTimes(null);
      LspBridge.setAvailableForTests(null);
      JavaBridge.setAvailableForTests(null);
      LspTestHooks.setServerFilter(null);
      repos.dispose();
      StandInLanguageServer.reset();
    }
  }

  private IFile conflict(String ours, String theirs) throws Exception {
    repos.write("lib.agdiag", "def lib\n");
    repos.conflict("a.agdiag", LanguageErrorsTest.BASE, ours, theirs);
    return repos.plainProject("resolve-all-languages", "a.agdiag");
  }

  private static ResolveAllReport resolveAll(IFile file, IProgressMonitor monitor) {
    return ResolveAll.run(List.of(file), OPTIONS, ResolveAllJob::checks, Set.of(), monitor);
  }

  private FileOutcome run(String ours, String theirs) throws Exception {
    return resolveAll(conflict(ours, theirs), new NullProgressMonitor()).files().get(0);
  }

  /** Resolve All of {@code file} on another thread, as the job runs it; the caller joins it. */
  private static Thread inBackground(IFile file, IProgressMonitor monitor, AtomicReference<ResolveAllReport> report,
      AtomicReference<Throwable> thrown) {
    Thread t = new Thread(() -> {
      try {
        report.set(resolveAll(file, monitor));
      } catch (Throwable e) {
        thrown.set(e);
      }
    }, "Resolve All of the test");
    t.setDaemon(true);
    t.start();
    return t;
  }

  @Test
  void aBlockWithANewErrorMakesTheFilePartlyResolved() throws Exception {
    FileOutcome o = run(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    assertEquals(FileOutcome.Result.PARTIAL, o.result());
    assertEquals(1, o.notCompiling());
    assertEquals(0, o.conflicts() - o.resolved(), "no conflict is left: the file is partly resolved for the error");
    assertTrue(repos.read("a.agdiag").contains(LanguageErrorsTest.MERGED + "\n"));
    ConflictRepos.waitUntil(() -> SideChecks.runningServers() == 0, 10, "the instance to stop after the run (idle 0)");
    File[] virtual = dir.listFiles((d, n) -> n.contains(".aftergit-"));
    assertEquals(0, virtual == null ? 0 : virtual.length, "no virtual document is written to disk");
    List<String> uris = StandInLanguageServer.virtualUris();
    assertEquals(3, uris.size(), "ours, theirs and the result, each its own opening: " + uris);
    assertTrue(uris.stream().allMatch(u -> u.matches(CHECK_URI)), uris.toString());
    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void anInheritedErrorKeepsTheFileFullyResolved() throws Exception {
    FileOutcome o = run(LanguageCompileMarksTest.INHERIT_OURS, LanguageCompileMarksTest.INHERIT_THEIRS);
    assertEquals(FileOutcome.Result.FULL, o.result());
    assertEquals(0, o.notCompiling());
    assertTrue(repos.read("a.agdiag").contains(LanguageCompileMarksTest.INHERITED + "\n"), "the block was auto-resolved");
  }

  @Test
  void aServerThatRejectsTheTextsMarksNothing() throws Exception {
    StandInLanguageServer.rejectMissingFiles = true;
    FileOutcome o = run(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    assertEquals(FileOutcome.Result.FULL, o.result(), "unknown, as a Java compile that failed");
    assertEquals(0, o.notCompiling());
  }

  @Test
  void withoutTheBundleOrAServerThereIsNoCheck() throws Exception {
    repos.conflict("a.txt", "a\n", "b\n", "c\n");
    IFile txt = repos.plainProject("resolve-all-languages", "a.txt");
    assertNull(ResolveAllJob.checks(txt));
    IFile agdiag = txt.getProject().getFile("a.agdiag");
    JavaChecks checks = ResolveAllJob.checks(agdiag);
    assertNotNull(checks);
    assertNull(checks.locator(), "no import pass for other languages");
    LspBridge.setAvailableForTests(false);
    assertNull(ResolveAllJob.checks(agdiag));
    assertTrue(StandInLanguageServer.INSTANCES.isEmpty(), "asking for the checks starts no server");
  }

  @Test
  void javaFilesAreNeverAskedOfALanguageServer() throws Exception {
    JavaBridge.setAvailableForTests(false); // without the JDT a Java file has no check at all
    LspTestHooks.setServerFilter(id -> true);
    repos.conflict("A.java", "class A {\n  int a;\n}\n", "class A {\n  int b;\n}\n", "class A {\n  int c;\n}\n");
    IFile javaFile = repos.plainProject("resolve-all-languages", "A.java");
    assertNull(ResolveAllJob.checks(javaFile));
    assertTrue(StandInLanguageServer.INSTANCES.isEmpty());
  }

  /** Task 4 re-review: Eclipse stopping the instances mid-check is unknown for Resolve All, never an exception. */
  @Test
  void eclipseStoppingTheInstancesMidCheckCountsNothing() throws Exception {
    StandInLanguageServer.delayMillis = 5000;
    IFile file = conflict(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    AtomicReference<ResolveAllReport> report = new AtomicReference<>();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread t = inBackground(file, new NullProgressMonitor(), report, thrown);
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.virtualUris().isEmpty(), 10, "ours to be opened");
    SideServers.stopAll();
    t.join(10_000);
    assertFalse(t.isAlive(), "Resolve All went on at once");
    assertNull(thrown.get(), "no exception reaches Resolve All");
    FileOutcome o = report.get().files().get(0);
    assertEquals(FileOutcome.Result.FULL, o.result(), "unknown: nothing is marked");
    assertEquals(0, o.notCompiling());
    assertTrue(repos.read("a.agdiag").contains(LanguageErrorsTest.MERGED + "\n"));
    assertTrue(logged.isEmpty(), "a stop is no failure: " + logged);
  }

  /** Task 4 re-review: a cancel while the server starts ends the run at once, without an exception. */
  @Test
  void aCancelWhileTheServerStartsEndsTheRunAtOnce() throws Exception {
    StandInLanguageServer.silentInitialize = true;
    LspTestHooks.setSideTimes(new long[] {20_000, 3000, 50, 0, 3000});
    IFile file = conflict(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    String before = repos.read("a.agdiag");
    NullProgressMonitor monitor = new NullProgressMonitor();
    AtomicReference<ResolveAllReport> report = new AtomicReference<>();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread t = inBackground(file, monitor, report, thrown);
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.INSTANCES.isEmpty(), 10, "the instance to start");
    long t0 = System.nanoTime();
    monitor.setCanceled(true);
    t.join(10_000);
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertFalse(t.isAlive());
    assertTrue(took < 2000, "the cancel did not wait for the start of 20 s: " + took + " ms");
    assertNull(thrown.get(), "no exception reaches Resolve All");
    assertTrue(report.get().cancelled());
    assertEquals(before, repos.read("a.agdiag"), "a cancelled file is not written");
    assertNull(SideChecks.errors(file, "use z\n", monitor), "a cancelled check is unknown");
    assertTrue(logged.isEmpty(), "a cancel is no failure: " + logged);
  }

  /** Task 4 re-review: a server that does not start is unknown for Resolve All (one warning), never an exception. */
  @Test
  void aServerThatDoesNotStartMarksNothing() throws Exception {
    StandInLanguageServer.silentInitialize = true;
    LspTestHooks.setSideTimes(new long[] {500, 3000, 50, 0, 3000});
    FileOutcome o = run(LanguageErrorsTest.OURS, LanguageErrorsTest.THEIRS);
    assertEquals(FileOutcome.Result.FULL, o.result(), "unknown: nothing is marked");
    assertEquals(0, o.notCompiling());
    assertTrue(repos.read("a.agdiag").contains(LanguageErrorsTest.MERGED + "\n"));
    List<IStatus> warnings = logged.stream().filter(st -> st.getSeverity() == IStatus.WARNING
        && Grammars.PLUGIN_ID.equals(st.getPlugin())).toList();
    assertEquals(1, warnings.size(), "one warning for the instance that did not start: " + logged);
  }
}
