package net.aftergit.merge.mcp.ui;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.aftergit.merge.ui.MergeEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/** Repositories, projects and open resolvers for the agents' UI tests; HTTP calls run off the UI thread. */
final class UiRepos {
  static final String BASE = "a\nb\nc\nd\ne\n";
  static final String OURS = "a\nL1\nc\nL3\ne\n";
  static final String THEIRS = "a\nR1\nc\nR3\ne\n";

  private UiRepos() {}

  static void write(File dir, String path, String content) throws Exception {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit().setAll(true).setMessage(message).setAuthor("t", "t@t").setCommitter("t", "t@t").call();
  }

  static Repository conflict(File dir, String path, String base, String ours, String theirs) throws Exception {
    Git git = Git.init().setDirectory(dir).setInitialBranch("main").call();
    write(dir, path, base);
    git.add().addFilepattern(path).call();
    commit(git, "base");
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    write(dir, path, theirs);
    commit(git, "feature");
    git.checkout().setName("main").call();
    write(dir, path, ours);
    commit(git, "main");
    git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge feature").call();
    return git.getRepository();
  }

  static void remerge(Repository repo, String path, String newTheirs) throws Exception {
    Git git = Git.wrap(repo);
    git.reset().setMode(ResetCommand.ResetType.HARD).call();
    git.checkout().setName("feature").call();
    write(repo.getWorkTree(), path, newTheirs);
    commit(git, "feature again");
    git.checkout().setName("main").call();
    git.merge().include(repo.resolve("feature")).setMessage("Merge feature again").call();
  }

  static IProject project(String name, File dir) throws Exception {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    IProject p = ws.getRoot().getProject(name);
    IProjectDescription d = ws.newProjectDescription(name);
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    p.create(d, null);
    p.open(null);
    p.setDefaultCharset("UTF-8", null);
    return p;
  }

  static IWorkbenchPage page() {
    return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
  }

  static MergeEditorInput open(IFile file) {
    MergeEditorInput input = new MergeEditorInput(file);
    CompareUI.openCompareEditor(input, false);
    waitUntil(() -> input.viewer() != null && input.session() != null, 15, "the merge viewer");
    flush();
    return input;
  }

  static void flush() {
    Display d = Display.getCurrent();
    for (int i = 0; i < 5; i++) {
      while (d.readAndDispatch()) {
        // pending paints, layouts and async runnables
      }
    }
  }

  static void waitUntil(BooleanSupplier condition, int seconds, String what) {
    Display d = Display.getCurrent();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("timed out waiting for " + what);
      }
      if (!d.readAndDispatch()) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail("interrupted waiting for " + what);
        }
      }
    }
  }

  /**
   * Runs {@code task} (an HTTP call) on another thread while the UI thread keeps dispatching events, so the server
   * can reach the open resolver; returns its result, or the exception it threw.
   */
  static Object offUiOrException(Callable<?> task) {
    CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
      try {
        return task.call();
      } catch (Exception e) {
        return e;
      }
    });
    waitUntil(f::isDone, 30, "the call");
    return f.join();
  }

  @SuppressWarnings("unchecked")
  static <T> T offUi(Callable<T> task) {
    Object o = offUiOrException(task);
    if (o instanceof Exception e) {
      throw new AssertionError("the call failed: " + e, e);
    }
    return (T) o;
  }

  static void cleanUp(File dir, Repository repo, IProject project) throws Exception {
    page().closeAllEditors(false);
    flush();
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
    if (repo != null) {
      repo.close();
    }
    RepositoryCache.clear();
    File[] children = dir.listFiles();
    if (children != null) {
      for (File c : children) {
        FileUtils.delete(c, FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
      }
    }
  }
}
