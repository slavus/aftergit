package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.git.GitIndex;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/INSTALL.md, "Git Staging refreshes at once": Git Staging shows a file staged by AfterGit at once, also when the project is not
 * shared with EGit and AfterGit works on another JGit instance of the repository than EGit's.
 */
class StagingRefreshTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  /** EGit UI's rescan of repositories for changes made outside EGit, in seconds; 0 turns it off. */
  static final String EGIT_RESCAN = "refesh_index_interval";
  String rescanBefore;
  String autoShareBefore;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    // the rescan finds any index change sooner or later: only an event tells EGit at once
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_UI);
    rescanBefore = egit.get(EGIT_RESCAN, null);
    egit.putInt(EGIT_RESCAN, 0);
    // the projects stay unshared: EGit would share projects of repositories it knows
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_CORE);
    autoShareBefore = core.get(MergeToolCommandTest.EGIT_AUTO_SHARE, null);
    core.putBoolean(MergeToolCommandTest.EGIT_AUTO_SHARE, false);
  }

  @AfterEach
  void tearDown() throws Exception {
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_CORE);
    if (autoShareBefore == null) {
      core.remove(MergeToolCommandTest.EGIT_AUTO_SHARE);
    } else {
      core.put(MergeToolCommandTest.EGIT_AUTO_SHARE, autoShareBefore);
    }
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(MergeToolCommandTest.EGIT_UI);
    if (rescanBefore == null) {
      egit.remove(EGIT_RESCAN);
    } else {
      egit.put(EGIT_RESCAN, rescanBefore);
    }
    repos.dispose();
  }

  /** Builds of earlier tests' projects are over, so EGit's refresh job is not queued behind them. */
  static void quiet() {
    ConflictRepos.waitUntil(() -> org.eclipse.core.runtime.jobs.Job.getJobManager()
        .find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD).length == 0, 60, "the builds");
  }

  /** EGit's index state of the repository, the one its Staging view lists. */
  private static IndexDiffData diff(IndexDiffCacheEntry entry) {
    return entry.getIndexDiff();
  }

  @Test
  void saveAndMarkResolvedReachesEGitsOwnRepositoryAtOnce() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("staging-refresh", "a.txt"); // not shared: AfterGit finds the repository itself
    Repository egit = RepositoryCache.INSTANCE.lookupRepository(new File(dir, ".git")); // as Git Staging holds it
    assertNotSame(egit, RepositoryLocator.locate(file).orElseThrow().repository(), "two instances of one repository");
    IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(egit);
    ConflictRepos.waitUntil(() -> diff(entry) != null && diff(entry).getConflicting().contains("a.txt"), 20,
        "EGit to see the conflict");
    quiet();
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    ConflictRepos.waitUntil(() -> !diff(entry).getConflicting().contains("a.txt")
        && diff(entry).getChanged().contains("a.txt"), 5, "EGit to see the file staged");
  }

  /**
   * Staging without a change of the file (Resolve All stages files it wrote before, when the user says so): only the
   * index changes, which EGit learns from an event on its own instance of the repository.
   */
  @Test
  void stagingAWrittenFileLaterReachesEGitAtOnce() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("staging-later", "a.txt");
    Repository egit = RepositoryCache.INSTANCE.lookupRepository(new File(dir, ".git"));
    IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(egit);
    repos.write("a.txt", "a\nR\nc\n");
    file.refreshLocal(0, null);
    ConflictRepos.waitUntil(() -> diff(entry) != null && diff(entry).getConflicting().contains("a.txt"), 20,
        "EGit to see the conflict");
    quiet();
    long until = System.nanoTime() + 1_500_000_000L; // EGit's refreshes after the write are over
    ConflictRepos.waitUntil(() -> System.nanoTime() > until, 5, "a moment");
    var rf = RepositoryLocator.locate(file).orElseThrow();
    GitIndex.markResolved(rf.repository(), rf.path());
    ConflictRepos.waitUntil(() -> !diff(entry).getConflicting().contains("a.txt")
        && diff(entry).getChanged().contains("a.txt"), 5, "EGit to see the file staged");
  }

  /**
   * EGit relays index events of other JGit instances to its own only when both name the repository by the same path:
   * a workspace reached through a symbolic link names it otherwise than the Git Repositories view.
   */
  @Test
  void aRepositoryReachedThroughASymbolicLinkStillReachesEGitAtOnce() throws Exception {
    File real = new File(dir, "real");
    real.mkdirs();
    File link = new File(dir, "link");
    java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath());
    ConflictRepos linked = new ConflictRepos(link);
    try {
      linked.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
      IFile file = linked.plainProject("staging-link", "a.txt");
      Repository egit = RepositoryCache.INSTANCE.lookupRepository(new File(real, ".git"));
      IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(egit);
      ConflictRepos.waitUntil(() -> diff(entry) != null && diff(entry).getConflicting().contains("a.txt"), 20,
          "EGit to see the conflict");
      quiet();
      MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
      ConflictRepos.waitUntil(() -> !diff(entry).getConflicting().contains("a.txt")
          && diff(entry).getChanged().contains("a.txt"), 5, "EGit to see the file staged");
    } finally {
      linked.dispose();
    }
  }

  @Test
  void stagingLaterThroughASymbolicLinkStillReachesEGitAtOnce() throws Exception {
    File real = new File(dir, "real");
    real.mkdirs();
    File link = new File(dir, "link");
    java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath());
    ConflictRepos linked = new ConflictRepos(link);
    try {
      linked.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
      IFile file = linked.plainProject("staging-link-later", "a.txt");
      Repository egit = RepositoryCache.INSTANCE.lookupRepository(new File(real, ".git"));
      IndexDiffCacheEntry entry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(egit);
      linked.write("a.txt", "a\nR\nc\n");
      file.refreshLocal(0, null);
      ConflictRepos.waitUntil(() -> diff(entry) != null && diff(entry).getConflicting().contains("a.txt"), 20,
          "EGit to see the conflict");
      quiet();
      long until = System.nanoTime() + 1_500_000_000L; // EGit's refreshes after the write are over
      ConflictRepos.waitUntil(() -> System.nanoTime() > until, 5, "a moment");
      var rf = RepositoryLocator.locate(file).orElseThrow();
      GitIndex.markResolved(rf.repository(), rf.path());
      ConflictRepos.waitUntil(() -> !diff(entry).getConflicting().contains("a.txt")
          && diff(entry).getChanged().contains("a.txt"), 5, "EGit to see the file staged");
    } finally {
      linked.dispose();
    }
  }
}
