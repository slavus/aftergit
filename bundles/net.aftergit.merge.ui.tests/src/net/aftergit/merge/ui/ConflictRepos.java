package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/** A git repository with one conflicted file, opened as a workspace project; shared by the editor tests. */
final class ConflictRepos {
  private final File dir;
  private IProject project;

  ConflictRepos(File dir) {
    this.dir = dir;
  }

  void write(String path, String content) throws IOException {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
  }

  byte[] bytes(String path) throws IOException {
    return Files.readAllBytes(new File(dir, path).toPath());
  }

  String read(String path) throws IOException {
    return Files.readString(new File(dir, path).toPath());
  }

  /** Commits {@code base}, then {@code theirs} on "feature" and {@code ours} on "main", and merges: {@code path} conflicts. */
  void conflict(String path, String base, String ours, String theirs) throws Exception {
    try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
      write(path, base);
      git.add().addFilepattern(path).call();
      git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.branchCreate().setName("feature").call();
      git.checkout().setName("feature").call();
      write(path, theirs);
      git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.checkout().setName("main").call();
      write(path, ours);
      git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'").call();
    }
  }

  /** Several files changed on both branches and merged once; {@code files}: path → {base, ours, theirs}. */
  void conflicts(Map<String, String[]> files) throws Exception {
    try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
      for (var e : files.entrySet()) {
        write(e.getKey(), e.getValue()[0]);
      }
      git.add().addFilepattern(".").call();
      git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.branchCreate().setName("feature").call();
      git.checkout().setName("feature").call();
      for (var e : files.entrySet()) {
        write(e.getKey(), e.getValue()[2]);
      }
      git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.checkout().setName("main").call();
      for (var e : files.entrySet()) {
        write(e.getKey(), e.getValue()[1]);
      }
      git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'").call();
    }
  }

  void writeBytes(String path, byte[] content) throws IOException {
    File f = new File(dir, path);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), content);
  }

  /** Like {@link #conflict}, with raw bytes. */
  void conflictBytes(String path, byte[] base, byte[] ours, byte[] theirs) throws Exception {
    try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
      writeBytes(path, base);
      git.add().addFilepattern(path).call();
      git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.branchCreate().setName("feature").call();
      git.checkout().setName("feature").call();
      writeBytes(path, theirs);
      git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.checkout().setName("main").call();
      writeBytes(path, ours);
      git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'").call();
    }
  }

  /** One branch changes {@code path}, the other deletes it; {@code oursDeletes}: main deletes, feature changes. */
  void modifyDelete(String path, String base, String changed, boolean oursDeletes) throws Exception {
    try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
      write(path, base);
      git.add().addFilepattern(path).call();
      git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.branchCreate().setName("feature").call();
      git.checkout().setName("feature").call();
      if (oursDeletes) {
        write(path, changed);
      } else {
        git.rm().addFilepattern(path).call();
      }
      git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.checkout().setName("main").call();
      if (oursDeletes) {
        git.rm().addFilepattern(path).call();
      } else {
        write(path, changed);
      }
      git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.merge().include(git.getRepository().resolve("feature")).setMessage("Merge branch 'feature'").call();
    }
  }

  /** Replaces the index entries of {@code path} by conflict stages of {@code mode} (kinds JGit's merge does not make). */
  void fakeStages(String path, FileMode mode, int... stages) throws Exception {
    try (Git git = Git.open(dir)) {
      Repository repo = git.getRepository();
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
  }

  /** A plain project at the repository; returns the file at {@code path}. */
  IFile plainProject(String name, String path) throws CoreException {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject(name);
    IProjectDescription d = ws.newProjectDescription(name);
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    return project.getFile(path);
  }

  /**
   * A conflicted text file "a.txt" in a plain project shared with EGit, so that EGit's own Merge Tool works on it
   * too; returns once EGit sees the conflict (its Merge Tool is enabled only then).
   */
  IFile sharedConflict(String name) throws Exception {
    conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = plainProject(name, "a.txt");
    new org.eclipse.egit.core.op.ConnectProviderOperation(file.getProject(), new File(dir, ".git"))
        .execute(new org.eclipse.core.runtime.NullProgressMonitor());
    waitUntil(() -> MergeToolCommandTest.egitSeesConflict(file), 20, "EGit to see the conflict");
    return file;
  }

  /** A Java project at the repository: source folder "src", the default JRE, output "bin". */
  IFile javaProject(String name, String path) throws CoreException {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject(name);
    IProjectDescription d = ws.newProjectDescription(name);
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    d.setNatureIds(new String[] {JavaCore.NATURE_ID});
    project.create(d, null);
    project.open(null);
    assertNotNull(JavaRuntime.getDefaultVMInstall(), "the test runtime has a JRE for Java projects");
    IJavaProject jp = JavaCore.create(project);
    jp.setRawClasspath(new IClasspathEntry[] {
        JavaCore.newSourceEntry(project.getFullPath().append("src")),
        JavaRuntime.getDefaultJREContainerEntry()}, project.getFullPath().append("bin"), null);
    return project.getFile(path);
  }

  void dispose() throws CoreException {
    page().closeAllEditors(false);
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
  }

  static IWorkbenchPage page() {
    return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
  }

  static MergeEditorInput open(IFile file, SaveDecision decision) {
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(decision);
    return open(input);
  }

  /** Opens the editor (prepareInput runs in a background job) and waits for its viewer. */
  static MergeEditorInput open(MergeEditorInput input) {
    CompareUI.openCompareEditor(input, false);
    waitUntil(() -> input.viewer() != null, 10, "the merge viewer");
    assertNotNull(input.viewer(), "viewer created");
    return input;
  }

  /** {@code saveWithoutStaging}: the answer when Save & Mark Resolved finds open conflicts (else Cancel). */
  static SaveDecision answer(SaveDecision.SaveChoice choice, boolean saveWithoutStaging) {
    return answer(choice, saveWithoutStaging ? SaveDecision.FinishChoice.SAVE_WITHOUT_STAGING
        : SaveDecision.FinishChoice.CANCEL);
  }

  static SaveDecision answer(SaveDecision.SaveChoice choice, SaveDecision.FinishChoice finish) {
    return new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        return choice;
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        return finish;
      }
    };
  }

  /** Pumps events until {@code condition} holds; fails after {@code seconds}. */
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

  /** Tells {@code control}'s focus listeners that it got the focus (no window manager on Xvfb). */
  static void focusIn(Control control) {
    control.notifyListeners(SWT.FocusIn, new Event());
  }

  /** Tells {@code control}'s focus listeners that it lost the focus. */
  static void focusOut(Control control) {
    control.notifyListeners(SWT.FocusOut, new Event());
  }
}
