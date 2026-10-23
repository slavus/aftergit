package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.jdt.JavaPanes;
import org.eclipse.core.resources.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaBridgeTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaBridge.setAvailableForTests(null);
    JavaPaneSetup.setCreateHookForTests(null);
    repos.dispose();
  }

  @Test
  void javaIntegrationIsWiredInTheTestRuntime() {
    assertTrue(JavaBridge.available());
    JavaBridge.setAvailableForTests(false);
    assertFalse(JavaBridge.available());
  }

  @Test
  void javaFilesAreRecognizedByContentType() throws Exception {
    repos.write("src/p/A.java", "package p;\nclass A {}\n");
    repos.write("a.txt", "x\n");
    IFile java = repos.javaProject("bridge-it", "src/p/A.java");
    assertTrue(JavaPanes.isJavaFile(java));
    assertFalse(JavaPanes.isJavaFile(java.getProject().getFile("a.txt")));
  }

  @Test
  void aFailingJavaSetupFallsBackToPlainPanes() throws Exception {
    java.util.List<org.eclipse.core.runtime.IStatus> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    org.eclipse.core.runtime.ILogListener l = (status, plugin) -> logged.add(status);
    org.eclipse.core.runtime.Platform.addLogListener(l);
    JavaPaneSetup.setCreateHookForTests(() -> {
      throw new IllegalStateException("broken Java integration");
    });
    try {
      MergeEditorInput input = JavaSources.open(repos, "bridge-fallback", JavaSources.BASE, JavaSources.OURS,
          JavaSources.THEIRS, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      MergeViewer v = input.viewer();
      assertEquals(ResultKind.PLAIN, v.resultKind(), "plain panes instead (spec \u00a79)");
      assertNull(v.sharedBuffer(), "the Java setup's buffer connection was released");
      IFile file = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject("bridge-fallback")
          .getFile(JavaSources.PATH);
      assertNull(org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager()
          .getTextFileBuffer(file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE));
      assertTrue(logged.stream().anyMatch(st -> st.getException() instanceof IllegalStateException),
          "the failure is logged: " + logged);
    } finally {
      org.eclipse.core.runtime.Platform.removeLogListener(l);
    }
  }
}
