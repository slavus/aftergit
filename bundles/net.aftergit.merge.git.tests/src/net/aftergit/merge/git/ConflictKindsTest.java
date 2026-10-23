package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictKindsTest {
  @TempDir
  File dir;
  Repository repo;

  @AfterEach
  void closeRepository() {
    if (repo != null) {
      repo.close();
    }
  }

  @Test
  void aContentConflictIsText() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    assertEquals(ConflictKind.TEXT, ConflictKinds.classify(repo, "a.txt"));
    assertTrue(ConflictKind.TEXT.opensEditor());
  }

  @Test
  void addAddIsText() throws Exception {
    repo = TestRepos.addAdd(dir, "n.txt", "x\ny\n", "x\nz\n");
    assertEquals(ConflictKind.TEXT, ConflictKinds.classify(repo, "n.txt"));
  }

  @Test
  void modifyDeleteAndDeleteModify() throws Exception {
    repo = TestRepos.modifyDelete(dir, "a.txt", "a\n", "a changed\n", false);
    assertEquals(ConflictKind.MODIFY_DELETE, ConflictKinds.classify(repo, "a.txt"));
    repo.close();
    File other = new File(dir, "other");
    repo = TestRepos.modifyDelete(other, "a.txt", "a\n", "a changed\n", true);
    assertEquals(ConflictKind.DELETE_MODIFY, ConflictKinds.classify(repo, "a.txt"));
    assertTrue(ConflictKind.DELETE_MODIFY.offersSides());
  }

  @Test
  void aLoneCarriageReturnIsStillText() throws Exception {
    repo = TestRepos.binary(dir, "t.txt", "a\rb\n".getBytes(), "a\rc\n".getBytes(), "a\rd\n".getBytes());
    assertEquals(ConflictKind.TEXT, ConflictKinds.classify(repo, "t.txt"));
  }

  @Test
  void aBinaryConflict() throws Exception {
    repo = TestRepos.binary(dir, "b.bin", new byte[] {0, 1, '\n'}, new byte[] {0, 2, '\n'}, new byte[] {0, 3, '\n'});
    assertEquals(ConflictKind.BINARY, ConflictKinds.classify(repo, "b.bin"));
    assertTrue(ConflictKind.BINARY.offersSides());
    assertFalse(ConflictKind.BINARY.opensEditor());
  }

  @Test
  void submoduleSymlinkAndRenameNeedTheMergeTool() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    TestRepos.fakeStages(repo, "sub", FileMode.GITLINK, DirCacheEntry.STAGE_1, DirCacheEntry.STAGE_2,
        DirCacheEntry.STAGE_3);
    TestRepos.fakeStages(repo, "link", FileMode.SYMLINK, DirCacheEntry.STAGE_2, DirCacheEntry.STAGE_3);
    TestRepos.fakeStages(repo, "moved.txt", FileMode.REGULAR_FILE, DirCacheEntry.STAGE_1);
    TestRepos.fakeStages(repo, "ours-only.txt", FileMode.REGULAR_FILE, DirCacheEntry.STAGE_2);
    assertEquals(ConflictKind.SUBMODULE, ConflictKinds.classify(repo, "sub"));
    assertEquals(ConflictKind.SYMLINK, ConflictKinds.classify(repo, "link"));
    assertEquals(ConflictKind.RENAME, ConflictKinds.classify(repo, "moved.txt"));
    assertEquals(ConflictKind.RENAME, ConflictKinds.classify(repo, "ours-only.txt"));
    for (ConflictKind k : List.of(ConflictKind.SUBMODULE, ConflictKind.SYMLINK, ConflictKind.RENAME)) {
      assertTrue(k.needsMergeTool(), k.name());
      assertFalse(k.offersSides(), k.name());
    }
  }

  @Test
  void pathsWithoutConflictAreNone() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    assertEquals(ConflictKind.NONE, ConflictKinds.classify(repo, "missing.txt"));
  }

  @Test
  void conflictPathsListsEachPathOnce() throws Exception {
    repo = TestRepos.conflicts(dir, new java.util.LinkedHashMap<>(Map.of(
        "z.txt", new String[] {"z\n", "Z1\n", "Z2\n"},
        "d/a.txt", new String[] {"a\n", "A1\n", "A2\n"})));
    assertEquals(List.of("d/a.txt", "z.txt"), ConflictPaths.list(repo));
  }
}
