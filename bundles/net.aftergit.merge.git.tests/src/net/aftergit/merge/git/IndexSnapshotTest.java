package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexSnapshotTest {
  @TempDir
  File dir;
  Repository repo;

  @AfterEach
  void closeRepository() {
    if (repo != null) {
      repo.close();
    }
  }

  private static String stages(Repository repo, String path) throws Exception {
    DirCache c = repo.readDirCache();
    StringBuilder sb = new StringBuilder();
    for (int i = Math.max(0, c.findEntry(path)); i < c.getEntryCount(); i++) {
      DirCacheEntry e = c.getEntry(i);
      if (e.getPathString().equals(path)) {
        sb.append(e.getStage()).append(':').append(e.getObjectId().name()).append(' ');
      }
    }
    return sb.toString().trim();
  }

  @Test
  void restoreBringsBackTheConflictStages() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    String before = stages(repo, "a.txt");
    IndexSnapshot snap = IndexSnapshot.take(repo, List.of("a.txt"));
    TestRepos.write(dir, "a.txt", "a\nmerged\n");
    GitIndex.markResolved(repo, "a.txt");
    assertFalse(ConflictState.isConflicted(repo, "a.txt"));
    snap.restore();
    assertTrue(ConflictState.isConflicted(repo, "a.txt"));
    assertEquals(before, stages(repo, "a.txt"));
    assertTrue(before.startsWith("1:") && before.contains(" 2:") && before.contains(" 3:"), before);
  }

  @Test
  void restoreLeavesOtherPathsAlone() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    IndexSnapshot snap = IndexSnapshot.take(repo, List.of("a.txt"));
    TestRepos.write(dir, "other.txt", "new\n");
    Git.wrap(repo).add().addFilepattern("other.txt").call();
    String other = stages(repo, "other.txt");
    GitIndex.markResolved(repo, "a.txt");
    snap.restore();
    assertEquals(other, stages(repo, "other.txt"));
    assertTrue(ConflictState.isConflicted(repo, "a.txt"));
  }
}
