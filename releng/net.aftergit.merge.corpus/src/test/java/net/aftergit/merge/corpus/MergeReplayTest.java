package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeReplayTest {
  @TempDir
  Path dir;

  @BeforeEach
  void needsGit() {
    assumeTrue(GitCli.available(), "git not available");
  }

  private static SyntheticRepos.Scenario conflict(String path) {
    return new SyntheticRepos.Scenario("c", path, "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", "a\nL\nc\n");
  }

  @Test
  void findsTheMergeAndItsConflictedStages() throws Exception {
    SyntheticRepos.create(dir, List.of(conflict("sub dir/a.txt")));
    MergeReplay replay = new MergeReplay(new GitCli(dir));
    List<MergeReplay.MergeCommit> merges = replay.merges(10, List.of());
    assertEquals(1, merges.size());
    MergeReplay.Replayed r = replay.replay(merges.get(0));
    assertEquals(1, r.files().size());
    MergeReplay.ConflictFile f = r.files().get(0);
    assertEquals("sub dir/a.txt", f.path());
    assertTrue(f.contentConflict());
    assertEquals("a\nb\nc\n", new String(replay.blob(f.base().oid()), StandardCharsets.UTF_8));
    assertEquals("a\nL\nc\n", new String(replay.blob(f.ours().oid()), StandardCharsets.UTF_8));
    assertEquals("a\nR\nc\n", new String(replay.blob(f.theirs().oid()), StandardCharsets.UTF_8));
    String working = new String(replay.blobOrNull(r.tree(), f.path()), StandardCharsets.UTF_8);
    assertTrue(working.startsWith("a\n<<<<<<< "), working);
    assertEquals("a\nL\nc\n", new String(replay.blobOrNull(merges.get(0).id(), f.path()), StandardCharsets.UTF_8));
    assertNull(replay.blobOrNull(merges.get(0).id(), "missing.txt"));
  }

  @Test
  void aCleanMergeHasNoConflictedFiles() throws Exception {
    SyntheticRepos.create(dir, List.of(new SyntheticRepos.Scenario("clean", "a.txt", "a\nb\nc\n", "A\nb\nc\n",
        "a\nb\nC\n", "A\nb\nC\n")));
    MergeReplay replay = new MergeReplay(new GitCli(dir));
    assertTrue(replay.replay(replay.merges(10, List.of()).get(0)).files().isEmpty());
  }

  @Test
  void pathsLimitTheMerges() throws Exception {
    SyntheticRepos.create(dir, List.of(conflict("x/a.txt"), conflict("y/b.txt")));
    MergeReplay replay = new MergeReplay(new GitCli(dir));
    assertEquals(2, replay.merges(10, List.of()).size());
    List<MergeReplay.MergeCommit> onlyY = replay.merges(10, List.of("y"));
    assertEquals(1, onlyY.size());
    assertEquals("y/b.txt", replay.replay(onlyY.get(0)).files().get(0).path());
  }

  @Test
  void userConfigurationDoesNotReachGit() throws Exception {
    // a repository-independent setting like the user's merge.conflictStyle must not change git's result
    Path home = Files.createDirectories(dir.resolve("home"));
    Files.writeString(home.resolve(".gitconfig"), "[merge]\n\tconflictStyle = diff3\n");
    Path repo = dir.resolve("repo");
    SyntheticRepos.create(repo, List.of(conflict("a.txt")));
    MergeReplay replay = new MergeReplay(new GitCli(repo, home));
    MergeReplay.Replayed r = replay.replay(replay.merges(10, List.of()).get(0));
    String working = new String(replay.blobOrNull(r.tree(), "a.txt"), StandardCharsets.UTF_8);
    assertFalse(working.contains("|||||||"), working);
  }

  @Test
  void binaryIsTheGitHeuristic() {
    assertTrue(MergeReplay.binary(new byte[] {'a', 0, 'b'}));
    assertFalse(MergeReplay.binary("a\nb\n".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void anUnreadableRepositoryIsNotAMissingFile() throws Exception {
    Path empty = Files.createDirectories(dir.resolve("empty"));
    MergeReplay replay = new MergeReplay(new GitCli(empty));
    assertThrows(java.io.IOException.class, () -> replay.blobOrNull("HEAD", "a.txt"));
  }

  @Test
  void gitErrorMessagesCarryItsStandardError() throws Exception {
    Path repo = dir.resolve("repo");
    SyntheticRepos.create(repo, List.of(conflict("a.txt")));
    java.io.IOException e = assertThrows(java.io.IOException.class, () -> new GitCli(repo).run("cat-file", "-p", "nope"));
    assertTrue(e.getMessage().contains("nope"), e.getMessage());
    assertTrue(e.getMessage().contains("fatal"), e.getMessage());
  }
}
