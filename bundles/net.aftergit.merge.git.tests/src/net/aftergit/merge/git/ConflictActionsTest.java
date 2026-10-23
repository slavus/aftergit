package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictActionsTest {
  @TempDir
  File dir;
  Repository repo;

  @AfterEach
  void closeRepository() {
    if (repo != null) {
      repo.close();
    }
  }

  private interface Action {
    void run() throws Exception;
  }

  /** Not assertThrows: its Executable type is not visible to the test bundle. */
  private static void refused(Action a) throws Exception {
    try {
      a.run();
    } catch (IllegalStateException expected) {
      return;
    }
    fail("expected IllegalStateException");
  }

  @Test
  void keepOursOfModifyDeleteKeepsTheChangedFile() throws Exception {
    repo = TestRepos.modifyDelete(dir, "a.txt", "a\n", "a changed\n", false);
    ConflictActions.keep(repo, "a.txt", ConflictActions.Side.OURS);
    assertEquals("a changed\n", Files.readString(new File(dir, "a.txt").toPath()));
    assertFalse(ConflictState.isConflicted(repo, "a.txt"));
    assertNotNull(repo.readDirCache().getEntry("a.txt"), "staged");
  }

  @Test
  void keepTheirsOfModifyDeleteDeletesTheFile() throws Exception {
    repo = TestRepos.modifyDelete(dir, "a.txt", "a\n", "a changed\n", false);
    ConflictActions.keep(repo, "a.txt", ConflictActions.Side.THEIRS);
    assertFalse(new File(dir, "a.txt").exists());
    assertNull(repo.readDirCache().getEntry("a.txt"));
    assertEquals(ConflictKind.NONE, ConflictKinds.classify(repo, "a.txt"));
  }

  @Test
  void keepTheirsOfABinaryWritesTheirBytesAndStagesThem() throws Exception {
    byte[] theirs = {0, 3, '\n'};
    repo = TestRepos.binary(dir, "b.bin", new byte[] {0, 1, '\n'}, new byte[] {0, 2, '\n'}, theirs);
    ConflictActions.keep(repo, "b.bin", ConflictActions.Side.THEIRS);
    assertArrayEquals(theirs, Files.readAllBytes(new File(dir, "b.bin").toPath()));
    assertFalse(ConflictState.isConflicted(repo, "b.bin"));
  }

  @Test
  void deleteRemovesTheFileAndEveryStage() throws Exception {
    repo = TestRepos.binary(dir, "b.bin", new byte[] {0, 1, '\n'}, new byte[] {0, 2, '\n'}, new byte[] {0, 3, '\n'});
    ConflictActions.delete(repo, "b.bin");
    assertFalse(new File(dir, "b.bin").exists());
    assertTrue(repo.readDirCache().findEntry("b.bin") < 0, "no stage of the path is left");
  }

  @Test
  void keepOursOfDeleteModifyDeletesTheFile() throws Exception {
    repo = TestRepos.modifyDelete(dir, "a.txt", "a\n", "a changed\n", true);
    ConflictActions.keep(repo, "a.txt", ConflictActions.Side.OURS);
    assertFalse(new File(dir, "a.txt").exists());
    assertEquals(ConflictKind.NONE, ConflictKinds.classify(repo, "a.txt"));
  }

  @Test
  void keepTheirsOfDeleteModifyKeepsTheChangedFile() throws Exception {
    repo = TestRepos.modifyDelete(dir, "a.txt", "a\n", "a changed\n", true);
    ConflictActions.keep(repo, "a.txt", ConflictActions.Side.THEIRS);
    assertEquals("a changed\n", Files.readString(new File(dir, "a.txt").toPath()));
    assertNotNull(repo.readDirCache().getEntry("a.txt"));
    assertFalse(ConflictState.isConflicted(repo, "a.txt"));
  }

  @Test
  void keepRefusesAStagedNonConflictedPath() throws Exception {
    repo = TestRepos.conflicts(dir, new java.util.LinkedHashMap<>(java.util.Map.of(
        "a.txt", new String[] {"a\n", "A1\n", "A2\n"})));
    TestRepos.write(dir, "ok.txt", "ok\n");
    org.eclipse.jgit.api.Git.wrap(repo).add().addFilepattern("ok.txt").call();
    for (ConflictActions.Side side : ConflictActions.Side.values()) {
      refused(() -> ConflictActions.keep(repo, "ok.txt", side));
    }
    refused(() -> ConflictActions.delete(repo, "ok.txt"));
    assertEquals("ok\n", Files.readString(new File(dir, "ok.txt").toPath()));
    assertNotNull(repo.readDirCache().getEntry("ok.txt"));
  }

  @Test
  void keepRefusesKindsWithoutSides() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    TestRepos.write(dir, "moved.txt", "m\n");
    TestRepos.fakeStages(repo, "moved.txt", org.eclipse.jgit.lib.FileMode.REGULAR_FILE,
        org.eclipse.jgit.dircache.DirCacheEntry.STAGE_2);
    refused(() -> ConflictActions.keep(repo, "moved.txt", ConflictActions.Side.THEIRS));
    refused(() -> ConflictActions.keep(repo, "a.txt", ConflictActions.Side.OURS));
    assertEquals("m\n", Files.readString(new File(dir, "moved.txt").toPath()));
    assertEquals(ConflictKind.RENAME, ConflictKinds.classify(repo, "moved.txt"));
    assertTrue(ConflictState.isConflicted(repo, "a.txt"));
  }
}
