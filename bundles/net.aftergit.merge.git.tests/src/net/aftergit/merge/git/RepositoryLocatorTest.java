package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryLocatorTest {
  @TempDir
  File dir;
  IProject project;
  Repository created;
  Repository located;

  @AfterEach
  void cleanUp() throws Exception {
    if (located != null) {
      RepositoryCache.close(located);
    }
    if (created != null) {
      created.close();
    }
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
  }

  @Test
  void locatesRepositoryOfUnsharedProjectFile() throws Exception {
    created = TestRepos.conflict(dir, "src/a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject("locator-test");
    IProjectDescription d = ws.newProjectDescription("locator-test");
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    IFile file = project.getFile("src/a.txt");

    RepoFile rf = RepositoryLocator.locate(file).orElseThrow();
    located = rf.repository();
    assertEquals("src/a.txt", rf.path());
    assertEquals(new File(dir, "src/a.txt").getCanonicalFile(), rf.file().getCanonicalFile());
    assertTrue(ConflictState.isConflicted(rf.repository(), rf.path()));
  }
}
