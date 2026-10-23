package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.SideChecks;
import net.aftergit.merge.lsp.diagnostics.SideServers;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: the server's errors of ours and theirs, the counterpart of Java's SideAnalysis. */
@SuppressWarnings("restriction")
class SideChecksTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener listener = (status, plugin) -> {
    if (Grammars.PLUGIN_ID.equals(status.getPlugin())) {
      logged.add(status);
    }
  };

  @BeforeEach
  void setUp() throws Exception {
    repos = new ConflictRepos(dir);
    StandInLanguageServer.reset();
    LspTestHooks.setSideTimes(new long[] {-1, 1500, 100, 0, 1500}); // stop at once when unused
    repos.write("lib.agdiag", "def lib\n");
    repos.write("a.agdiag", "use lib\n");
    file = repos.plainProject("side-checks", "a.agdiag");
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      Job.getJobManager().join(SideChecks.FAMILY, null);
      ConflictRepos.waitUntil(() -> SideChecks.runningServers() == 0, 10, "no instance to outlive the test");
    } finally {
      Platform.removeLogListener(listener);
      SideServers.stopAll(); // a failed test leaves nothing to the next
      LspTestHooks.setSideTimes(null);
      repos.dispose();
      StandInLanguageServer.reset();
    }
  }

  private SideChecks run(String left, String right) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    SideChecks c = SideChecks.start(file, left, right, done::countDown);
    assertTrue(done.await(20, TimeUnit.SECONDS), "the checks to end");
    return c;
  }

  @Test
  void oursAndTheirsGetTheirErrorsByLine() throws Exception {
    SideChecks c = run("import ./lib.agdiag\ndef a\nuse a\nuse b\n", "def b\nuse b\n\nuse c\n");
    assertEquals(List.of(new LineProblem(3, "Unknown name b")), c.leftErrors(),
        "the import of ./lib.agdiag resolves next to the file");
    assertEquals(List.of(new LineProblem(3, "Unknown name c")), c.rightErrors());
    assertFalse(c.rejected());
    assertTrue(c.finished());
    assertEquals(0, SideChecks.runningServers(), "the instance stopped after the checks (idle time 0)");
    List<String> names = StandInLanguageServer.virtualUris().stream().map(u -> u.substring(u.lastIndexOf('/') + 1))
        .sorted().toList();
    assertEquals(2, names.size(), "one opening each: " + names);
    assertTrue(names.get(0).matches("a\\.aftergit-ours-\\d+\\.agdiag"), names.toString());
    assertTrue(names.get(1).matches("a\\.aftergit-theirs-\\d+\\.agdiag"), names.toString());
    assertFalse(new File(dir, names.get(0)).exists(), "never written");
    assertFalse(new File(dir, names.get(1)).exists(), "never written");
  }

  @Test
  void warningsAreNoErrors() throws Exception {
    SideChecks c = run("warn\n", "warn\n");
    assertEquals(List.of(), c.leftErrors());
    assertEquals(List.of(), c.rightErrors());
    assertFalse(c.rejected());
  }

  @Test
  void aServerThatRejectsThemLeavesBothSidesWithoutErrors() throws Exception {
    StandInLanguageServer.rejectMissingFiles = true;
    SideChecks c = run("use a\n", "use b\n");
    assertTrue(c.rejected());
    assertEquals(List.of(), c.leftErrors(), "new errors fall back to errors");
    assertEquals(List.of(), c.rightErrors());
  }

  @Test
  void aCancelledCheckKnowsNothingAndTellsNobody() throws Exception {
    StandInLanguageServer.delayMillis = 5000;
    AtomicBoolean told = new AtomicBoolean();
    SideChecks c = SideChecks.start(file, "use a\n", "use b\n", () -> told.set(true));
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.virtualUris().isEmpty(), 10, "ours to be opened");
    c.cancel();
    Job.getJobManager().join(SideChecks.FAMILY, null);
    assertNull(c.leftErrors());
    assertNull(c.rightErrors());
    assertFalse(told.get());
    assertFalse(c.rejected(), "a cancel is no rejection");
    assertTrue(c.finished());
  }

  @Test
  void textsWithoutTheFinalNewlineKeepTheirLastLine() throws Exception {
    SideChecks c = run("def a\nuse a\nuse b", "use c");
    assertEquals(List.of(new LineProblem(2, "Unknown name b")), c.leftErrors());
    assertEquals(List.of(new LineProblem(0, "Unknown name c")), c.rightErrors());
  }

  private static Diagnostic diagnostic(int startLine, int endLine, String message, DiagnosticSeverity severity) {
    Diagnostic d = new Diagnostic(new Range(new Position(startLine, 1), new Position(endLine, 0)), message);
    d.setSeverity(severity);
    return d;
  }

  @Test
  void errorsAreTakenByTheLineWhereTheyStart() {
    List<Diagnostic> found = new ArrayList<>();
    found.add(diagnostic(7, 7, "on the last line, which has no newline", DiagnosticSeverity.Error));
    found.add(diagnostic(2, 2, "without severity", null));
    found.add(diagnostic(3, 5, "over three lines", DiagnosticSeverity.Error));
    found.add(diagnostic(4, 4, "first line\r\nsecond line", DiagnosticSeverity.Error));
    Diagnostic markdown = diagnostic(6, 6, "", DiagnosticSeverity.Error);
    markdown.setMessage(Either.forRight(new MarkupContent(MarkupKind.MARKDOWN, "**bold**")));
    found.add(markdown);
    found.add(diagnostic(1, 1, "a warning", DiagnosticSeverity.Warning));
    found.add(diagnostic(1, 1, "an information", DiagnosticSeverity.Information));
    found.add(diagnostic(1, 1, "a hint", DiagnosticSeverity.Hint));
    found.add(null);
    Diagnostic noRange = new Diagnostic();
    noRange.setMessage("no range");
    found.add(noRange);
    assertEquals(List.of(new LineProblem(7, "on the last line, which has no newline"),
        new LineProblem(2, "without severity"), new LineProblem(3, "over three lines"),
        new LineProblem(4, "first line\r\nsecond line"), new LineProblem(6, "**bold**")),
        SideChecks.errorLines(found));
  }

  @Test
  void theLanguageIdIsLsp4esMappingElseTheExtension() {
    var standIn = LanguageServersRegistry.getInstance().getDefinition(StandInLanguageServer.ID);
    assertEquals("agdiag", SideChecks.languageId(standIn, "a.agdiag"), "the mapping of fragment.xml");
    assertEquals("ts", SideChecks.languageId(standIn, "a.ts"), "no mapping: the extension");
    assertEquals("Makefile", SideChecks.languageId(standIn, "Makefile"), "no extension: the name");
  }

  @Test
  void aCancelWhileTheServerStartsEndsTheChecksAtOnce() throws Exception {
    StandInLanguageServer.silentInitialize = true;
    LspTestHooks.setSideTimes(new long[] {20_000, 1500, 100, 0, 1500});
    AtomicBoolean told = new AtomicBoolean();
    SideChecks c = SideChecks.start(file, "use a\n", "use b\n", () -> told.set(true));
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.INSTANCES.isEmpty(), 10, "the instance to start");
    long t0 = System.nanoTime();
    c.cancel();
    Job.getJobManager().join(SideChecks.FAMILY, null);
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took < 2000, "the cancel did not wait for the start of 20 s: " + took + " ms");
    assertNull(c.leftErrors());
    assertFalse(c.rejected(), "a cancel is no rejection");
    assertFalse(told.get());
    ConflictRepos.waitUntil(() -> StandInLanguageServer.INSTANCES.get(0).stopped, 10,
        "the unused start to be stopped (idle time 0)");
    assertTrue(logged.isEmpty(), "a start stopped for a cancel is no failure of the server: " + logged);
  }

  @Test
  void eclipseStoppingTheInstancesIsNoRejection() throws Exception {
    StandInLanguageServer.delayMillis = 5000;
    AtomicBoolean told = new AtomicBoolean();
    SideChecks c = SideChecks.start(file, "use a\n", "use b\n", () -> told.set(true));
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.virtualUris().isEmpty(), 10, "ours to be opened");
    SideServers.stopAll();
    Job.getJobManager().join(SideChecks.FAMILY, null);
    assertNull(c.leftErrors());
    assertFalse(c.rejected());
    assertFalse(told.get(), "nobody is told on a stopped system");
    assertTrue(c.finished());
    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void aServerThatAsksItsClientForSettingsChecksTheSides() throws Exception {
    StandInLanguageServer.needsClientSettings = true;
    SideChecks c = run("use a\n", "use b\n");
    assertFalse(c.rejected(), "the settings come from the client of the server's definition: "
        + StandInLanguageServer.SETTINGS_REFUSED);
    assertEquals(List.of(new LineProblem(0, "Unknown name a")), c.leftErrors());
    assertEquals(List.of(new LineProblem(0, "Unknown name b")), c.rightErrors());
    assertTrue(StandInLanguageServer.SETTINGS_ANSWERED.get() >= 2);
  }

  @Test
  void aServerThatDoesNotAnswerRejectsOnlyItsOwnSides() throws Exception {
    pairFile();
    SideChecks c = run("use a\n", "use b\n");
    assertTrue(c.rejected());
    assertEquals(java.util.Set.of(StandInMuteServerConnection.ID, StandInMuteServerConnection.ID2),
        c.rejectedServers(), "only the mute ones");
    assertEquals(List.of(new LineProblem(0, "Unknown name a")), c.leftErrors(), "the stand-in's answer counts");
    assertEquals(List.of(new LineProblem(0, "Unknown name b")), c.rightErrors());
    assertEquals(List.of(), c.leftErrors(java.util.Set.of(StandInLanguageServer.ID)));
    // Resolve All: the result, ours and theirs as the stand-in found them, the mute server left out of all three
    assertEquals(List.of(List.of(new LineProblem(0, "Unknown name z")), List.of(new LineProblem(0, "Unknown name a")),
        List.of()), SideChecks.errors(file, "use z\n", "use a\n", "def z\n", new NullProgressMonitor()));
  }

  private void pairFile() throws Exception {
    repos.write("a.agpair", "use lib\n");
    file = file.getProject().getFile("a.agpair");
    file.refreshLocal(0, null);
  }

  @Test
  void theServersOfAFileAreAskedAtOnceSoTheWaitIsThatOfTheSlowest() throws Exception {
    LspTestHooks.setSideTimes(new long[] {-1, 2000, 100, 0, 2000});
    pairFile();
    long t0 = System.nanoTime();
    SideChecks c = run("use a\n", "use b\n");
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertEquals(java.util.Set.of(StandInMuteServerConnection.ID, StandInMuteServerConnection.ID2),
        c.rejectedServers());
    assertEquals(List.of(new LineProblem(0, "Unknown name a")), c.leftErrors(), "the answering one counts");
    assertTrue(took >= 1900, "the silent ones were waited for: " + took + " ms");
    assertTrue(took < 3500, "two silent servers cost one wait of 2 s, not two: " + took + " ms");
  }

  @Test
  void aServerThatLeftAVirtualDocumentUnansweredIsNotAskedAgainInTheSession() throws Exception {
    pairFile();
    run("use a\n", "use b\n"); // the first resolver waits for the silent ones once
    ConflictRepos.waitUntil(() -> SideChecks.runningServers() == 0, 10, "the instances to stop (idle time 0)");
    long mutes = StandInLanguageServer.INSTANCES.stream().filter(StandInLanguageServer::muteVirtual).count();
    long t0 = System.nanoTime();
    SideChecks c = run("use c\n", "use d\n"); // a second resolver
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took < 1000, "no wait for the remembered servers: " + took + " ms");
    assertEquals(java.util.Set.of(StandInMuteServerConnection.ID, StandInMuteServerConnection.ID2),
        c.rejectedServers());
    assertEquals(List.of(new LineProblem(0, "Unknown name c")), c.leftErrors());
    assertEquals(mutes, StandInLanguageServer.INSTANCES.stream().filter(StandInLanguageServer::muteVirtual).count(),
        "the remembered servers were not even started");
    List<IStatus> infos = logged.stream().filter(s -> s.getSeverity() == IStatus.INFO).toList();
    assertEquals(2, infos.size(), "one INFO per remembered server: " + infos);
  }

  @Test
  void resolveAllChecksTheSidesOnlyWhenTheResultHasAnError() throws Exception {
    assertEquals(List.of(List.of(), List.of(), List.of()),
        SideChecks.errors(file, "def a\nuse a\n", "use b\n", "use c\n", new NullProgressMonitor()));
    assertEquals(1, StandInLanguageServer.virtualUris().size(), "only the result was opened");
  }

  @Test
  void theFirstCheckOfAnInstanceMayTakeAsLongAsAStart() throws Exception {
    // a server that publishes only once it has loaded the project: 1.2 s for every document
    StandInLanguageServer.sideDelayMillis = 1200;
    LspTestHooks.setSideTimes(new long[] {-1, 500, 50, 10_000, 5000}); // the instance stays between the checks
    assertEquals(List.of(new LineProblem(0, "Unknown name z")),
        SideChecks.errors(file, "use z\n", new NullProgressMonitor()), "the first check waits up to 5 s");
    assertNull(SideChecks.errors(file, "use y\n", new NullProgressMonitor()), "later ones wait 500 ms");
    SideServers.stopAll();
  }

  @Test
  void resolveAllChecksOneText() throws Exception {
    assertEquals(List.of(new LineProblem(1, "Unknown name z")),
        SideChecks.errors(file, "def lib\nuse z\nimport ./lib.agdiag\n", new NullProgressMonitor()));
    IFile txt = file.getProject().getFile("a.txt");
    assertNull(SideChecks.errors(txt, "use z\n", new NullProgressMonitor()), "no server: unknown");
    StandInLanguageServer.rejectMissingFiles = true;
    assertNull(SideChecks.errors(file, "use z\n", new NullProgressMonitor()), "rejected: unknown");
  }
}
