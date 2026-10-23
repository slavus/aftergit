package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.jdt.SideAnalysis;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SideAnalysisTest {
  static final String PATH = "src/p/S.java";

  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;

  @BeforeEach
  void setUp() throws Exception {
    repos = new ConflictRepos(dir);
    repos.write(PATH, "package p;\n\npublic class S {\n}\n");
    file = repos.javaProject("sides-it", PATH);
  }

  @AfterEach
  void tearDown() throws Exception {
    repos.dispose();
  }

  @Test
  void errorsComeByModelLine() throws Exception {
    List<LineProblem> errors = SideAnalysis.errors(file,
        "package p;\n\npublic class S {\n  int v() {\n    return missing;\n  }\n}\n", null);
    assertEquals(1, errors.size(), errors.toString());
    assertEquals(4, errors.get(0).line());
    assertTrue(errors.get(0).message().contains("missing"), errors.get(0).message());
  }

  @Test
  void bothSidesAreCheckedOnceInTheBackground() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    SideAnalysis a = SideAnalysis.start(file, "package p;\n\npublic class S {\n  int x = y;\n}\n",
        "package p;\n\npublic class S {\n}\n", done::countDown);
    assertTrue(done.await(30, TimeUnit.SECONDS), "the analysis finished");
    assertEquals(1, a.leftErrors().size());
    assertEquals(3, a.leftErrors().get(0).line());
    assertEquals(List.of(), a.rightErrors());
  }

  /** A plain project (no Java nature) that holds a .java file. */
  private IFile fileInPlainProject() throws Exception {
    File plain = new File(dir, "plain");
    assertTrue(new File(plain, "src/p").mkdirs());
    java.nio.file.Files.writeString(new File(plain, "src/p/S.java").toPath(), "package p;\n\npublic class S {\n}\n");
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    IProject project = ws.getRoot().getProject("plain-sides-it");
    org.eclipse.core.resources.IProjectDescription d = ws.newProjectDescription("plain-sides-it");
    d.setLocation(Path.fromOSString(plain.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
    return project.getFile("src/p/S.java");
  }

  /** The warnings and errors logged while {@code action} runs. */
  private static List<IStatus> logged(ThrowingRunnable action) throws Exception {
    List<IStatus> out = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> out.add(status);
    ILog log = ILog.of(SideAnalysis.class);
    log.addLogListener(listener);
    try {
      action.run();
      Job.getJobManager().join(SideAnalysis.FAMILY, null);
    } finally {
      log.removeLogListener(listener);
    }
    return out;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void aJavaFileOutsideAJavaProjectHasNoErrorsAndNothingIsLogged() throws Exception {
    IFile plain = fileInPlainProject();
    try {
      assertNull(SideAnalysis.errors(plain, "package p;\n\npublic class S {\n  int x = y;\n}\n", null));
      CountDownLatch called = new CountDownLatch(1);
      SideAnalysis[] a = new SideAnalysis[1];
      List<IStatus> log = logged(() -> a[0] = SideAnalysis.start(plain, "class S {}\n", "class S {}\n",
          called::countDown));
      assertNull(a[0].leftErrors());
      assertNull(a[0].rightErrors());
      assertEquals(1, called.getCount(), "no result, no callback");
      assertEquals(List.of(), log.stream().filter(s -> "net.aftergit.merge.jdt".equals(s.getPlugin())).toList());
    } finally {
      plain.getProject().delete(false, true, null);
    }
  }

  @Test
  void aFileOutsideTheClasspathHasNoErrors() throws Exception {
    repos.write("other/O.java", "class O {}\n");
    file.getProject().refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
    assertNull(SideAnalysis.errors(file.getProject().getFile("other/O.java"), "class O { int x = y; }\n", null));
  }

  @Test
  void aThrowingCallbackIsNotLoggedAsACompileFailure() throws Exception {
    CountDownLatch called = new CountDownLatch(1);
    SideAnalysis[] a = new SideAnalysis[1];
    List<IStatus> log = logged(() -> a[0] = SideAnalysis.start(file, "package p;\n\npublic class S {\n  int x = y;\n}\n",
        "package p;\n\npublic class S {\n}\n", () -> {
          called.countDown();
          throw new IllegalStateException("listener");
        }));
    assertEquals(0, called.getCount(), "the callback ran");
    assertEquals(1, a[0].leftErrors().size(), "the result is kept");
    assertEquals(List.of(), log.stream().filter(s -> s.getMessage().contains("could not be compiled")).toList());
    assertEquals(1, log.stream().filter(s -> s.getMessage().contains("listener")
        && s.getException() instanceof IllegalStateException).count(), "the listener's failure is logged once: " + log);
  }
}
