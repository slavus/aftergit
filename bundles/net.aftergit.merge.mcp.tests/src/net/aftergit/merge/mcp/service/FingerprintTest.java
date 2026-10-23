package net.aftergit.merge.mcp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.mcp.McpRepos;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §2: the conflict fingerprint is the three stages and the working-tree file. */
class FingerprintTest {
  @TempDir
  File dir;
  SystemReader original;
  Repository repo;

  @BeforeEach
  void setUp() throws Exception {
    original = McpRepos.isolateUserConfig(dir);
    repo = McpRepos.conflict(dir, "a.txt", McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS);
  }

  @AfterEach
  void tearDown() throws Exception {
    McpRepos.cleanUp(original, dir, repo);
  }

  @Test
  void aConflictHasThreeStagesAndAWorkingTreeHash() throws Exception {
    Fingerprint f = Fingerprint.of(McpRepos.repoFile(repo, "a.txt"));
    for (String s : new String[] {f.base(), f.ours(), f.theirs()}) {
      assertTrue(s.matches("[0-9a-f]{40}"), s);
    }
    assertTrue(f.workingTree().matches("[0-9a-f]{40}"));
    assertEquals(f, Fingerprint.of(McpRepos.repoFile(repo, "a.txt")));
  }

  @Test
  void savingTheFileChangesOnlyTheWorkingTreePart() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "a.txt");
    Fingerprint before = Fingerprint.of(rf);
    McpRepos.write(dir, "a.txt", "edited\n");
    Fingerprint after = Fingerprint.of(rf);
    assertNotEquals(before, after);
    assertTrue(before.sameStages(after));
  }

  @Test
  void aResolvedFileHasNoStages() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "a.txt");
    Fingerprint before = Fingerprint.of(rf);
    Git.wrap(repo).add().addFilepattern("a.txt").call();
    Fingerprint after = Fingerprint.of(rf);
    assertEquals("-", after.base());
    assertFalse(before.sameStages(after));
  }

  @Test
  void aMissingWorkingTreeFileIsADash() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "a.txt");
    assertTrue(rf.file().delete());
    assertEquals("-", Fingerprint.of(rf).workingTree());
  }

  @Test
  void theWorkingTreeIsHashedAsAStreamWithTheSameValue() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "a.txt");
    McpRepos.write(dir, "a.txt", "0123456789abcdef".repeat(4096) + "tail\n");
    Fingerprint f = Fingerprint.of(rf);
    assertEquals(Fingerprint.sha1(java.nio.file.Files.readAllBytes(rf.file().toPath())), f.workingTree());
    assertEquals(f, Fingerprint.of(rf, repo.readDirCache()));
  }

  @Test
  void aSymbolicLinkInPlaceOfTheFileIsNotFollowed() throws Exception {
    RepoFile rf = McpRepos.repoFile(repo, "a.txt");
    File target = new File(dir, "target.txt");
    McpRepos.write(dir, "target.txt", "elsewhere\n");
    assertTrue(rf.file().delete());
    try {
      java.nio.file.Files.createSymbolicLink(rf.file().toPath(), target.toPath());
    } catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
    }
    assertEquals("-", Fingerprint.of(rf).workingTree());
  }
}
