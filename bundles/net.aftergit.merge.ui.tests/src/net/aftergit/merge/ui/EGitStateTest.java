package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.info.GitInfo;
import org.eclipse.egit.core.info.GitItemState;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec §8.3: after Save & Mark Resolved, EGit (and so its Staging view) sees the file resolved right away. */
class EGitStateTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
  }

  /** EGit's own view of the file, the one its Staging view shows; null while EGit has not computed it. */
  private static Boolean egitConflict(IFile file) {
    GitInfo info = Adapters.adapt(file, GitInfo.class);
    GitItemState state = info == null ? null : info.getGitState();
    return state == null ? null : state.hasConflicts();
  }

  @Test
  void markResolvedClearsTheConflictInEGit() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("egit-state", "a.txt");
    new ConnectProviderOperation(file.getProject(), new File(dir, ".git")).execute(new NullProgressMonitor());
    assertNotNull(RepositoryMapping.getMapping(file), "the project is shared with EGit");
    ConflictRepos.waitUntil(() -> Boolean.TRUE.equals(egitConflict(file)), 20, "EGit to see the conflict");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    input.saveAndMarkResolved(new NullProgressMonitor());
    ConflictRepos.waitUntil(() -> Boolean.FALSE.equals(egitConflict(file)), 20, "EGit to see the file resolved");
  }
}
