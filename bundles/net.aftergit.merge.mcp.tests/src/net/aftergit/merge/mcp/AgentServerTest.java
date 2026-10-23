package net.aftergit.merge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModelException;
import net.aftergit.merge.git.agents.OpenModels;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.git.agents.ProposalSource;
import net.aftergit.merge.mcp.service.Fingerprint;
import net.aftergit.merge.mcp.service.ProposalStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/** Agents design §1, §4, §5: off by default, the switch, the token, the log. */
class AgentServerTest {
  @TempDir
  File root;
  IEclipsePreferences prefs;
  TokenStore tokens;
  /** How often the server asked the token store anything (secure storage must stay closed while off). */
  AtomicInteger tokenUses = new AtomicInteger();
  AgentServer server;
  BundleContext context;
  SystemReader original;
  Repository repo;
  IProject project;
  ServiceRegistration<OpenModels> fakeModels;

  @BeforeEach
  void setUp() {
    prefs = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.test" + System.nanoTime());
    tokens = counting(TokenStore.memory(), tokenUses);
    context = FrameworkUtil.getBundle(AgentServerTest.class).getBundleContext();
    server = new AgentServer(tokens, context, prefs);
    server.follow();
  }

  @AfterEach
  void tearDown() throws Exception {
    prefs.putBoolean(McpPreferences.ENABLED, false);
    server.apply();
    server.unfollow();
    prefs.removeNode();
    if (fakeModels != null) {
      fakeModels.unregister();
    }
    if (original != null) {
      McpRepos.cleanUp(original, root, repo, project);
    }
  }

  static TokenStore counting(TokenStore store, AtomicInteger uses) {
    return new TokenStore() {
      @Override
      public String get() {
        uses.incrementAndGet();
        return store.get();
      }

      @Override
      public String create() {
        uses.incrementAndGet();
        return store.create();
      }

      @Override
      public void revoke() {
        uses.incrementAndGet();
        store.revoke();
      }
    };
  }

  void enable() {
    prefs.putBoolean(McpPreferences.ENABLED, true); // the preference listener applies it
  }

  IFile conflictProject() throws Exception {
    File dir = new File(root, "repo");
    original = McpRepos.isolateUserConfig(root);
    repo = McpRepos.conflict(dir, "src/a.txt", McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS);
    project = McpRepos.project("agent-server", dir);
    return project.getFile("src/a.txt");
  }

  /** A stored record for another conflict: any read by a store deletes it. */
  RepoFile staleRecord() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "src/a.txt");
    new ProposalStore().put(rf, new Fingerprint("x", "x", "x", "x"), new Proposal(new BlockId(0, "0123456789ab"),
        "x\n", "", "test-agent", Instant.now()), false);
    assertTrue(Files.isRegularFile(ProposalStore.path(rf)));
    return rf;
  }

  /** Every file and directory under .git/aftergit with its time and contents. */
  Map<String, String> agentFiles() throws Exception {
    Map<String, String> out = new TreeMap<>();
    Path dir = repo.getDirectory().toPath().resolve("aftergit");
    if (!Files.exists(dir)) {
      return out;
    }
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        out.put(dir.relativize(p).toString(), Files.isDirectory(p) ? "dir"
            : Files.getLastModifiedTime(p) + " " + Files.readString(p));
      }
    }
    return out;
  }

  @Test
  void isOffByDefaultWithoutPortTokenOrSource() {
    assertFalse(server.running());
    assertNull(server.url());
    assertNull(server.token());
    assertEquals(-1, server.port());
    assertEquals(0, tokenUses.get(), "secure storage is not even opened while off");
    assertNull(tokens.get());
    assertNull(context.getServiceReference(ProposalSource.class));
    assertNull(server.failure());
  }

  @Test
  void whileOffNothingOpensSecureStorageOrTouchesTheStoredProposals() throws Exception {
    IFile file = conflictProject();
    staleRecord();
    Map<String, String> before = agentFiles();
    server.apply();
    prefs.putInt(McpPreferences.PORT, 4711); // the listener applies it: still off
    server.follow();
    server.apply();
    assertFalse(server.running());
    assertEquals(0, tokenUses.get());
    assertNull(context.getServiceReference(ProposalSource.class));
    assertEquals(before, agentFiles());
    assertTrue(file.exists());
  }

  @Test
  void aStoppedServerLeavesTheStoredProposalsAlone() throws Exception {
    IFile file = conflictProject();
    enable();
    ProposalSource source = context.getService(context.getServiceReference(ProposalSource.class));
    prefs.putBoolean(McpPreferences.ENABLED, false);
    RepoFile rf = staleRecord();
    Map<String, String> before = agentFiles();
    // a resolver that still holds the source from before the switch went off
    assertEquals(new ProposalSource.Stored(List.of(), false), source.stored(file));
    source.forget(file, new BlockId(0, "0123456789ab"));
    assertEquals(before, agentFiles(), "nothing under .git/aftergit changes while off");
    enable();
    ProposalSource fresh = context.getService(context.getServiceReference(ProposalSource.class));
    assertTrue(fresh.stored(file).stale(), "the record was stale: a running server deletes it");
    assertFalse(Files.exists(ProposalStore.path(rf)));
  }

  @Test
  void enablingStartsOnLoopbackWithANewTokenAndTheSource() {
    enable();
    assertTrue(server.running());
    assertTrue(server.url().matches("http://127\\.0\\.0\\.1:\\d+/mcp"), server.url());
    String token = server.token();
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
    assertEquals(32, Base64.getUrlDecoder().decode(token).length);
    assertEquals(token, tokens.get());
    assertNotNull(context.getServiceReference(ProposalSource.class));
  }

  @Test
  void theServerSaysHowToUseItAndHasNoToolThatSavesOrStages() throws Exception {
    enable();
    RawMcp c = new RawMcp(server.url(), server.token());
    JsonObject init = c.initialize();
    assertEquals("AfterGit", init.getAsJsonObject("serverInfo").get("name").getAsString());
    assertTrue(init.get("instructions").getAsString().contains("propose_resolution"));
    List<String> names = c.toolNames();
    assertTrue(names.containsAll(List.of("list_conflicts", "get_conflict", "validate")), names.toString());
    for (String n : names) {
      assertFalse(n.matches(".*(save|stage|write|commit|read_file).*"), n);
    }
  }

  @Test
  void requestsWithoutTheRightTokenAreRefused() throws Exception {
    enable();
    assertEquals(401, new RawMcp(server.url(), null).post("{}").status());
    assertEquals(401, new RawMcp(server.url(), "wrong").post("{}").status());
  }

  @Test
  void switchingOffStopsAtOnceRevokesTheTokenAndUnregistersTheSource() throws Exception {
    enable();
    String url = server.url();
    String token = server.token();
    prefs.putBoolean(McpPreferences.ENABLED, false);
    assertFalse(server.running());
    assertNull(tokens.get());
    assertNull(server.token());
    assertNull(context.getServiceReference(ProposalSource.class));
    assertThrows(ConnectException.class, () -> new RawMcp(url, token).post("{}"));
    enable();
    assertNotEquals(token, server.token());
  }

  @Test
  void switchingOffNeverWaitsForARunningCall() throws Exception {
    IFile file = conflictProject();
    fakeModels = context.registerService(OpenModels.class, new OpenModels() {
      @Override
      public Optional<OpenModel> find(IFile f) {
        return Optional.of(new OpenModel() {
          @Override public IFile file() { return f; }
          @Override public <T> T read(Function<MergeModel, T> reader) throws OpenModelException {
            try {
              Thread.sleep(30_000); // a busy UI thread
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new OpenModelException("interrupted");
          }
          @Override public Offer offer(Proposal p) { return Offer.NOT_FOUND; }
          @Override public boolean withdraw(Proposal p) { return false; }
        });
      }
      @Override public JavaChecks javaChecks(IFile f) { return null; }
      @Override public ComparisonPolicy policy() { return ComparisonPolicy.DEFAULT; }
      @Override public boolean greedy() { return false; }
    }, null);
    enable();
    RawMcp c = new RawMcp(server.url(), server.token());
    c.initialize();
    CompletableFuture<Object> call = CompletableFuture.supplyAsync(() -> {
      try {
        return c.tool("get_conflict", RawMcp.args("path", file.getLocation().toOSString()));
      } catch (Exception e) {
        return e;
      }
    });
    Thread.sleep(500);
    long start = System.nanoTime();
    prefs.putBoolean(McpPreferences.ENABLED, false);
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "switching off did not wait");
    assertNotNull(call.get(10, TimeUnit.SECONDS));
  }

  @Test
  void regeneratingTheTokenRefusesTheOldOneAtOnce() throws Exception {
    enable();
    String old = server.token();
    String fresh = server.regenerateToken();
    assertNotEquals(old, fresh);
    assertEquals(fresh, tokens.get());
    assertEquals(401, new RawMcp(server.url(), old).post("{}").status());
    new RawMcp(server.url(), fresh).initialize();
  }

  @Test
  void switchingOffWhileARegenerationFailsStillStopsTheServer() throws Exception {
    TokenStore memory = TokenStore.memory();
    java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.fail" + System.nanoTime());
    AgentServer failing = new AgentServer(new TokenStore() {
      @Override public String get() { return memory.get(); }

      @Override
      public String create() {
        if (failNext.get()) {
          p.putBoolean(McpPreferences.ENABLED, false); // the user switches off during the password dialog ...
          throw new IllegalStateException("cancelled"); // ... and then cancels it
        }
        return memory.create();
      }

      @Override public void revoke() { memory.revoke(); }
    }, context, p);
    failing.follow();
    try {
      p.putBoolean(McpPreferences.ENABLED, true);
      assertTrue(failing.running());
      failNext.set(true);
      assertThrows(IllegalStateException.class, failing::regenerateToken);
      assertFalse(failing.running(), "the deferred switch-off was lost");
      assertNull(failing.token());
    } finally {
      failing.unfollow();
      p.removeNode();
    }
  }

  @Test
  void aBusyPortIsReportedAndAFreeOneUsed() throws Exception {
    int port;
    try (ServerSocket busy = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      port = busy.getLocalPort();
      prefs.putInt(McpPreferences.PORT, port);
      enable();
      assertFalse(server.running());
      assertNotNull(server.failure());
    }
    server.apply();
    assertTrue(server.running());
    assertEquals(port, server.port());
    assertNull(server.failure());
  }

  @Test
  void aNewPortRestartsTheServerWithTheSameToken() throws Exception {
    enable();
    String token = server.token();
    int free;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      free = s.getLocalPort();
    }
    prefs.putInt(McpPreferences.PORT, free);
    assertTrue(server.running());
    assertEquals(free, server.port());
    assertEquals(token, server.token());
  }

  @Test
  void theSourceGivesTheStoredProposalsAndForgetsWhatTheUserDiscarded() throws Exception {
    IFile file = conflictProject();
    enable();
    RawMcp c = new RawMcp(server.url(), server.token());
    c.initialize();
    String path = file.getLocation().toOSString();
    JsonObject got = c.tool("get_conflict", RawMcp.args("path", path)).getAsJsonObject("structuredContent");
    String block = got.getAsJsonArray("blocks").get(0).getAsJsonObject().get("id").getAsString();
    JsonObject proposed = c.tool("propose_resolution", RawMcp.args("path", path, "block", block, "text", "L1\nR1\n",
        "rationale", "both"));
    assertFalse(proposed.get("isError").getAsBoolean(), proposed.toString());
    ProposalSource source = context.getService(context.getServiceReference(ProposalSource.class));
    ProposalSource.Stored first = source.stored(file);
    assertEquals(1, first.proposals().size());
    assertTrue(first.shown().isEmpty(), "no resolver had it yet");
    McpRepos.write(new File(root, "repo"), "src/a.txt", "saved by the resolver\n");
    ProposalSource.Stored again = source.stored(file);
    assertEquals(1, again.proposals().size(), "an open file: only the stages count");
    assertEquals(java.util.Set.of(BlockId.parse(block)), again.shown(), "a resolver had it: from now on it waits");
    source.forget(file, BlockId.parse(block));
    assertEquals(0, source.stored(file).proposals().size());
  }

  @Test
  void startingTheServerDeletesTheRecordsOfConflictsResolvedWhileItWasOff() throws Exception {
    conflictProject();
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    new ProposalStore().put(rf, Fingerprint.of(rf), new Proposal(
        new BlockId(0, "0123456789ab"), "x\n", "", "test-agent", Instant.now()), false);
    org.eclipse.jgit.api.Git.wrap(repo).add().addFilepattern("src/a.txt").call(); // resolved while agents are off
    assertTrue(Files.exists(ProposalStore.path(rf)), "nothing touches the files while off");
    enable();
    assertTrue(server.running());
    assertFalse(Files.exists(ProposalStore.path(rf)));
  }

  @Test
  void theSourceDropsTheRecordOfAFileTheUserMarkedResolved() throws Exception {
    IFile file = conflictProject();
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    enable();
    new ProposalStore().put(rf, Fingerprint.of(rf), new Proposal(
        new BlockId(0, "0123456789ab"), "x\n", "", "test-agent", Instant.now()), true);
    ProposalSource source = context.getService(context.getServiceReference(ProposalSource.class));
    source.resolved(file);
    assertFalse(Files.exists(ProposalStore.path(rf)));
  }

  @Test
  void deleteStoredProposalsRemovesTheFilesOfWorkspaceRepositories() throws Exception {
    conflictProject();
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    new ProposalStore().put(rf, Fingerprint.of(rf), new Proposal(
        new BlockId(0, "0123456789ab"), "x\n", "", "test-agent", Instant.now()), false);
    assertEquals(1, server.deleteStoredProposals());
    assertFalse(Files.exists(ProposalStore.path(rf)));
  }

  @Test
  void everyToolCallIsLoggedWithoutContentsOrToken() throws Exception {
    IFile file = conflictProject();
    List<String> logged = new ArrayList<>();
    ILogListener listener = (status, plugin) -> logged.add(status.getMessage());
    Platform.addLogListener(listener);
    try {
      enable();
      RawMcp c = new RawMcp(server.url(), server.token());
      c.initialize();
      String path = file.getLocation().toOSString();
      c.tool("get_conflict", RawMcp.args("path", path));
      c.tool("propose_resolution", RawMcp.args("path", path, "block", "0:000000000000", "text", "SECRET-TEXT",
          "rationale", "SECRET-WHY"));
    } finally {
      Platform.removeLogListener(listener);
    }
    String path = file.getLocation().toOSString();
    assertTrue(logged.contains("MCP get_conflict " + path + ": ok"), logged.toString());
    assertTrue(logged.stream().anyMatch(m -> m.startsWith("MCP propose_resolution " + path + ": refused")),
        logged.toString());
    for (String m : logged) {
      assertFalse(m.contains("SECRET") || m.contains(server.token()), m);
    }
  }

  @Test
  void theGettersAnswerAtOnceWhileSecureStorageBlocks() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch unlock = new CountDownLatch(1);
    TokenStore memory = TokenStore.memory();
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.block" + System.nanoTime());
    AgentServer blocked = new AgentServer(new TokenStore() {
      @Override
      public String get() {
        asked.countDown();
        try {
          unlock.await(); // e.g. a locked keyring
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return memory.get();
      }

      @Override public String create() { return memory.create(); }
      @Override public void revoke() { memory.revoke(); }
    }, context, p);
    blocked.follow();
    CompletableFuture<Void> enabling = CompletableFuture.runAsync(() -> p.putBoolean(McpPreferences.ENABLED, true));
    try {
      assertTrue(asked.await(10, TimeUnit.SECONDS));
      CompletableFuture<String> getters = CompletableFuture.supplyAsync(() -> blocked.running() + " " + blocked.url()
          + " " + blocked.token() + " " + blocked.port() + " " + blocked.failure());
      assertEquals("false null null -1 null", getters.get(2, TimeUnit.SECONDS), "the getters did not wait");
    } finally {
      unlock.countDown();
      enabling.get(10, TimeUnit.SECONDS);
    }
    assertTrue(blocked.running());
    p.putBoolean(McpPreferences.ENABLED, false);
    assertFalse(blocked.running());
    blocked.unfollow();
    p.removeNode();
  }

  @Test
  void aListenerMayCallBackIntoTheServerFromAnyThread() throws Exception {
    List<String> seen = new ArrayList<>();
    AtomicReference<Throwable> problem = new AtomicReference<>();
    server.addListener(() -> {
      try {
        // as a UI listener that asks the UI thread: another thread uses the server while the listener runs
        seen.add(CompletableFuture.supplyAsync(() -> {
          server.apply();
          return server.running() + " " + (server.url() != null);
        }).get(5, TimeUnit.SECONDS));
      } catch (Exception e) {
        problem.compareAndSet(null, e);
      }
    });
    enable();
    prefs.putBoolean(McpPreferences.ENABLED, false);
    assertNull(problem.get(), () -> "a listener could not reach the server: " + problem.get());
    assertEquals(List.of("true true", "false false"), seen);
  }

  @Test
  void aFailingSecureStorageIsReportedAndNothingRuns() throws Exception {
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.fail" + System.nanoTime());
    AgentServer failing = new AgentServer(new TokenStore() {
      @Override public String get() { return null; }
      @Override public String create() {
        throw new IllegalStateException("Eclipse secure storage is not available for the MCP token");
      }
      @Override public void revoke() {}
    }, context, p);
    failing.follow();
    p.putBoolean(McpPreferences.ENABLED, true);
    assertFalse(failing.running());
    assertEquals(-1, failing.port());
    assertNull(failing.token());
    assertTrue(failing.failure().contains("secure storage"), failing.failure());
    assertNull(context.getServiceReference(ProposalSource.class));
    p.putBoolean(McpPreferences.ENABLED, false);
    assertNull(failing.failure());
    failing.unfollow();
    p.removeNode();
  }

  @Test
  void replacingTheSharedInstanceStopsTheOldOne() throws Exception {
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.shared" + System.nanoTime());
    AgentServer old = new AgentServer(TokenStore.memory(), context, p);
    p.putBoolean(McpPreferences.ENABLED, true);
    old.follow();
    assertTrue(old.running());
    AgentServer.setInstanceForTests(old);
    AgentServer.setInstanceForTests(null);
    assertFalse(old.running());
    assertNull(old.token());
    assertNull(context.getServiceReference(ProposalSource.class));
    p.removeNode();
  }

  @Test
  void aNestedApplyWhileTheTokenIsFetchedStartsOnlyOneServer() throws Exception {
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.nested" + System.nanoTime());
    TokenStore memory = TokenStore.memory();
    AtomicReference<AgentServer> self = new AtomicReference<>();
    AtomicInteger asked = new AtomicInteger();
    AgentServer nesting = new AgentServer(new TokenStore() {
      @Override
      public String get() {
        if (asked.getAndIncrement() == 0) {
          self.get().apply(); // as an event dispatched by the event loop of a password dialog
        }
        return memory.get();
      }

      @Override public String create() { return memory.create(); }
      @Override public void revoke() { memory.revoke(); }
    }, context, p);
    self.set(nesting);
    nesting.follow();
    try {
      p.putBoolean(McpPreferences.ENABLED, true);
      assertTrue(nesting.running());
      assertEquals(1, context.getServiceReferences(ProposalSource.class, null).size(), "one server, one source");
      p.putBoolean(McpPreferences.ENABLED, false);
      assertFalse(nesting.running());
      assertNull(context.getServiceReference(ProposalSource.class), "no server is left behind");
    } finally {
      p.putBoolean(McpPreferences.ENABLED, false);
      nesting.unfollow();
      p.removeNode();
    }
  }

  @Test
  void switchingOffWhileANewTokenIsMadeLeavesNoToken() throws Exception {
    IEclipsePreferences p = InstanceScope.INSTANCE.getNode("net.aftergit.merge.mcp.regen" + System.nanoTime());
    TokenStore memory = TokenStore.memory();
    AtomicInteger creates = new AtomicInteger();
    AgentServer s = new AgentServer(new TokenStore() {
      @Override public String get() { return memory.get(); }
      @Override public String create() {
        String t = memory.create();
        if (creates.incrementAndGet() == 2) {
          p.putBoolean(McpPreferences.ENABLED, false); // switched off from the event loop of a password dialog
        }
        return t;
      }
      @Override public void revoke() { memory.revoke(); }
    }, context, p);
    s.follow();
    try {
      p.putBoolean(McpPreferences.ENABLED, true);
      assertTrue(s.running());
      assertNull(s.regenerateToken());
      assertFalse(s.running());
      assertNull(memory.get(), "no token while off");
      assertNull(context.getServiceReference(ProposalSource.class));
    } finally {
      p.putBoolean(McpPreferences.ENABLED, false);
      s.unfollow();
      p.removeNode();
    }
  }
}
