package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.Arrays;
import net.aftergit.merge.lsp.Servers;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 2: the language servers lsp4e has for a file, and the stand-in server of the tests. */
@SuppressWarnings("restriction")
class LanguageServersTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    StandInLanguageServer.reset();
  }

  @AfterEach
  void tearDown() throws Exception {
    LspTestHooks.setServerFilter(null);
    repos.dispose();
    StandInLanguageServer.reset();
  }

  @Test
  void theStandInIsTheServerOfItsFilesOnly() throws Exception {
    IFile file = repos.plainProject("servers", "a.agdiag");
    assertNotNull(LanguageServersRegistry.getInstance().getDefinition(StandInLanguageServer.ID));
    assertTrue(Servers.hasServer(file));
    assertFalse(Servers.hasServer(file.getProject().getFile("a.txt")));
    assertFalse(Servers.hasServer(file.getProject().getFile("a.kt")), "no Kotlin server in the target");
    assertFalse(Servers.hasServer(file.getProject().getFile("a.py")), "no Python server in the target");
  }

  @Test
  void javaFilesHaveNoneAndTheTestsSeeOnlyTheStandIn() throws Exception {
    IFile file = repos.plainProject("servers", "a.agdiag");
    assertFalse(Servers.hasServer(file.getProject().getFile("A.java")));
    // the tests' system property keeps Wild Web Developer's servers out; without it TypeScript has one
    String property = System.getProperty(LspTestHooks.SERVERS_PROPERTY);
    assumeTrue(property != null, "runs only under the pom's argLine (" + LspTestHooks.SERVERS_PROPERTY + ")");
    assertEquals("net.aftergit.merge.ui.tests.", property);
    assertFalse(Servers.hasServer(file.getProject().getFile("a.ts")));
    LspTestHooks.setServerFilter(id -> true);
    assertTrue(Servers.hasServer(file.getProject().getFile("a.ts")), "Wild Web Developer has a TypeScript server");
    LspTestHooks.setServerFilter(id -> false);
    assertFalse(Servers.hasServer(file));
  }

  @Test
  void lsp4eConnectsABufferOfTheFileAndKeepsTheServersWordsOnItsMarkers() throws Exception {
    repos.write("a.agdiag", "def one\nuse one\nuse two\n");
    IFile file = repos.plainProject("servers", "a.agdiag");
    ITextFileBufferManager m = FileBuffers.getTextFileBufferManager();
    m.connect(file.getFullPath(), LocationKind.IFILE, null);
    try {
      ConflictRepos.waitUntil(() -> markers(file).length == 1, 30, "the stand-in's marker");
      IMarker marker = markers(file)[0];
      assertEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, -1));
      assertEquals(StandInMarkerMessages.PREFIX + "Unknown name two", marker.getAttribute(IMarker.MESSAGE, ""));
      Diagnostic d = (Diagnostic) marker.getAttribute("lspDiagnostic");
      assertEquals("Unknown name two", d.getMessage().getLeft(), "the server's own words stay on the marker");
      assertEquals("def one\nuse one\nuse ".length(), marker.getAttribute(IMarker.CHAR_START, -1));
      assertEquals(1, StandInLanguageServer.INSTANCES.size());
    } finally {
      m.disconnect(file.getFullPath(), LocationKind.IFILE, null);
    }
  }

  static IMarker[] markers(IFile file) {
    try {
      return Arrays.stream(file.findMarkers(null, true, IResource.DEPTH_ZERO))
          .filter(x -> x.getAttribute("languageServerId", null) != null).toArray(IMarker[]::new);
    } catch (CoreException e) {
      return new IMarker[0];
    }
  }
}
