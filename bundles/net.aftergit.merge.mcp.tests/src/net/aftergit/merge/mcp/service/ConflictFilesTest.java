package net.aftergit.merge.mcp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.mcp.McpRepos;
import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §2: only conflicted files of workspace repositories are visible; any other path is refused. */
class ConflictFilesTest {
  @TempDir
  File root;
  File one;
  File two;
  SystemReader original;
  Repository repoOne;
  Repository repoTwo;
  IProject projectOne;
  IProject projectTwo;

  @BeforeEach
  void setUp() throws Exception {
    one = new File(root, "one");
    two = new File(root, "two");
    original = McpRepos.isolateUserConfig(root);
    repoOne = McpRepos.conflicts(one, Map.of("src/a.txt", new String[] {McpRepos.BASE, McpRepos.OURS,
        McpRepos.THEIRS}, "plain.txt", new String[] {"p\n", "p\n", "p\n"}));
    repoTwo = McpRepos.conflict(two, "src/a.txt", McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS);
    projectOne = McpRepos.project("conflict-files-one", one);
  }

  @AfterEach
  void tearDown() throws Exception {
    repoTwo.close();
    McpRepos.cleanUp(original, root, repoOne, projectOne, projectTwo);
  }

  @Test
  void onlyConflictedFilesOfWorkspaceRepositoriesAreVisible() throws Exception {
    var all = ConflictFiles.all();
    assertEquals(1, all.size());
    assertEquals("src/a.txt", all.get(0).repoFile().path());
    assertEquals(ConflictKind.TEXT, all.get(0).kind());
    assertEquals("one", all.get(0).repositoryName());
  }

  @Test
  void anAbsoluteOrRepositoryRelativePathFindsTheFile() throws Exception {
    String abs = new File(one, "src/a.txt").getAbsolutePath();
    assertEquals(abs, ConflictFiles.resolve(abs).absolutePath());
    assertEquals(abs, ConflictFiles.resolve(new File(one, "src/../src/a.txt").getAbsolutePath()).absolutePath());
    assertEquals(abs, ConflictFiles.resolve("src/a.txt").absolutePath());
    assertEquals(abs, ConflictFiles.resolve("./src/a.txt").absolutePath());
    assertEquals(abs, ConflictFiles.resolve("src\\a.txt").absolutePath());
  }

  @Test
  void everyOtherPathIsNotAConflictedFile() {
    for (String p : new String[] {"plain.txt", new File(one, "plain.txt").getAbsolutePath(), "/etc/passwd",
        "../two/src/a.txt", new File(two, "src/a.txt").getAbsolutePath(), "src/missing.txt", "", " "}) {
      AgentException e = assertThrows(AgentException.class, () -> ConflictFiles.resolve(p), p);
      assertFalse(e.getMessage().contains("conflicted in several"), p);
    }
  }

  @Test
  void aRelativePathConflictedInTwoRepositoriesNeedsTheAbsolutePath() throws Exception {
    projectTwo = McpRepos.project("conflict-files-two", two);
    AgentException e = assertThrows(AgentException.class, () -> ConflictFiles.resolve("src/a.txt"));
    assertTrue(e.getMessage().contains("several repositories"), e.getMessage());
    assertEquals(new File(two, "src/a.txt").getAbsolutePath(),
        ConflictFiles.resolve(new File(two, "src/a.txt").getAbsolutePath()).absolutePath());
  }

  @Test
  void aSymbolicLinkToTheFileIsTheFile() throws Exception {
    File link = new File(root, "link.txt");
    try {
      Files.createSymbolicLink(link.toPath(), new File(one, "src/a.txt").toPath());
    } catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
    }
    assertEquals(new File(one, "src/a.txt").getAbsolutePath(), ConflictFiles.resolve(link.getAbsolutePath())
        .absolutePath());
  }

  @Test
  void aFileOutsideEveryProjectOrInAClosedProjectIsNotVisible() throws Exception {
    File three = new File(root, "three");
    Repository repoThree = McpRepos.conflicts(three, Map.of("src/c.txt", new String[] {McpRepos.BASE, McpRepos.OURS,
        McpRepos.THEIRS}, "lib/d.txt", new String[] {McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS}, "top.txt",
        new String[] {McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS}));
    IProject src = McpRepos.project("conflict-files-src", new File(three, "src"));
    IProject lib = McpRepos.project("conflict-files-lib", new File(three, "lib"));
    try {
      lib.close(null);
      var paths = ConflictFiles.all().stream().map(f -> f.absolutePath()).toList();
      assertTrue(paths.contains(new File(three, "src/c.txt").getAbsolutePath()), paths.toString());
      assertFalse(paths.contains(new File(three, "lib/d.txt").getAbsolutePath()), "closed project");
      assertFalse(paths.contains(new File(three, "top.txt").getAbsolutePath()), "outside every project");
      for (String p : new String[] {"top.txt", new File(three, "top.txt").getAbsolutePath(), "lib/d.txt",
          new File(three, "lib/d.txt").getAbsolutePath()}) {
        AgentException e = assertThrows(AgentException.class, () -> ConflictFiles.resolve(p), p);
        assertTrue(e.getMessage().contains("not a conflicted file"), p);
      }
      assertEquals(new File(three, "src/c.txt").getAbsolutePath(), ConflictFiles.resolve("src/c.txt")
          .absolutePath());
    } finally {
      src.delete(false, true, null);
      lib.delete(false, true, null);
      repoThree.close();
    }
  }
}
