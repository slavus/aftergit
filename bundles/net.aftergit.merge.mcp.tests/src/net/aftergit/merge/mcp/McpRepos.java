package net.aftergit.merge.mcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.aftergit.merge.git.RepoFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.SystemReader;

/** Throw-away repositories with real merge conflicts, opened as workspace projects (agents tests). */
public final class McpRepos {
  public static final String BASE = "a\nb\nc\nd\ne\n";
  public static final String OURS = "a\nL1\nc\nL3\ne\n";
  public static final String THEIRS = "a\nR1\nc\nR3\ne\n";
  /** A conflict AfterGit's simple pass resolves: {base, ours, theirs}. */
  public static final String[] WORDS = {"x = 0; y = 0;\n", "x = 1; y = 0;\n", "x = 0; y = 2;\n"};

  private McpRepos() {}

  public static void write(File dir, String path, String content) throws Exception {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit().setAll(true).setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call();
  }

  /** main and feature change {@code path} differently from {@code base}; HEAD = main, merging feature. */
  public static Repository conflict(File dir, String path, String base, String ours, String theirs) throws Exception {
    return conflicts(dir, Map.of(path, new String[] {base, ours, theirs}));
  }

  /** Several files changed on both branches and merged once; {@code files}: path → {base, ours, theirs}. */
  public static Repository conflicts(File dir, Map<String, String[]> files) throws Exception {
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
    MergeResult r = git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge feature").call();
    if (r.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
      throw new IllegalStateException("expected a conflict, got " + r.getMergeStatus());
    }
    return git.getRepository();
  }

  /** main changes {@code path}, feature deletes it: a modify/delete conflict. */
  public static Repository modifyDelete(File dir, String path, String base, String changed) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, path, base);
    git.add().addFilepattern(path).call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    git.rm().addFilepattern(path).call();
    commit(git, "feature deletes");
    git.checkout().setName("main").call();
    write(dir, path, changed);
    commit(git, "main change");
    git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge feature").call();
    return git.getRepository();
  }

  /** Throws the merge away, gives feature another version of {@code path}, and merges again: a new conflict. */
  public static void remerge(Repository repo, String path, String newTheirs) throws Exception {
    Git git = Git.wrap(repo);
    git.reset().setMode(ResetCommand.ResetType.HARD).call();
    git.checkout().setName("feature").call();
    write(repo.getWorkTree(), path, newTheirs);
    commit(git, "feature again");
    git.checkout().setName("main").call();
    git.merge().include(repo.resolve("feature")).setMessage("Merge feature again").call();
  }

  /** A project named {@code name} at {@code dir}, default charset UTF-8. */
  public static IProject project(String name, File dir) throws Exception {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    IProject p = ws.getRoot().getProject(name);
    IProjectDescription d = ws.newProjectDescription(name);
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    p.create(d, null);
    p.open(null);
    p.setDefaultCharset("UTF-8", null);
    return p;
  }

  public static RepoFile repoFile(Repository repo, String path) {
    return new RepoFile(repo, path, new File(repo.getWorkTree(), path));
  }

  /** Hides the developer's git configuration behind empty files; returns the reader to put back afterwards. */
  public static SystemReader isolateUserConfig(File dir) {
    FS.FileStoreAttributes.setBackground(false);
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

  /** Waits (at most 30 s) until no job runs or waits. */
  public static void waitForJobs() throws InterruptedException {
    long end = System.currentTimeMillis() + 30_000;
    while (!org.eclipse.core.runtime.jobs.Job.getJobManager().isIdle() && System.currentTimeMillis() < end) {
      Thread.sleep(20);
    }
  }

  public static void deleteContents(File dir) throws java.io.IOException {
    File[] children = dir.listFiles();
    if (children == null) {
      return;
    }
    for (File child : children) {
      child.setWritable(true);
      FileUtils.delete(child, FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
    }
  }

  /** Deletes the projects, closes the repository and empties {@code dir}; puts the git configuration back. */
  public static void cleanUp(SystemReader original, File dir, Repository repo, IProject... projects) throws Exception {
    SystemReader.setInstance(original);
    waitForJobs();
    for (IProject p : projects) {
      if (p != null && p.exists()) {
        p.delete(false, true, null);
      }
    }
    if (repo != null) {
      repo.close();
    }
    RepositoryCache.clear();
    waitForJobs();
    deleteContents(dir);
  }
}
