package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.diagnostics.SideClient;
import net.aftergit.merge.lsp.diagnostics.SideServer;
import net.aftergit.merge.lsp.diagnostics.SideServers;
import net.aftergit.merge.lsp.diagnostics.VirtualUris;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: AfterGit's own server instance checks virtual documents next to the file. */
@SuppressWarnings("restriction")
class SideServerTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;
  LanguageServerDefinition standIn;
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
    LspTestHooks.setSideTimes(new long[] {-1, 2000, 100, 0, 2000}); // stop at once when unused
    repos.write("lib.agdiag", "def lib\n");
    repos.write("a.agdiag", "import ./lib.agdiag\n");
    file = repos.plainProject("side-server", "a.agdiag");
    standIn = LanguageServersRegistry.getInstance().getDefinition(StandInLanguageServer.ID);
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      ConflictRepos.waitUntil(() -> SideServers.running() == 0 && sideThreads().isEmpty(), 10,
          "no instance and no thread of a side check to outlive the test: " + sideThreads());
      assertTrue(StandInLanguageServer.INSTANCES.stream().allMatch(s -> s.stopped), "every instance stopped");
    } finally {
      Platform.removeLogListener(listener);
      SideServers.stopAll(); // a failed test leaves nothing to the next
      LspTestHooks.setSideTimes(null);
      repos.dispose();
      StandInLanguageServer.reset();
    }
  }

  private static List<String> sideThreads() {
    return Thread.getAllStackTraces().entrySet().stream()
        .filter(t -> t.getKey().isAlive() && t.getKey().getName().startsWith("AfterGit side check"))
        .map(t -> t.getKey().getName() + " " + Arrays.toString(t.getValue())).toList();
  }

  private static List<String> messages(List<Diagnostic> d) {
    return d.stream().map(x -> x.getMessage().getLeft()).sorted().toList();
  }

  @Test
  void virtualUrisAreSiblingsThatDoNotExist() throws Exception {
    URI ours = VirtualUris.sibling(file, "ours");
    assertEquals(new File(dir, "a.aftergit-ours.agdiag").toURI().getPath(), ours.getPath());
    repos.write("a.aftergit-theirs.agdiag", "a file of the user\n");
    URI theirs = VirtualUris.sibling(file, "theirs");
    assertEquals(new File(dir, "a.aftergit-theirs-2.agdiag").toURI().getPath(), theirs.getPath(),
        "a file of the user by that name is never taken");
    IFile noExtension = file.getProject().getFile("Makefile");
    assertTrue(VirtualUris.sibling(noExtension, "ours").getPath().endsWith("/Makefile.aftergit-ours"));
    // the mark goes before the first dot: every extension stays, so a declaration file is still one
    assertTrue(VirtualUris.sibling(file.getProject().getFile("types.d.ts"), "ours").getPath()
        .endsWith("/types.aftergit-ours.d.ts"));
    assertTrue(VirtualUris.sibling(file.getProject().getFile("a.test.ts"), "check").getPath()
        .endsWith("/a.aftergit-check.test.ts"));
    assertTrue(VirtualUris.sibling(file.getProject().getFile(".eslintrc.json"), "ours").getPath()
        .endsWith("/.eslintrc.aftergit-ours.json"), "a hidden name keeps its first dot");
    assertTrue(VirtualUris.sibling(file.getProject().getFile(".project"), "ours").getPath()
        .endsWith("/.project.aftergit-ours"));
  }

  @Test
  void eachOpeningHasANameOfItsOwnThatIsNoFileOfTheUser() throws Exception {
    URI ours = VirtualUris.sibling(file, "ours");
    AtomicInteger n = new AtomicInteger();
    assertEquals(new File(dir, "a.aftergit-ours-1.agdiag").toURI().getPath(),
        VirtualUris.opening(ours, n::incrementAndGet).getPath());
    repos.write("a.aftergit-ours-2.agdiag", "a file of the user\n");
    assertEquals(new File(dir, "a.aftergit-ours-3.agdiag").toURI().getPath(),
        VirtualUris.opening(ours, n::incrementAndGet).getPath(), "a file of the user by that name is never taken");
    URI marked = VirtualUris.sibling(file.getProject().getFile("x.aftergit-y.ts"), "ours");
    assertTrue(marked.getPath().endsWith("/x.aftergit-ours.aftergit-y.ts"), marked.toString());
    assertTrue(VirtualUris.opening(marked, () -> 4).getPath().endsWith("/x.aftergit-ours-4.aftergit-y.ts"),
        "the counter follows the role, also when the user's name holds the mark");
    URI hidden = VirtualUris.sibling(file.getProject().getFile(".aftergit-z"), "ours");
    assertTrue(VirtualUris.opening(hidden, () -> 5).getPath().endsWith("/.aftergit-z.aftergit-ours-5"),
        hidden.toString());
    URI make = VirtualUris.sibling(file.getProject().getFile("Makefile"), "check");
    assertTrue(VirtualUris.opening(make, () -> 7).getPath().endsWith("/Makefile.aftergit-check-7"));
    URI declarations = VirtualUris.sibling(file.getProject().getFile("types.d.ts"), "theirs");
    assertTrue(VirtualUris.opening(declarations, () -> 2).getPath().endsWith("/types.aftergit-theirs-2.d.ts"),
        declarations.toString());
  }

  @Test
  void answersAreMatchedToTheirUriHoweverAServerSpellsIt() {
    assertEquals(SideClient.key("file:///C:/a%20b/%C3%A9.ts"), SideClient.key("file:///c%3A/a%20b/%C3%A9.ts"),
        "vscode-uri writes a lower-case, encoded drive letter");
    assertEquals(SideClient.key("file:/x/y"), SideClient.key("file:///x/y"));
    assertEquals(SideClient.key("file:///x/./z/../y"), SideClient.key("file:///x/y"));
    assertNotEquals(SideClient.key("file:///x/y"), SideClient.key("file:///x/z"));
  }

  @Test
  void aCheckAnswersWithTheErrorsOfTheTextAndRelativeImportsResolve() throws Exception {
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      SideServer s = lease.server();
      assertNotNull(s);
      List<Diagnostic> d = s.check(VirtualUris.sibling(file, "ours"), "agdiag",
          "import ./lib.agdiag\nimport ./missing.agdiag\nuse nothing\n", 2000, 100, new NullProgressMonitor());
      assertNotNull(d);
      assertEquals(List.of("Cannot find ./missing.agdiag", "Unknown name nothing"), messages(d),
          "./lib.agdiag is found next to the file: the virtual document is its sibling");
      assertEquals(1, SideServers.running());
    }
    assertEquals(0, SideServers.running(), "stopped with the last lease (idle time 0 in this test)");
    assertTrue(StandInLanguageServer.INSTANCES.get(0).stopped);
  }

  @Test
  void aLateOrEmptyAnswerToAnEarlierOpeningIsNotTheNextOnesAnswer() throws Exception {
    StandInLanguageServer.delayMillis = 300; // later than the quiet time; the stand-in sends [] at each close
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      URI ours = VirtualUris.sibling(file, "ours");
      assertEquals(List.of("Unknown name a"),
          messages(lease.server().check(ours, "agdiag", "use a\n", 2000, 100, new NullProgressMonitor())));
      assertEquals(List.of("Unknown name b"),
          messages(lease.server().check(ours, "agdiag", "use b\n", 2000, 100, new NullProgressMonitor())),
          "the [] without a version for the close of the first opening is no answer to the second");
      assertEquals(2, StandInLanguageServer.virtualUris().stream().distinct().count(),
          StandInLanguageServer.virtualUris().toString());
    }
  }

  @Test
  void virtualDocumentsNeverReachTheDisk() throws Exception {
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      URI ours = VirtualUris.sibling(file, "ours");
      assertNotNull(lease.server().check(ours, "agdiag", "use x\n", 2000, 100, new NullProgressMonitor()));
      var opened = StandInLanguageServer.INSTANCES.get(0).opened;
      assertEquals(1, opened.size(), opened.toString());
      assertTrue(opened.keySet().iterator().next().endsWith("/a.aftergit-ours-1.agdiag"), opened.toString());
      assertEquals(Boolean.FALSE, opened.values().iterator().next(),
          "the server saw the document while no such file existed");
    }
    assertEquals(List.of("a.agdiag", "lib.agdiag"),
        Arrays.stream(dir.list()).filter(n -> n.endsWith(".agdiag")).sorted().toList());
    file.getProject().refreshLocal(IResource.DEPTH_INFINITE, null);
    assertNull(file.getProject().findMember("a.aftergit-ours-1.agdiag"));
  }

  @Test
  void aFolderWithASpaceAndLettersBeyondAsciiWorks() throws Exception {
    File folder = new File(dir, "p \u00e4 \u0107");
    folder.mkdirs();
    Files.writeString(new File(folder, "lib.agdiag").toPath(), "def lib\n");
    Files.writeString(new File(folder, "a.agdiag").toPath(), "import ./lib.agdiag\n");
    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("side-server-\u00e4");
    IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(project.getName());
    d.setLocation(org.eclipse.core.runtime.Path.fromOSString(folder.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    try {
      IFile a = project.getFile("a.agdiag");
      URI ours = VirtualUris.sibling(a, "ours");
      assertEquals(new File(folder, "a.aftergit-ours.agdiag").toPath(), java.nio.file.Path.of(ours));
      try (SideServers.Lease lease = SideServers.acquire(standIn, project)) {
        List<Diagnostic> found = lease.server().check(ours, "agdiag",
            "import ./lib.agdiag\nuse z\n", 2000, 100, new NullProgressMonitor());
        assertNotNull(found, "the answer is matched to the encoded URI");
        assertEquals(List.of("Unknown name z"), messages(found), "./lib.agdiag is found in that folder");
      }
    } finally {
      project.delete(false, true, null);
    }
  }

  @Test
  void aServerThatDoesNotAnswerRejectsAndIsLoggedOnce() throws Exception {
    StandInLanguageServer.rejectMissingFiles = true;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      SideServer s = lease.server();
      long t0 = System.nanoTime();
      assertNull(s.check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 1000, 100, new NullProgressMonitor()));
      assertTrue((System.nanoTime() - t0) / 1_000_000 >= 900, "it waited for the answer");
      assertTrue(s.rejecting());
      long t1 = System.nanoTime();
      assertNull(s.check(VirtualUris.sibling(file, "theirs"), "agdiag", "use y\n", 1000, 100,
          new NullProgressMonitor()));
      assertTrue((System.nanoTime() - t1) / 1_000_000 < 500, "a rejecting server is not asked again");
    }
    assertEquals(1, logged.size(), logged.toString());
    assertEquals(IStatus.INFO, logged.get(0).getSeverity());
  }

  @Test
  void theServerAsksTheClientOfItsDefinitionForItsSettingsAndItsOwnRequests() throws Exception {
    StandInLanguageServer.needsClientSettings = true; // as ESLint's server: no answer without them
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      List<Diagnostic> found = lease.server().check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 2000,
          100, new NullProgressMonitor());
      assertNotNull(found, "the definition's client answered workspace/configuration and standin/confirmExecution: "
          + StandInLanguageServer.SETTINGS_REFUSED);
      assertEquals(List.of("Unknown name x"), messages(found));
      assertFalse(lease.server().rejecting());
    }
    assertTrue(StandInLanguageServer.SETTINGS_ANSWERED.get() >= 1);
    assertEquals(0, StandInLanguageClient.WATCHERS.get(), "a watcher request never reaches the definition's client");
    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void requestsThatRegisterSomethingLastingAreNeverForwarded() {
    assertTrue(net.aftergit.merge.lsp.diagnostics.SideClient.registers("markdown/fs/watcher/create"));
    assertTrue(net.aftergit.merge.lsp.diagnostics.SideClient.registers("markdown/fs/watcher/delete"));
    assertTrue(net.aftergit.merge.lsp.diagnostics.SideClient.registers("client/registerCapability"));
    assertFalse(net.aftergit.merge.lsp.diagnostics.SideClient.registers("eslint/confirmESLintExecution"));
    assertFalse(net.aftergit.merge.lsp.diagnostics.SideClient.registers("workspace/configuration"));
  }

  @Test
  void aStartAfterAStopInTheSameClassLoaderIsANewSession() throws Exception {
    StandInLanguageServer.rejectMissingFiles = true;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      assertNull(lease.server().check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 500, 50,
          new NullProgressMonitor()));
    }
    assertTrue(SideServers.silent(standIn), "remembered as silent");
    net.aftergit.merge.lsp.internal.LspActivator activator = new net.aftergit.merge.lsp.internal.LspActivator();
    activator.stop(null); // the bundle stops, its class loader stays (the OSGi console)
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      assertNull(lease.server(), "stopped: nothing starts");
    }
    activator.start(null); // and starts again
    assertFalse(net.aftergit.merge.lsp.internal.LspActivator.stopped);
    assertFalse(SideServers.silent(standIn), "a new session forgets the silent servers");
    StandInLanguageServer.rejectMissingFiles = false;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      assertNotNull(lease.server(), "instances start again");
      assertEquals(List.of("Unknown name x"), messages(lease.server().check(VirtualUris.sibling(file, "ours"),
          "agdiag", "use x\n", 2000, 50, new NullProgressMonitor())));
    }
  }

  @Test
  void noInstanceStartsAfterTheBundleStopped() throws Exception {
    net.aftergit.merge.lsp.internal.LspActivator.stopped = true;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      assertNull(lease.server(), "a Resolve All job that goes on while Eclipse shuts down starts nothing");
      assertTrue(lease.stopped(), "which its check reads as cancelled, no rejection");
    } finally {
      net.aftergit.merge.lsp.internal.LspActivator.stopped = false;
    }
    assertEquals(0, SideServers.running());
    assertEquals(List.of(), StandInLanguageServer.INSTANCES);
  }

  @Test
  void aCancelledCheckIsNoRejection() throws Exception {
    StandInLanguageServer.delayMillis = 3000;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      NullProgressMonitor cancelled = new NullProgressMonitor();
      cancelled.setCanceled(true);
      assertNull(lease.server().check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 5000, 100, cancelled));
      assertFalse(lease.server().rejecting());
    }
  }

  @Test
  void aCheckCancelledWhileItWaitsEndsSoonAndIsNoRejection() throws Exception {
    StandInLanguageServer.delayMillis = 3000;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      NullProgressMonitor monitor = new NullProgressMonitor();
      CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> monitor.setCanceled(true));
      long t0 = System.nanoTime();
      assertNull(lease.server().check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 5000, 100, monitor));
      long took = (System.nanoTime() - t0) / 1_000_000;
      assertTrue(took < 1000, "returned " + took + " ms after the start, about 200 ms after the cancel");
      assertFalse(lease.server().rejecting());
    }
    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void usesShareOneInstanceAndAnIdleOneStopsLater() throws Exception {
    LspTestHooks.setSideTimes(new long[] {-1, 2000, 100, 2000, 2000});
    SideServers.Lease a = SideServers.acquire(standIn, file.getProject());
    SideServers.Lease b = SideServers.acquire(standIn, file.getProject());
    assertSame(a.server(), b.server());
    a.close();
    b.close();
    try (SideServers.Lease c = SideServers.acquire(standIn, file.getProject())) {
      assertSame(a.server(), c.server(), "kept for the idle time: Resolve All goes on with the next file");
    }
    ConflictRepos.waitUntil(() -> SideServers.running() == 0, 10, "the idle instance to stop");
    assertEquals(1, StandInLanguageServer.INSTANCES.size());
  }

  @Test
  void twoUsersAtOnceStartOneInstance() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    CompletableFuture<SideServers.Lease> other = CompletableFuture.supplyAsync(() -> {
      try {
        go.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return SideServers.acquire(standIn, file.getProject());
    });
    go.countDown();
    try (SideServers.Lease mine = SideServers.acquire(standIn, file.getProject());
        SideServers.Lease theirs = other.get(10, TimeUnit.SECONDS)) {
      assertNotNull(mine.server());
      assertSame(mine.server(), theirs.server());
      assertEquals(1, StandInLanguageServer.INSTANCES.size());
    }
  }

  @Test
  void aServerThatDoesNotStartIsLoggedOnceStaysFailedAndIsStopped() throws Exception {
    StandInLanguageServer.silentInitialize = true;
    LspTestHooks.setSideTimes(new long[] {500, 2000, 100, 5000, 2000});
    long t0 = System.nanoTime();
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      assertNull(lease.server());
    }
    long took = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(took >= 450 && took < 3000, "waited for the start time only: " + took + " ms");
    try (SideServers.Lease again = SideServers.acquire(standIn, file.getProject())) {
      assertNull(again.server(), "failed for the idle time: Resolve All does not wait at every file");
    }
    assertEquals(1, StandInLanguageServer.INSTANCES.size());
    assertTrue(StandInLanguageServer.INSTANCES.get(0).stopped);
    assertEquals(1, logged.size(), logged.toString());
    assertEquals(IStatus.WARNING, logged.get(0).getSeverity());
    SideServers.stopAll();
  }

  @Test
  void aCancelWhileTheInstanceStartsEndsTheWaitAtOnce() throws Exception {
    StandInLanguageServer.silentInitialize = true;
    LspTestHooks.setSideTimes(new long[] {20_000, 2000, 100, 0, 2000});
    NullProgressMonitor monitor = new NullProgressMonitor();
    CompletableFuture<Long> cancelled = CompletableFuture.supplyAsync(() -> {
      long t0 = System.nanoTime();
      try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject(), monitor)) {
        return -1L;
      } catch (org.eclipse.core.runtime.OperationCanceledException e) {
        return (System.nanoTime() - t0) / 1_000_000;
      }
    });
    ConflictRepos.waitUntil(() -> !StandInLanguageServer.INSTANCES.isEmpty(), 10, "the instance to start");
    monitor.setCanceled(true);
    long took = cancelled.get(5, TimeUnit.SECONDS);
    assertTrue(took >= 0, "the cancel ended the wait with OperationCanceledException");
    ConflictRepos.waitUntil(() -> StandInLanguageServer.INSTANCES.get(0).stopped, 10,
        "the start nobody waits for any more to be stopped (idle time 0)");
    assertTrue(logged.isEmpty(), "a start stopped while unused is no failure of the server: " + logged);
  }

  @Test
  void stopAllEndsKeptAndUsedInstancesAndWakesAWaitingCheck() throws Exception {
    LspTestHooks.setSideTimes(new long[] {-1, 5000, 100, 5000, 5000});
    SideServers.acquire(standIn, file.getProject()).close(); // kept for the idle time
    assertEquals(1, SideServers.running());
    SideServers.stopAll();
    assertEquals(0, SideServers.running());
    assertTrue(StandInLanguageServer.INSTANCES.get(0).stopped);

    StandInLanguageServer.delayMillis = 4000;
    try (SideServers.Lease lease = SideServers.acquire(standIn, file.getProject())) {
      SideServer s = lease.server();
      assertNotSame(StandInLanguageServer.INSTANCES.get(0), StandInLanguageServer.INSTANCES.get(1));
      CompletableFuture<List<Diagnostic>> waiting = CompletableFuture.supplyAsync(
          () -> s.check(VirtualUris.sibling(file, "ours"), "agdiag", "use x\n", 5000, 100, new NullProgressMonitor()));
      ConflictRepos.waitUntil(() -> !StandInLanguageServer.INSTANCES.get(1).opened.isEmpty(), 10,
          "the check to open its document");
      long t0 = System.nanoTime();
      SideServers.stopAll();
      assertNull(waiting.get(3, TimeUnit.SECONDS));
      assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "the waiting check ended with the stop");
      assertFalse(s.rejecting(), "a stop is no rejection");
      assertTrue(s.stopped());
      assertEquals(0, SideServers.running());
    }
    assertTrue(StandInLanguageServer.INSTANCES.stream().allMatch(x -> x.stopped));
    assertTrue(logged.isEmpty(), logged.toString());
  }

  @Test
  void stopAllStopsSeveralInstancesTogether() throws Exception {
    StandInLanguageServer.silentShutdown = true; // each stop waits its full second for the shutdown
    LspTestHooks.setSideTimes(new long[] {-1, 2000, 100, 5000, 2000});
    List<IProject> more = new ArrayList<>();
    try {
      for (int i = 0; i < 3; i++) {
        File folder = new File(dir, "more" + i);
        folder.mkdirs();
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject("side-server-more" + i);
        IProjectDescription d = ResourcesPlugin.getWorkspace().newProjectDescription(p.getName());
        d.setLocation(org.eclipse.core.runtime.Path.fromOSString(folder.getAbsolutePath()));
        p.create(d, null);
        p.open(null);
        more.add(p);
        SideServers.acquire(standIn, p).close(); // kept for the idle time
      }
      SideServers.acquire(standIn, file.getProject()).close();
      assertEquals(4, SideServers.running());
      long t0 = System.nanoTime();
      SideServers.stopAll();
      long took = (System.nanoTime() - t0) / 1_000_000;
      assertTrue(took < 2500, "four stops of a second each took " + took + " ms: together, not one after another");
      assertEquals(0, SideServers.running());
      ConflictRepos.waitUntil(() -> StandInLanguageServer.INSTANCES.stream().allMatch(x -> x.stopped), 10,
          "every instance to stop");
    } finally {
      for (IProject p : more) {
        p.delete(false, true, null);
      }
    }
  }
}
