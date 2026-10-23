package net.aftergit.merge.mcp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.mcp.McpRepos;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §2 and §5: proposals per file, on disk under .git/aftergit, discarded when the conflict changes. */
class ProposalStoreTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
  @TempDir
  File dir;
  SystemReader original;
  Repository repo;
  RepoFile file;

  @BeforeEach
  void setUp() throws Exception {
    original = McpRepos.isolateUserConfig(dir);
    repo = McpRepos.conflict(dir, "src/a.txt", McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS);
    file = McpRepos.repoFile(repo, "src/a.txt");
  }

  @AfterEach
  void tearDown() throws Exception {
    McpRepos.cleanUp(original, dir, repo);
  }

  static Proposal proposal(int index, String text) {
    return new Proposal(new BlockId(index, "0123456789ab"), text, "why", "test-agent", T);
  }

  Fingerprint now() throws Exception {
    return Fingerprint.of(file);
  }

  @Test
  void nothingIsWrittenUntilAProposalIsPut() throws Exception {
    ProposalStore store = new ProposalStore();
    assertEquals(new ProposalStore.Loaded(List.of(), false), store.load(file, now(), false));
    assertFalse(Files.exists(new File(repo.getDirectory(), "aftergit").toPath()));
  }

  @Test
  void theFileIsNamedByTheSha1OfThePathUnderTheGitDirectory() throws Exception {
    Path p = ProposalStore.path(file);
    assertEquals(repo.getDirectory().toPath().resolve("aftergit/proposals"), p.getParent());
    assertEquals(Fingerprint.sha1("src/a.txt".getBytes(StandardCharsets.UTF_8)) + ".json", p.getFileName().toString());
  }

  @Test
  void aProposalSurvivesARestart() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "L1\nR1\n"), false);
    assertTrue(Files.isRegularFile(ProposalStore.path(file)));
    ProposalStore.Loaded l = new ProposalStore().load(file, now(), false);
    assertFalse(l.stale());
    assertEquals(List.of(proposal(0, "L1\nR1\n")), l.proposals());
  }

  @Test
  void aSecondProposalForTheSameBlockReplacesTheFirst() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "one\n"), false);
    store.put(file, now(), proposal(0, "two\n"), false);
    assertEquals(List.of(proposal(0, "two\n")), store.load(file, now(), false).proposals());
  }

  @Test
  void identicalConflictsKeepOneProposalEach() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "first\n"), false);
    store.put(file, now(), proposal(1, "second\n"), false);
    assertEquals(2, new ProposalStore().load(file, now(), false).proposals().size());
  }

  @Test
  void aChangedConflictMakesTheProposalsStaleAndDeletesTheFile() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    McpRepos.remerge(repo, "src/a.txt", "a\nR9\nc\nR3\ne\n");
    ProposalStore store = new ProposalStore();
    ProposalStore.Loaded l = store.load(file, now(), false);
    assertTrue(l.stale());
    assertEquals(List.of(), l.proposals());
    assertFalse(Files.exists(ProposalStore.path(file)));
    assertFalse(store.load(file, now(), false).stale(), "reported once");
  }

  @Test
  void aResolvedConflictMakesTheProposalsStale() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    Git.wrap(repo).add().addFilepattern("src/a.txt").call();
    assertTrue(store.load(file, now(), true).stale());
  }

  @Test
  void savingAClosedFileMakesItsProposalsStaleButNotWhileItIsOpen() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    McpRepos.write(dir, "src/a.txt", "saved in the resolver\n");
    ProposalStore.Loaded open = store.load(file, now(), true);
    assertFalse(open.stale());
    assertEquals(1, open.proposals().size());
    assertFalse(new ProposalStore().load(file, now(), false).stale(), "the stored fingerprint followed the save");
    McpRepos.write(dir, "src/a.txt", "changed outside\n");
    assertTrue(new ProposalStore().load(file, now(), false).stale());
  }

  @Test
  void putReportsThatEarlierProposalsWereStale() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    McpRepos.write(dir, "src/a.txt", "changed outside\n");
    assertTrue(store.put(file, now(), proposal(1, "y\n"), false));
    assertEquals(List.of(proposal(1, "y\n")), store.load(file, now(), false).proposals());
  }

  @Test
  void removeDeletesTheFileWithTheLastProposal() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    store.put(file, now(), proposal(1, "y\n"), false);
    assertTrue(store.remove(file, proposal(0, "").block()));
    assertFalse(store.remove(file, proposal(0, "").block()));
    assertTrue(Files.exists(ProposalStore.path(file)));
    assertTrue(store.remove(file, proposal(1, "").block()));
    assertFalse(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void proposalsAnEditorWasOfferedAreRememberedAcrossARestart() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), true);
    store.put(file, now(), proposal(1, "y\n"), true);
    assertEquals(Set.of(), store.load(file, now(), true).shown());
    store.markShown(file, List.of(proposal(0, "").block(), new BlockId(7, "0123456789ab")));
    assertEquals(Set.of(proposal(0, "").block()), store.load(file, now(), true).shown());
    ProposalStore.Loaded again = new ProposalStore().load(file, now(), true);
    assertEquals(Set.of(proposal(0, "").block()), again.shown());
    assertEquals(2, again.proposals().size());
  }

  @Test
  void aNewProposalForAShownBlockIsNotShownYet() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), true);
    store.markShown(file, List.of(proposal(0, "").block()));
    store.put(file, now(), proposal(0, "other\n"), true);
    assertEquals(Set.of(), new ProposalStore().load(file, now(), true).shown());
  }

  @Test
  void aResolvedFileLosesItsRecord() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), true);
    store.put(file, now(), proposal(1, "y\n"), true);
    store.resolved(file);
    assertFalse(Files.exists(ProposalStore.path(file)));
    assertEquals(List.of(), store.load(file, now(), false).proposals());
    assertFalse(store.load(file, now(), false).stale(), "nothing left to report");
  }

  @Test
  void theSweepDeletesOnlyTheRecordsOfPathsThatAreNoLongerConflicted() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    assertEquals(0, store.sweepResolved(List.of(repo)));
    assertTrue(Files.exists(ProposalStore.path(file)), "still conflicted: kept");
    Git.wrap(repo).add().addFilepattern("src/a.txt").call();
    assertEquals(1, store.sweepResolved(List.of(repo)));
    assertFalse(Files.exists(ProposalStore.path(file)));
    assertFalse(Files.exists(ProposalStore.path(file).getParent()), "the empty folder goes too");
  }

  @Test
  void aClosedStoreNeitherSweepsNorDropsAResolvedFile() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    Git.wrap(repo).add().addFilepattern("src/a.txt").call();
    store.close();
    store.resolved(file);
    assertEquals(0, store.sweepResolved(List.of(repo)));
    assertTrue(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void droppingTheMemoryKeepsTheFiles() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    store.dropMemory();
    assertEquals(1, store.load(file, now(), false).proposals().size());
  }

  @Test
  void anUnreadableFileIsStale() throws Exception {
    Files.createDirectories(ProposalStore.path(file).getParent());
    Files.writeString(ProposalStore.path(file), "{not json", StandardCharsets.UTF_8);
    assertTrue(new ProposalStore().load(file, now(), false).stale());
    assertFalse(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void aRecordWithAnOversizedTextIsDiscarded() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    Path p = ProposalStore.path(file);
    Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8).replace("\"x\\n\"",
        "\"" + "x".repeat(ProposalStore.MAX_TEXT + 1) + "\""), StandardCharsets.UTF_8);
    ProposalStore.Loaded l = new ProposalStore().load(file, now(), false);
    assertTrue(l.stale());
    assertEquals(List.of(), l.proposals());
    assertFalse(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void aTextAtTheLimitIsKept() throws Exception {
    String text = "x".repeat(ProposalStore.MAX_TEXT - 1) + "\n";
    new ProposalStore().put(file, now(), proposal(0, text), false);
    assertEquals(1, new ProposalStore().load(file, now(), false).proposals().size());
  }

  @Test
  void anOversizedFileIsDiscardedUnread() throws Exception {
    Files.createDirectories(ProposalStore.path(file).getParent());
    Files.write(ProposalStore.path(file), new byte[(int) ProposalStore.MAX_RECORD_BYTES + 1]);
    assertTrue(new ProposalStore().load(file, now(), false).stale());
    assertFalse(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void aFileThatIsNotUtf8IsDiscarded() throws Exception {
    Files.createDirectories(ProposalStore.path(file).getParent());
    Files.write(ProposalStore.path(file), new byte[] {'{', (byte) 0xff, (byte) 0xfe, '}'});
    assertTrue(new ProposalStore().load(file, now(), false).stale());
    assertFalse(Files.exists(ProposalStore.path(file)));
  }

  @Test
  void aRecordWithAMalformedBlockIdIsDiscarded() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    Path p = ProposalStore.path(file);
    Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8).replace("0:0123456789ab", "nonsense"),
        StandardCharsets.UTF_8);
    assertTrue(new ProposalStore().load(file, now(), false).stale());
    assertFalse(Files.exists(p));
  }

  @Test
  void deleteAllRemovesEveryStoredFile() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    assertEquals(1, ProposalStore.deleteAll(List.of(repo)));
    assertFalse(Files.exists(new File(repo.getDirectory(), "aftergit").toPath()));
    assertEquals(0, ProposalStore.deleteAll(List.of(repo)));
  }

  @Test
  void putRefusesATextOverTheLimitAndKeepsTheOthers() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    assertThrows(IllegalArgumentException.class,
        () -> store.put(file, now(), proposal(1, "x".repeat(ProposalStore.MAX_TEXT) + "\n"), false));
    assertEquals(List.of(proposal(0, "x\n")), store.load(file, now(), false).proposals());
    assertEquals(List.of(proposal(0, "x\n")), new ProposalStore().load(file, now(), false).proposals());
  }

  @Test
  void deleteAllAlsoEmptiesTheMemoryOfALiveStore() throws Exception {
    ProposalStore store = new ProposalStore();
    store.put(file, now(), proposal(0, "x\n"), false);
    assertEquals(1, ProposalStore.deleteAll(List.of(repo)));
    assertEquals(List.of(), store.load(file, now(), false).proposals());
    store.put(file, now(), proposal(1, "y\n"), false);
    assertEquals(List.of(proposal(1, "y\n")), new ProposalStore().load(file, now(), false).proposals());
  }

  @Test
  void deleteAllAlsoRemovesLeftoverTempFiles() throws Exception {
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    Files.writeString(ProposalStore.path(file).resolveSibling("proposals123.tmp"), "{", StandardCharsets.UTF_8);
    assertEquals(1, ProposalStore.deleteAll(List.of(repo)));
    assertFalse(Files.exists(new File(repo.getDirectory(), "aftergit").toPath()));
  }

  @Test
  void anOldLeftoverTempFileGoesWithTheNextWrite() throws Exception {
    Path dirOf = ProposalStore.path(file).getParent();
    Files.createDirectories(dirOf);
    Path old = dirOf.resolve("proposals1.tmp");
    Path young = dirOf.resolve("proposals2.tmp");
    Files.writeString(old, "{", StandardCharsets.UTF_8);
    Files.writeString(young, "{", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));
    new ProposalStore().put(file, now(), proposal(0, "x\n"), false);
    assertFalse(Files.exists(old));
    assertTrue(Files.exists(young), "a fresh one may belong to a write in progress elsewhere");
  }

  @Test
  void deleteAllAndPutDoNotRace() throws Exception {
    ProposalStore store = new ProposalStore();
    Fingerprint fp = now();
    java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
    Thread deleter = new Thread(() -> {
      try {
        for (int i = 0; i < 300; i++) {
          ProposalStore.deleteAll(List.of(repo));
        }
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
      }
    });
    deleter.start();
    for (int i = 0; i < 300; i++) {
      store.put(file, fp, proposal(i % 3, "x" + i + "\n"), false);
    }
    deleter.join();
    assertNull(failure.get());
    ProposalStore.deleteAll(List.of(repo));
    assertEquals(List.of(), store.load(file, fp, false).proposals());
  }

  @Test
  void aClosedStoreNeitherReadsNorWritesTheFiles() throws Exception {
    new ProposalStore().put(file, new Fingerprint("x", "x", "x", "x"), proposal(0, "x\n"), false);
    String stored = Files.readString(ProposalStore.path(file));
    ProposalStore store = new ProposalStore();
    store.close();
    assertEquals(new ProposalStore.Loaded(List.of(), false), store.load(file, now(), true));
    assertThrows(java.io.IOException.class, () -> store.put(file, now(), proposal(1, "y\n"), false));
    assertFalse(store.remove(file, proposal(0, "x\n").block()), "a discard after the switch went off: no failure");
    assertEquals(stored, Files.readString(ProposalStore.path(file)), "the stale record stays untouched");
  }
}
