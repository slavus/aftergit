package net.aftergit.merge.git;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/** Builds throw-away repositories with real merge conflicts. */
final class TestRepos {
  // JGit measures the timestamp resolution of a file store once per JVM with a probe file in the first repository it
  // meets (.git/objects); in the background that probe can outlive the test and keep its temporary directory from
  // being deleted, so measure in the foreground before any repository is made
  static {
    FS.FileStoreAttributes.setBackground(false);
  }

  private TestRepos() {}

  /** An empty repository at {@code dir} on branch main, for tests that build their own history. */
  static Git init(File dir) throws Exception {
    return Git.init().setDirectory(dir).setInitialBranch("main").call();
  }

  static void write(File dir, String path, String content) throws Exception {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
  }

  /** main and feature both change {@code path} differently from {@code base}; HEAD = main, merging feature. */
  static Repository conflict(File dir, String path, String base, String ours, String theirs) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, path, base);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    write(dir, path, theirs);
    git.commit().setAll(true).setMessage("feature change").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.checkout().setName("main").call();
    write(dir, path, ours);
    git.commit().setAll(true).setMessage("main change").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    MergeResult r = git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'")
        .call();
    if (r.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
      throw new IllegalStateException("expected a conflict, got " + r.getMergeStatus());
    }
    return git.getRepository();
  }

  /** Both branches add {@code path} with different content (no common ancestor version). */
  static Repository addAdd(File dir, String path, String ours, String theirs) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, "README", "r\n");
    git.add().addFilepattern("README").call();
    git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    write(dir, path, theirs);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("add on feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.checkout().setName("main").call();
    write(dir, path, ours);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("add on main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.merge().include(git.getRepository().resolve("feature")).call();
    return git.getRepository();
  }

  /** HEAD = main; cherry-picking the feature commit conflicts. */
  static Repository cherryPickConflict(File dir, String path, String base, String ours, String theirs)
      throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, path, base);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    write(dir, path, theirs);
    var picked = git.commit().setAll(true).setMessage("Fix the thing").setAuthor("t", "t@t")
        .setCommitter("t", "t@t").call();
    git.checkout().setName("main").call();
    write(dir, path, ours);
    git.commit().setAll(true).setMessage("main change").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.cherryPick().include(picked).call();
    return git.getRepository();
  }

  static void writeBytes(File dir, String path, byte[] content) throws Exception {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), content);
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit().setAll(true).setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call();
  }

  private static Repository mergeFeature(Git git) throws Exception {
    MergeResult r = git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'")
        .call();
    if (r.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
      throw new IllegalStateException("expected a conflict, got " + r.getMergeStatus());
    }
    return git.getRepository();
  }

  /** Several files changed on both branches and merged once; {@code files}: path → {base, ours, theirs}. */
  static Repository conflicts(File dir, Map<String, String[]> files) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    for (var e : files.entrySet()) {
      write(dir, e.getKey(), e.getValue()[0]);
    }
    git.add().addFilepattern(".").call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    for (var e : files.entrySet()) {
      write(dir, e.getKey(), e.getValue()[2]);
    }
    commit(git, "feature change");
    git.checkout().setName("main").call();
    for (var e : files.entrySet()) {
      write(dir, e.getKey(), e.getValue()[1]);
    }
    commit(git, "main change");
    return mergeFeature(git);
  }

  /** One branch changes {@code path}, the other deletes it; {@code oursDeletes}: main deletes, feature changes. */
  static Repository modifyDelete(File dir, String path, String base, String changed, boolean oursDeletes)
      throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, path, base);
    git.add().addFilepattern(path).call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    if (oursDeletes) {
      write(dir, path, changed);
    } else {
      git.rm().addFilepattern(path).call();
    }
    commit(git, "feature change");
    git.checkout().setName("main").call();
    if (oursDeletes) {
      git.rm().addFilepattern(path).call();
    } else {
      write(dir, path, changed);
    }
    commit(git, "main change");
    return mergeFeature(git);
  }

  /** A binary {@code path} changed differently on both branches. */
  static Repository binary(File dir, String path, byte[] base, byte[] ours, byte[] theirs) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    writeBytes(dir, path, base);
    git.add().addFilepattern(path).call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    writeBytes(dir, path, theirs);
    commit(git, "feature change");
    git.checkout().setName("main").call();
    writeBytes(dir, path, ours);
    commit(git, "main change");
    return mergeFeature(git);
  }

  /**
   * Replaces the index entries of {@code path} by the given conflict stages with file mode {@code mode} (for kinds
   * JGit's merge does not produce, such as submodule or symbolic-link conflicts).
   */
  static void fakeStages(Repository repo, String path, FileMode mode, int... stages) throws Exception {
    ObjectId id;
    try (ObjectInserter ins = repo.newObjectInserter()) {
      id = ins.insert(Constants.OBJ_BLOB, "x\n".getBytes(StandardCharsets.UTF_8));
      ins.flush();
    }
    DirCache cache = repo.lockDirCache();
    try {
      DirCacheBuilder b = cache.builder();
      for (int i = 0; i < cache.getEntryCount(); i++) {
        if (!cache.getEntry(i).getPathString().equals(path)) {
          b.add(cache.getEntry(i));
        }
      }
      for (int s : stages) {
        DirCacheEntry e = new DirCacheEntry(path, s);
        e.setFileMode(mode);
        e.setObjectId(id);
        b.add(e);
      }
      b.finish();
      b.commit();
    } finally {
      cache.unlock();
    }
  }

  /**
   * Hides the developer's user, system and JGit configuration (which may set merge.conflictStyle or line end
   * conversion) behind empty files next to {@code dir}; returns the reader to put back afterwards.
   */
  static SystemReader isolateUserConfig(File dir) {
    SystemReader original = SystemReader.getInstance();
    File empty = new File(dir.getParentFile(), "no-such-gitconfig-" + System.nanoTime());
    SystemReader.setInstance(new SystemReader.Delegate(original) {
      @Override
      public FileBasedConfig openUserConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
        return new FileBasedConfig(parent, empty, fs);
      }

      @Override
      public FileBasedConfig openSystemConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
        return new FileBasedConfig(parent, empty, fs);
      }

      @Override
      public FileBasedConfig openJGitConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
        return new FileBasedConfig(parent, empty, fs);
      }
    });
    return original;
  }

  /** Like {@link #conflict} with byte contents (for texts not valid in the file's charset). */
  static Repository conflictBytes(File dir, String path, byte[] base, byte[] ours, byte[] theirs) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    writeBytes(dir, path, base);
    git.add().addFilepattern(path).call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    writeBytes(dir, path, theirs);
    commit(git, "feature change");
    git.checkout().setName("main").call();
    writeBytes(dir, path, ours);
    commit(git, "main change");
    return mergeFeature(git);
  }

  /**
   * Deletes everything in {@code dir}, retrying files that a late listener (a delayed job sleeps, so
   * {@link #waitForJobs} does not see it) is still writing; the temporary directory itself is left to JUnit.
   */
  static void deleteContents(File dir) throws java.io.IOException {
    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      child.setWritable(true);
      org.eclipse.jgit.util.FileUtils.delete(child, org.eclipse.jgit.util.FileUtils.RECURSIVE
          | org.eclipse.jgit.util.FileUtils.RETRY | org.eclipse.jgit.util.FileUtils.SKIP_MISSING);
    }
  }

  /** Waits (at most 30 s) until no job runs or waits, so none writes into a test directory being deleted. */
  static void waitForJobs() throws InterruptedException {
    long end = System.currentTimeMillis() + 30_000;
    while (!org.eclipse.core.runtime.jobs.Job.getJobManager().isIdle() && System.currentTimeMillis() < end) {
      Thread.sleep(20);
    }
  }
}
