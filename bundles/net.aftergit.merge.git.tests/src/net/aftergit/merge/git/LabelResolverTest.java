package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelResolverTest {
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
  void mergeLabels() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    Labels l = LabelResolver.resolve(repo);
    assertTrue(l.left().startsWith("main (\"ours\", "), l.left());
    assertTrue(l.right().startsWith("feature (\"theirs\", "), l.right());
    assertTrue(l.left().matches(".*, [0-9a-f]{8}\\)"), l.left());
  }

  @Test
  void cherryPickLabels() throws Exception {
    repo = TestRepos.cherryPickConflict(dir, "a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    Labels l = LabelResolver.resolve(repo);
    assertTrue(l.left().startsWith("main (\"ours\", "), l.left());
    assertTrue(l.right().startsWith("Cherry-picked 'Fix the thing' ("), l.right());
  }
}
