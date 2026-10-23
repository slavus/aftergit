package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictScopeTest {
  @TempDir
  File dir;
  Repository repo;
  IProject project;

  SystemReader original;

  @BeforeEach
  void isolateFromUserConfig() {
    original = TestRepos.isolateUserConfig(dir);
  }

  IProject linking;

  @AfterEach
  void cleanUp() throws Exception {
    SystemReader.setInstance(original);
    TestRepos.waitForJobs();
    if (linking != null && linking.exists()) {
      linking.delete(true, true, null);
    }
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
    if (repo != null) {
      repo.close();
    }
    RepositoryCache.clear();
  }

  @Test
  void aFolderGivesItsConflictsAndTheRepositoryAllOfThem() throws Exception {
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("src/a.txt", new String[] {"a\n", "A1\n", "A2\n"});
    files.put("src/deep/b.txt", new String[] {"b\n", "B1\n", "B2\n"});
    files.put("doc/c.txt", new String[] {"c\n", "C1\n", "C2\n"});
    repo = TestRepos.conflicts(dir, files);
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    // the project covers only "src": "doc" is outside the workspace
    project = ws.getRoot().getProject("scope-test");
    IProjectDescription d = ws.newProjectDescription(project.getName());
    d.setLocation(Path.fromOSString(new File(dir, "src").getAbsolutePath()));
    project.create(d, null);
    project.open(null);

    ConflictScope.Scope folder = ConflictScope.collect(List.of(project.getFolder("deep")), false);
    assertEquals(List.of(project.getFile("deep/b.txt")), folder.files());
    assertEquals(List.of(), folder.outside());

    ConflictScope.Scope whole = ConflictScope.collect(List.of(project.getFile("a.txt")), true);
    assertEquals(List.of(project.getFile("a.txt"), project.getFile("deep/b.txt")), whole.files());
    assertEquals(List.of(dir.getName() + "/doc/c.txt"), whole.outside());
  }

  @Test
  void aProjectAtTheTopOfTheRepositoryGivesAllItsConflicts() throws Exception {
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("a.txt", new String[] {"a\n", "A1\n", "A2\n"});
    files.put("src/b.txt", new String[] {"b\n", "B1\n", "B2\n"});
    repo = TestRepos.conflicts(dir, files);
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject("scope-root-test");
    IProjectDescription d = ws.newProjectDescription(project.getName());
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);

    ConflictScope.Scope scope = ConflictScope.collect(List.of(project), false);
    assertEquals(List.of(project.getFile("a.txt"), project.getFile("src/b.txt")), scope.files());
    assertEquals(List.of(), scope.outside());
  }

  @Test
  void aFileReachableOnlyThroughALinkedFolderIsInTheWorkspace() throws Exception {
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("src/a.txt", new String[] {"a\n", "A1\n", "A2\n"});
    repo = TestRepos.conflicts(dir, files);
    File elsewhere = new File(dir.getParentFile(), dir.getName() + "-linking");
    elsewhere.mkdirs();
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    linking = ws.getRoot().getProject("scope-linking-test");
    IProjectDescription d = ws.newProjectDescription(linking.getName());
    d.setLocation(Path.fromOSString(elsewhere.getAbsolutePath()));
    linking.create(d, null);
    linking.open(null);
    org.eclipse.core.resources.IFolder link = linking.getFolder("lnk");
    link.createLink(Path.fromOSString(new File(dir, "src").getAbsolutePath()), 0, null);

    ConflictScope.Scope scope = ConflictScope.collect(List.of(link), false);
    assertEquals(List.of(link.getFile("a.txt")), scope.files());
    assertEquals(List.of(), scope.outside());
  }
}
