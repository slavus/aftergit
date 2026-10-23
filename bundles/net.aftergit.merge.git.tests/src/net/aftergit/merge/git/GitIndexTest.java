package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIndexTest {
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
  void markResolvedStagesTheFile() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    TestRepos.write(dir, "a.txt", "a\nmerged\n");
    GitIndex.markResolved(repo, "a.txt");
    assertFalse(ConflictState.isConflicted(repo, "a.txt"));
  }
}
