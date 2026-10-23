package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSection;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolveAllTest {
  static final ResolveAll.Options OPTIONS = new ResolveAll.Options(ComparisonPolicy.DEFAULT, false);
  static final String[] WORDS = {"x = 0; y = 0;\n", "x = 1; y = 0;\n", "x = 0; y = 2;\n"}; // resolvable
  static final String[] LINES = {"a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n"}; // a real conflict
  static final String K = "k1\nk2\nk3\nk4\nk5\n";
  static final String[] BOTH = {"x = 0; y = 0;\n" + K + "b\n", "x = 1; y = 0;\n" + K + "L\n",
      "x = 0; y = 2;\n" + K + "R\n"};

  @TempDir
  File dir;
  Repository repo;
  IProject project;

  SystemReader original;

  @BeforeEach
  void isolateFromUserConfig() {
    original = TestRepos.isolateUserConfig(dir);
  }

  /** More projects a test made (nested or elsewhere), deleted after it. */
  final List<IProject> extraProjects = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    SystemReader.setInstance(original);
    TestRepos.waitForJobs(); // workspace and EGit jobs started by the test must not write into the deleted dir
    for (IProject p : extraProjects) {
      if (p.exists()) {
        p.delete(false, true, null);
      }
    }
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
    if (repo != null) {
      repo.close();
    }
    RepositoryCache.clear(); // RepositoryLocator opened the test repositories through the cache
    TestRepos.waitForJobs();
    TestRepos.deleteContents(dir);
  }

  private void setUp(Map<String, String[]> files) throws Exception {
    repo = TestRepos.conflicts(dir, files);
    openProject();
  }

  /** The project "resolve-all-test" at the repository, default charset UTF-8. */
  private void openProject() throws Exception {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject("resolve-all-test");
    IProjectDescription d = ws.newProjectDescription(project.getName());
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    project.setDefaultCharset("UTF-8", null);
  }

  private static Map<String, String[]> files(Object... pathsAndTexts) {
    Map<String, String[]> m = new LinkedHashMap<>();
    for (int i = 0; i < pathsAndTexts.length; i += 2) {
      m.put((String) pathsAndTexts[i], (String[]) pathsAndTexts[i + 1]);
    }
    return m;
  }

  private ResolveAllReport run(Function<IFile, JavaChecks> java, Set<IFile> busy, String... paths) {
    List<IFile> list = new ArrayList<>();
    for (String p : paths) {
      list.add(project.getFile(p));
    }
    return ResolveAll.run(list, OPTIONS, java, busy, new NullProgressMonitor());
  }

  private ResolveAllReport run(String... paths) {
    return run(f -> null, Set.of(), paths);
  }

  private String read(String path) throws Exception {
    return Files.readString(new File(dir, path).toPath(), StandardCharsets.UTF_8);
  }

  private byte[] bytes(String path) throws Exception {
    return Files.readAllBytes(new File(dir, path).toPath());
  }

  private static FileOutcome outcome(ResolveAllReport r, String path) {
    return r.files().stream().filter(o -> o.path().endsWith("/" + path)).findFirst().orElseThrow();
  }

  /** {@code w} with its first conflict hunk replaced by {@code resolved}. */
  private static String firstHunkReplaced(String w, String resolved) {
    int start = w.indexOf("<<<<<<< ");
    int end = w.indexOf('\n', w.indexOf(">>>>>>> ", start)) + 1;
    return w.substring(0, start) + resolved + w.substring(end);
  }

  @Test
  void writesOnlyFilesWithAResolvedConflict() throws Exception {
    setUp(files("full.txt", WORDS, "none.txt", LINES, "part.txt", BOTH));
    byte[] none = bytes("none.txt");
    ResolveAllReport r = run("full.txt", "none.txt", "part.txt");
    assertEquals(FileOutcome.Result.FULL, outcome(r, "full.txt").result());
    assertEquals(FileOutcome.Result.UNTOUCHED, outcome(r, "none.txt").result());
    assertEquals(FileOutcome.Reason.NOTHING_RESOLVED, outcome(r, "none.txt").reason());
    assertEquals(FileOutcome.Result.PARTIAL, outcome(r, "part.txt").result());
    assertEquals(1, r.count(FileOutcome.Result.FULL));
    assertEquals(1, r.count(FileOutcome.Result.PARTIAL));
    assertEquals(1, r.count(FileOutcome.Result.UNTOUCHED));
    assertEquals("x = 1; y = 2;\n", read("full.txt"));
    assertArrayEquals(none, bytes("none.txt"), "a file without a resolved conflict is not written");
    assertEquals(2, outcome(r, "part.txt").conflicts());
    assertEquals(1, outcome(r, "part.txt").resolved());
    assertFalse(r.cancelled());
    assertEquals(List.of(project.getFile("full.txt"), project.getFile("part.txt")), r.undo().files());
  }

  @Test
  void unresolvedConflictsKeepGitsMarkersAndLabels() throws Exception {
    setUp(files("part.txt", BOTH));
    String w = read("part.txt");
    assertTrue(w.startsWith("<<<<<<< HEAD\n"), w);
    run("part.txt");
    assertEquals(firstHunkReplaced(w, "x = 1; y = 2;\n"), read("part.txt"));
  }

  @Test
  void diff3WhenTheRepositoryAsksForIt() throws Exception {
    setUp(files("part.txt", BOTH));
    StoredConfig c = repo.getConfig();
    c.setString("merge", null, "conflictStyle", "diff3");
    c.save();
    String w = read("part.txt");
    String theirs = w.substring(w.lastIndexOf(">>>>>>> ") + 8, w.indexOf('\n', w.lastIndexOf(">>>>>>> ")));
    run("part.txt");
    assertEquals("x = 1; y = 2;\n" + K + "<<<<<<< HEAD\nL\n||||||| base\nb\n=======\nR\n>>>>>>> " + theirs + "\n",
        read("part.txt"));
  }

  @Test
  void keepsCrlfAndAMissingFinalNewline() throws Exception {
    String[] crlf = {"x = 0; y = 0;\r\nk\r\nb\r\nend", "x = 1; y = 0;\r\nk\r\nL\r\nend",
        "x = 0; y = 2;\r\nk\r\nR\r\nend"};
    String[] crlfFull = {"x = 0; y = 0;\r\nend", "x = 1; y = 0;\r\nend", "x = 0; y = 2;\r\nend"};
    setUp(files("crlf.txt", crlf, "full.txt", crlfFull));
    String w = read("crlf.txt");
    String theirs = w.substring(w.lastIndexOf(">>>>>>> ") + 8, w.indexOf('\n', w.lastIndexOf(">>>>>>> ")));
    run("crlf.txt", "full.txt");
    assertEquals("x = 1; y = 2;\r\nend", read("full.txt"));
    String out = read("crlf.txt");
    assertTrue(out.startsWith("x = 1; y = 2;\r\nk\r\n"), out);
    assertTrue(out.contains("<<<<<<< HEAD\r\nL\r\n=======\r\nR\r\n>>>>>>> " + theirs + "\r\n"), out);
    assertTrue(out.endsWith("end"), out);
  }

  @Test
  void aHandEditInsideTheMarkersIsNeverOverwritten() throws Exception {
    setUp(files("full.txt", WORDS));
    String edited = read("full.txt").replace("=======\n", "KEEP ME\n=======\n");
    Files.writeString(new File(dir, "full.txt").toPath(), edited, StandardCharsets.UTF_8);
    ResolveAllReport r = run("full.txt");
    assertNotEquals(FileOutcome.Result.FULL, outcome(r, "full.txt").result());
    assertEquals(edited, read("full.txt"));
    assertEquals(List.of(), r.fullyResolved());
  }

  @Test
  void aHandEditInTheDiff3BaseSectionIsNeverOverwritten() throws Exception {
    setUp(files("full.txt", WORDS));
    StoredConfig c = repo.getConfig();
    c.setString("merge", null, "conflictStyle", "diff3");
    c.save();
    String w = read("full.txt");
    // the conflict was written in merge style: give it the diff3 base section git would have written
    String edited = w.replace("=======\n", "||||||| base\n" + WORDS[0] + "KEEP ME\n=======\n");
    Files.writeString(new File(dir, "full.txt").toPath(), edited, StandardCharsets.UTF_8);
    ResolveAllReport r = run("full.txt");
    assertNotEquals(FileOutcome.Result.FULL, outcome(r, "full.txt").result());
    assertEquals(edited, read("full.txt"));
  }

  @Test
  void undoAfterStagingBringsBackBytesAndConflictStages() throws Exception {
    setUp(files("full.txt", WORDS, "part.txt", BOTH));
    byte[] full = bytes("full.txt");
    byte[] part = bytes("part.txt");
    ResolveAllReport r = run("full.txt", "part.txt");
    r.undo().stage(r.fullyResolved());
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
    assertTrue(ConflictState.isConflicted(repo, "part.txt"), "only fully resolved files are staged");
    assertEquals(List.of(), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(full, bytes("full.txt"));
    assertArrayEquals(part, bytes("part.txt"));
    assertTrue(ConflictState.isConflicted(repo, "full.txt"), "the conflict stages are back");
    assertTrue(r.undo().isEmpty(), "an undo is done once");
  }

  @Test
  void stagingTwiceStillUndoesToTheConflictStages() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    r.undo().stage(r.fullyResolved());
    r.undo().stage(r.fullyResolved());
    byte[] resolvedBytes = bytes("full.txt");
    r.undo().undo(new NullProgressMonitor());
    assertTrue(ConflictState.isConflicted(repo, "full.txt"));
    assertFalse(java.util.Arrays.equals(resolvedBytes, bytes("full.txt")));
    assertTrue(read("full.txt").contains("<<<<<<< "), "the conflict markers are back");
  }

  @Test
  void undoLeavesAFileChangedSinceAlone() throws Exception {
    setUp(files("full.txt", WORDS, "other.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n",
        "p = 0; q = 2;\n"}));
    byte[] other = bytes("other.txt");
    ResolveAllReport r = run("full.txt", "other.txt");
    TestRepos.write(dir, "full.txt", "edited by hand\n");
    project.getFile("full.txt").refreshLocal(0, null);
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertEquals("edited by hand\n", read("full.txt"));
    assertArrayEquals(other, bytes("other.txt"));
  }

  @Test
  void stageResolvedStagesOnlyFullyResolvedFiles() throws Exception {
    setUp(files("full.txt", WORDS, "none.txt", LINES, "part.txt", BOTH));
    ResolveAllReport r = run("full.txt", "none.txt", "part.txt");
    assertEquals(List.of(outcome(r, "full.txt")), r.fullyResolved());
    r.undo().stage(r.fullyResolved());
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
    assertTrue(ConflictState.isConflicted(repo, "none.txt"));
    assertTrue(ConflictState.isConflicted(repo, "part.txt"));
    assertEquals(List.of(outcome(r, "none.txt"), outcome(r, "part.txt")), r.remaining());
  }

  /** Java checks that report a compile error on every result line equal to {@code bad}. */
  private static JavaChecks rejecting(String bad, List<String> calls) {
    return new JavaChecks() {
      @Override
      public ImportLocator locator() {
        return null;
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        calls.add("fix");
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        calls.add("errors");
        List<LineProblem> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if ((lines[i] + "\n").equals(bad)) {
            out.add(new LineProblem(i, "x cannot be resolved"));
          }
        }
        return out;
      }
    };
  }

  @Test
  void aBlockTheChecksRejectMakesTheFilePartlyResolved() throws Exception {
    setUp(files("full.txt", WORDS));
    List<String> calls = new ArrayList<>();
    ResolveAllReport r = run(f -> rejecting("x = 1; y = 2;\n", calls), Set.of(), "full.txt");
    FileOutcome o = outcome(r, "full.txt");
    assertEquals(FileOutcome.Result.PARTIAL, o.result());
    assertEquals(1, o.notCompiling());
    assertEquals("x = 1; y = 2;\n", read("full.txt"), "the resolution is written all the same");
    assertEquals(List.of("errors", "errors", "errors"), calls, "the result, ours and theirs, each once");
    assertTrue(r.fullyResolved().isEmpty());
  }

  @Test
  void aResultWithoutErrorsChecksNeitherSide() throws Exception {
    setUp(files("full.txt", WORDS));
    List<String> calls = new ArrayList<>();
    ResolveAllReport r = run(f -> rejecting("no line is this\n", calls), Set.of(), "full.txt");
    assertEquals(FileOutcome.Result.FULL, outcome(r, "full.txt").result());
    assertEquals(List.of("errors"), calls, "only the result: no block can be marked without an error there");
  }

  @Test
  void errorsUnknownToTheChecksKeepTheFileFullyResolved() throws Exception {
    setUp(files("full.txt", WORDS));
    JavaChecks unknown = new JavaChecks() {
      @Override
      public ImportLocator locator() {
        return null;
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        // no import sections without a locator
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        return null; // the compile failed or there is no Java model
      }
    };
    assertEquals(FileOutcome.Result.FULL, outcome(run(f -> unknown, Set.of(), "full.txt"), "full.txt").result());
  }

  @Test
  void otherKindsOfConflictsStayUntouched() throws Exception {
    repo = TestRepos.modifyDelete(dir, "gone.txt", "a\n", "a changed\n", false);
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject("resolve-all-test");
    IProjectDescription d = ws.newProjectDescription(project.getName());
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    byte[] before = bytes("gone.txt");
    FileOutcome o = outcome(run("gone.txt"), "gone.txt");
    assertEquals(FileOutcome.Result.UNTOUCHED, o.result());
    assertEquals(FileOutcome.Reason.OTHER_KIND, o.reason());
    assertEquals(ConflictKind.MODIFY_DELETE, o.kind());
    assertArrayEquals(before, bytes("gone.txt"));
    assertTrue(o.remaining(), "the dialog offers to resolve it");
  }

  @Test
  void busyFilesAndMixedLineEndsStayUntouched() throws Exception {
    String[] mixed = {"x = 0; y = 0;\r\nk\nend\n", "x = 1; y = 0;\r\nk\nend\n", "x = 0; y = 2;\r\nk\nend\n"};
    setUp(files("busy.txt", WORDS, "mixed.txt", mixed));
    byte[] busy = bytes("busy.txt");
    ResolveAllReport r = run(f -> null, Set.of(project.getFile("busy.txt")), "busy.txt", "mixed.txt");
    assertEquals(FileOutcome.Reason.BUSY, outcome(r, "busy.txt").reason());
    assertEquals(FileOutcome.Reason.MIXED_LINE_ENDINGS, outcome(r, "mixed.txt").reason());
    assertArrayEquals(busy, bytes("busy.txt"));
  }

  @Test
  void cancellingStopsBeforeTheNextFile() throws Exception {
    setUp(files("a.txt", WORDS, "b.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n", "p = 0; q = 2;\n"}));
    byte[] b = bytes("b.txt");
    NullProgressMonitor monitor = new NullProgressMonitor() {
      @Override
      public void subTask(String name) {
        if (name.endsWith("/b.txt")) {
          setCanceled(true); // the run announces the second file
        }
      }
    };
    ResolveAllReport r = ResolveAll.run(List.of(project.getFile("a.txt"), project.getFile("b.txt")), OPTIONS,
        f -> null, Set.of(), monitor);
    assertTrue(r.cancelled());
    assertEquals(1, r.files().size());
    assertEquals(2, r.requested());
    assertEquals("x = 1; y = 2;\n", read("a.txt"));
    assertArrayEquals(b, bytes("b.txt"));
  }

  @Test
  void aFailingFileDoesNotStopTheOthers() throws Exception {
    setUp(files("a.txt", WORDS, "b.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n", "p = 0; q = 2;\n"}));
    Function<IFile, JavaChecks> failing = f -> {
      if (f.getName().equals("a.txt")) {
        throw new IllegalStateException("broken checks");
      }
      return null;
    };
    ResolveAllReport r = run(failing, Set.of(), "a.txt", "b.txt");
    assertEquals(FileOutcome.Reason.FAILED, outcome(r, "a.txt").reason());
    assertEquals(FileOutcome.Result.FULL, outcome(r, "b.txt").result());
  }

  /** Java checks that run {@code action} at their first compile, between reading and writing the file. */
  private static JavaChecks during(Runnable action) {
    return new JavaChecks() {
      boolean done;

      @Override
      public ImportLocator locator() {
        return null;
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        // nothing
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        if (!done) {
          done = true;
          action.run();
        }
        return null;
      }
    };
  }

  @Test
  void aFileSavedInEclipseDuringTheRunIsNotOverwritten() throws Exception {
    setUp(files("full.txt", WORDS));
    IFile file = project.getFile("full.txt");
    JavaChecks save = during(() -> {
      try {
        file.setContents(new ByteArrayInputStream("saved by the user\n".getBytes(StandardCharsets.UTF_8)), 0, null);
      } catch (CoreException e) {
        throw new IllegalStateException(e);
      }
    });
    ResolveAllReport r = run(f -> save, Set.of(), "full.txt");
    FileOutcome o = outcome(r, "full.txt");
    assertEquals(FileOutcome.Result.UNTOUCHED, o.result());
    assertEquals(FileOutcome.Reason.CHANGED_DURING_RUN, o.reason());
    assertTrue(o.remaining());
    assertEquals("saved by the user\n", read("full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void aFileRewrittenOutsideEclipseDuringTheRunIsNotOverwritten() throws Exception {
    setUp(files("full.txt", WORDS));
    JavaChecks rewrite = during(() -> {
      try {
        TestRepos.write(dir, "full.txt", "written by git\n");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    FileOutcome o = outcome(run(f -> rewrite, Set.of(), "full.txt"), "full.txt");
    assertEquals(FileOutcome.Reason.CHANGED_DURING_RUN, o.reason());
    assertEquals("written by git\n", read("full.txt"));
  }

  @Test
  void anUndoBlockedByTheIndexLockCanBeRetried() throws Exception {
    setUp(files("full.txt", WORDS));
    byte[] before = bytes("full.txt");
    ResolveAllReport r = run("full.txt");
    byte[] resolved = bytes("full.txt");
    r.undo().stage(r.fullyResolved());
    File lock = new File(repo.getDirectory(), "index.lock");
    assertTrue(lock.createNewFile());
    ResolveAllUndo.IncompleteUndoException e = assertThrows(ResolveAllUndo.IncompleteUndoException.class,
        () -> r.undo().undo(new NullProgressMonitor()));
    assertEquals(List.of(project.getFile("full.txt")), e.failed());
    assertArrayEquals(resolved, bytes("full.txt"), "nothing of the file is undone");
    assertFalse(ConflictState.isConflicted(repo, "full.txt"), "still staged");
    assertFalse(r.undo().isEmpty(), "kept for a retry");
    assertTrue(lock.delete());
    assertEquals(List.of(), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(before, bytes("full.txt"));
    assertTrue(ConflictState.isConflicted(repo, "full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void anUndoThatCannotWriteKeepsTheFileStagedAndCanBeRetried() throws Exception {
    setUp(files("full.txt", WORDS, "other.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n",
        "p = 0; q = 2;\n"}));
    byte[] before = bytes("full.txt");
    byte[] otherBefore = bytes("other.txt");
    ResolveAllReport r = run("full.txt", "other.txt");
    byte[] resolved = bytes("full.txt");
    r.undo().stage(r.fullyResolved());
    File f = new File(dir, "full.txt");
    assertTrue(f.setWritable(false));
    try {
      ResolveAllUndo.IncompleteUndoException e = assertThrows(ResolveAllUndo.IncompleteUndoException.class,
          () -> r.undo().undo(new NullProgressMonitor()));
      assertEquals(List.of(project.getFile("full.txt")), e.failed());
      assertArrayEquals(resolved, bytes("full.txt"));
      assertFalse(ConflictState.isConflicted(repo, "full.txt"), "staged again, as before the undo");
      assertArrayEquals(otherBefore, bytes("other.txt"), "the other file is undone");
      assertTrue(ConflictState.isConflicted(repo, "other.txt"));
      assertEquals(List.of(project.getFile("full.txt")), r.undo().files());
    } finally {
      assertTrue(f.setWritable(true));
    }
    assertEquals(List.of(), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(before, bytes("full.txt"));
    assertTrue(ConflictState.isConflicted(repo, "full.txt"));
  }

  @Test
  void undoLeavesAFileWhoseMergeWasCommitted() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    r.undo().stage(r.fullyResolved());
    org.eclipse.jgit.api.Git.wrap(repo).commit().setMessage("merged").setAuthor("t", "t@t")
        .setCommitter("t", "t@t").call();
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertEquals("x = 1; y = 2;\n", read("full.txt"));
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
  }

  @Test
  void undoLeavesAFileStagedOutsideAfterGit() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    byte[] resolved = bytes("full.txt");
    GitIndex.markResolved(repo, "full.txt"); // git add from the command line or EGit
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(resolved, bytes("full.txt"), "no conflict markers in a file git took as resolved");
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void undoLeavesAFileCommittedOutsideAfterGit() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    byte[] resolved = bytes("full.txt");
    org.eclipse.jgit.api.Git.wrap(repo).add().addFilepattern("full.txt").call();
    org.eclipse.jgit.api.Git.wrap(repo).commit().setMessage("merged").setAuthor("t", "t@t")
        .setCommitter("t", "t@t").call();
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(resolved, bytes("full.txt"));
  }

  @Test
  void stagingAFileStagedOutsideAfterGitLeavesItForUndo() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    byte[] resolved = bytes("full.txt");
    GitIndex.markResolved(repo, "full.txt"); // git add from the command line or EGit
    assertEquals(List.of(project.getFile("full.txt")), r.undo().stage(r.fullyResolved()),
        "no longer conflicted: not staged by Resolve All");
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(resolved, bytes("full.txt"), "no conflict markers in a file git took as resolved");
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
  }

  @Test
  void undoAfterStagingAFileCommittedOutsideAfterGitLeavesIt() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    byte[] resolved = bytes("full.txt");
    GitIndex.markResolved(repo, "full.txt");
    r.undo().stage(r.fullyResolved());
    org.eclipse.jgit.api.Git.wrap(repo).commit().setMessage("merged").setAuthor("t", "t@t")
        .setCommitter("t", "t@t").call();
    assertEquals(List.of(project.getFile("full.txt")), r.undo().undo(new NullProgressMonitor()));
    assertArrayEquals(resolved, bytes("full.txt"));
    assertFalse(ConflictState.isConflicted(repo, "full.txt"));
  }

  @Test
  void undoChecksAndWritesUnderTheRuleOfTheFile() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    IFile full = project.getFile("full.txt");
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    org.eclipse.core.runtime.jobs.Job saver = org.eclipse.core.runtime.jobs.Job.create("saves the file", m -> {
      held.countDown();
      try {
        release.await();
        full.setContents(new ByteArrayInputStream("saved meanwhile\n".getBytes(StandardCharsets.UTF_8)), 0, m);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    saver.setRule(full);
    saver.schedule();
    assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
    java.util.concurrent.CompletableFuture<List<IFile>> undone = java.util.concurrent.CompletableFuture
        .supplyAsync(() -> {
          try {
            return r.undo().undo(new NullProgressMonitor());
          } catch (CoreException e) {
            throw new IllegalStateException(e);
          }
        });
    Thread.sleep(300); // the undo would read the bytes now, before the save, without the rule
    assertFalse(undone.isDone(), "the undo waits for the job that holds the file");
    release.countDown();
    assertEquals(List.of(full), undone.get(10, java.util.concurrent.TimeUnit.SECONDS),
        "saved while the undo waited: changed since the run");
    saver.join();
    assertEquals("saved meanwhile\n", read("full.txt"));
  }

  private static final String[] OTHER = {"p = 0; q = 0;\n", "p = 1; q = 0;\n", "p = 0; q = 2;\n"};

  @Test
  void undoKeepsBusyFilesForALaterUndo() throws Exception {
    setUp(files("full.txt", WORDS, "other.txt", OTHER));
    byte[] full = bytes("full.txt");
    byte[] other = bytes("other.txt");
    ResolveAllReport r = run("full.txt", "other.txt");
    byte[] resolved = bytes("full.txt");
    IFile busy = project.getFile("full.txt");
    ResolveAllUndo.Result result = r.undo().undo(Set.of(busy), new NullProgressMonitor());
    assertEquals(List.of(busy), result.busy());
    assertEquals(List.of(), result.changed());
    assertFalse(result.cancelled());
    assertArrayEquals(resolved, bytes("full.txt"), "a busy file is not written");
    assertArrayEquals(other, bytes("other.txt"));
    assertEquals(List.of(busy), r.undo().files(), "kept for a later undo");
    assertEquals(List.of(), r.undo().undo(Set.of(), new NullProgressMonitor()).busy());
    assertArrayEquals(full, bytes("full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void undoStopsBetweenFilesWhenCancelled() throws Exception {
    setUp(files("full.txt", WORDS, "other.txt", OTHER));
    byte[] other = bytes("other.txt");
    ResolveAllReport r = run("full.txt", "other.txt");
    byte[] resolved = bytes("full.txt");
    NullProgressMonitor cancelAfterOne = new NullProgressMonitor() {
      @Override
      public void worked(int work) {
        setCanceled(true);
      }

      @Override
      public void internalWorked(double work) {
        setCanceled(true);
      }
    };
    ResolveAllUndo.Result result = r.undo().undo(Set.of(), cancelAfterOne);
    assertTrue(result.cancelled());
    assertArrayEquals(other, bytes("other.txt"), "the last written file is undone first");
    assertArrayEquals(resolved, bytes("full.txt"), "the rest waits for the next undo");
    assertEquals(List.of(project.getFile("full.txt")), r.undo().files());
    NullProgressMonitor cancelled = new NullProgressMonitor();
    cancelled.setCanceled(true);
    assertTrue(r.undo().undo(Set.of(), cancelled).cancelled());
    assertArrayEquals(resolved, bytes("full.txt"));
  }

  @Test
  void stagingSkipsAFileChangedSinceTheRun() throws Exception {
    setUp(files("full.txt", WORDS));
    ResolveAllReport r = run("full.txt");
    TestRepos.write(dir, "full.txt", "<<<<<<< edited by hand\n");
    project.getFile("full.txt").refreshLocal(0, null);
    assertEquals(List.of(project.getFile("full.txt")), r.undo().stage(r.fullyResolved(), new NullProgressMonitor()));
    assertTrue(ConflictState.isConflicted(repo, "full.txt"), "not staged");
  }

  @Test
  void cancellingDuringTheChecksWritesNothing() throws Exception {
    setUp(files("full.txt", WORDS));
    byte[] before = bytes("full.txt");
    NullProgressMonitor monitor = new NullProgressMonitor();
    JavaChecks cancel = during(() -> monitor.setCanceled(true));
    ResolveAllReport r = ResolveAll.run(List.of(project.getFile("full.txt")), OPTIONS, f -> cancel, Set.of(),
        monitor);
    assertTrue(r.cancelled());
    assertEquals(List.of(), r.files());
    assertArrayEquals(before, bytes("full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void resolvedAndForeignFilesAreReported() throws Exception {
    setUp(files("full.txt", WORDS));
    GitIndex.markResolved(repo, "full.txt");
    assertEquals(FileOutcome.Reason.NOT_CONFLICTED, outcome(run("full.txt"), "full.txt").reason());

    File elsewhere = new File(dir.getParentFile(), dir.getName() + "-plain");
    elsewhere.mkdirs();
    try {
      IWorkspace ws = ResourcesPlugin.getWorkspace();
      IProject plain = ws.getRoot().getProject("resolve-all-plain");
      IProjectDescription d = ws.newProjectDescription(plain.getName());
      d.setLocation(Path.fromOSString(elsewhere.getAbsolutePath()));
      plain.create(d, null);
      plain.open(null);
      extraProjects.add(plain);
      TestRepos.write(elsewhere, "n.txt", "n\n");
      IFile n = plain.getFile("n.txt");
      n.refreshLocal(0, null);
      FileOutcome o = ResolveAll.run(List.of(n), OPTIONS, f -> null, Set.of(), new NullProgressMonitor()).files()
          .get(0);
      assertEquals(FileOutcome.Reason.NOT_IN_REPOSITORY, o.reason());
      assertFalse(o.remaining());
    } finally {
      for (IProject p : extraProjects) {
        p.delete(true, true, null);
      }
      extraProjects.clear();
    }
  }

  @Test
  void bytesInvalidInTheCharsetAreKeptAndNotCompiled() throws Exception {
    byte[] e9 = {(byte) 0xE9, '\n'}; // Latin-1 "é", not UTF-8
    repo = TestRepos.conflictBytes(dir, "latin.txt", concat(WORDS[0], e9), concat(WORDS[1], e9),
        concat(WORDS[2], e9));
    openProject();
    List<String> calls = new ArrayList<>();
    FileOutcome o = outcome(run(f -> rejecting("never", calls), Set.of(), "latin.txt"), "latin.txt");
    assertEquals(FileOutcome.Result.FULL, o.result());
    assertArrayEquals(concat("x = 1; y = 2;\n", e9), bytes("latin.txt"));
    assertEquals(List.of(), calls, "text read as ISO-8859-1 is not compiled");
  }

  private static byte[] concat(String text, byte[] tail) {
    byte[] head = text.getBytes(StandardCharsets.US_ASCII);
    byte[] out = java.util.Arrays.copyOf(head, head.length + tail.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  @Test
  void aFileThatBecomesBusyDuringTheRunIsNotWritten() throws Exception {
    setUp(files("full.txt", WORDS));
    IFile full = project.getFile("full.txt");
    byte[] before = bytes("full.txt");
    java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
    // free when the file is reached, opened in a resolver before its resolution is written
    java.util.function.Supplier<Set<IFile>> busy = () -> asked.incrementAndGet() == 1 ? Set.of() : Set.of(full);
    ResolveAllReport r = ResolveAll.run(List.of(full), OPTIONS, f -> null, busy, new NullProgressMonitor());
    assertEquals(FileOutcome.Reason.BUSY, outcome(r, "full.txt").reason());
    assertArrayEquals(before, bytes("full.txt"));
    assertTrue(r.undo().isEmpty());
  }

  @Test
  void stagingSkipsBusyFiles() throws Exception {
    setUp(files("full.txt", WORDS, "other.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n",
        "p = 0; q = 2;\n"}));
    ResolveAllReport r = run("full.txt", "other.txt");
    IFile full = project.getFile("full.txt");
    ResolveAllUndo.StageResult staged = r.undo().stage(r.fullyResolved(), Set.of(full), new NullProgressMonitor());
    assertEquals(List.of(full), staged.busy());
    assertEquals(List.of(), staged.changed());
    assertTrue(ConflictState.isConflicted(repo, "full.txt"), "a busy file is not staged");
    assertFalse(ConflictState.isConflicted(repo, "other.txt"));
    assertEquals(List.of(), r.undo().stage(r.fullyResolved(), Set.of(), new NullProgressMonitor()).busy());
    assertFalse(ConflictState.isConflicted(repo, "full.txt"), "staged once it is free");
  }

  @Test
  void aBusyFileIsFoundThroughANestedProject() throws Exception {
    setUp(files("sub/full.txt", WORDS));
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    IProject nested = ws.getRoot().getProject("resolve-all-nested");
    IProjectDescription d = ws.newProjectDescription(nested.getName());
    d.setLocation(Path.fromOSString(new File(dir, "sub").getAbsolutePath()));
    nested.create(d, null);
    nested.open(null);
    extraProjects.add(nested);
    byte[] before = bytes("sub/full.txt");
    ResolveAllReport r = run(f -> null, Set.of(nested.getFile("full.txt")), "sub/full.txt");
    assertEquals(FileOutcome.Reason.BUSY, outcome(r, "full.txt").reason());
    assertArrayEquals(before, bytes("sub/full.txt"));
  }

  @Test
  void aFatalErrorStopsTheRunButKeepsItsUndo() throws Exception {
    setUp(files("a.txt", WORDS, "b.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n", "p = 0; q = 2;\n"},
        "c.txt", new String[] {"r = 0; s = 0;\n", "r = 1; s = 0;\n", "r = 0; s = 2;\n"}));
    Function<IFile, JavaChecks> fatal = f -> {
      if (f.getName().equals("b.txt")) {
        throw new InternalError("the runtime is in trouble");
      }
      return null;
    };
    ResolveAllReport r = run(fatal, Set.of(), "a.txt", "b.txt", "c.txt");
    assertEquals(2, r.files().size(), "stopped after b.txt");
    assertEquals(FileOutcome.Result.FULL, outcome(r, "a.txt").result());
    assertEquals(FileOutcome.Reason.FAILED, outcome(r, "b.txt").reason());
    assertEquals(List.of(project.getFile("a.txt")), r.undo().files());
  }

  @Test
  void anAssertionErrorFailsOnlyItsFile() throws Exception {
    setUp(files("a.txt", WORDS, "b.txt", new String[] {"p = 0; q = 0;\n", "p = 1; q = 0;\n", "p = 0; q = 2;\n"}));
    Function<IFile, JavaChecks> failing = f -> {
      if (f.getName().equals("a.txt")) {
        throw new AssertionError("broken invariant");
      }
      return null;
    };
    ResolveAllReport r = run(failing, Set.of(), "a.txt", "b.txt");
    assertEquals(FileOutcome.Reason.FAILED, outcome(r, "a.txt").reason());
    assertEquals(FileOutcome.Result.FULL, outcome(r, "b.txt").result());
  }

  @Test
  void aStackOverflowIsLoggedWrapped() {
    Throwable t = ResolveAll.loggable(new StackOverflowError("deep"));
    assertFalse(t instanceof VirtualMachineError);
    assertTrue(t.getCause() instanceof StackOverflowError);
    IllegalStateException plain = new IllegalStateException("x");
    assertSame(plain, ResolveAll.loggable(plain));
  }

  @Test
  void aPropertiesFileIsResolvedPerKey() throws Exception {
    setUp(files("app.properties", new String[] {"a=1\nb=2\n", "a=1\nb=2\nc=3\n", "a=1\nb=2\nd=4\n"}));
    ResolveAllReport r = run("app.properties");
    assertEquals(FileOutcome.Result.FULL, outcome(r, "app.properties").result());
    assertEquals("a=1\nb=2\nc=3\nd=4\n", read("app.properties"));
  }

  @Test
  void thePipelineOfTheOptionsIsTheOneThatRuns() throws Exception {
    setUp(files("words.txt", WORDS));
    ResolveAllReport r = ResolveAll.run(List.of(project.getFile("words.txt")),
        new ResolveAll.Options(ComparisonPolicy.DEFAULT, false, ResolverPipeline.none()), f -> null, Set.of(),
        new NullProgressMonitor());
    assertEquals(FileOutcome.Reason.NOTHING_RESOLVED, outcome(r, "words.txt").reason());
  }

  @Test
  void eachFileIsResolvedWithItsOwnOptions() throws Exception {
    setUp(files("a.txt", WORDS, "b.txt", WORDS));
    IFile a = project.getFile("a.txt");
    IFile b = project.getFile("b.txt");
    ResolveAll.Options none = new ResolveAll.Options(ComparisonPolicy.DEFAULT, false, ResolverPipeline.none());
    List<IFile> asked = new ArrayList<>();
    Function<IFile, ResolveAll.Options> options = f -> {
      asked.add(f);
      return f.equals(a) ? none : OPTIONS;
    };
    ResolveAll.run(List.of(a, b), options, f -> null, Set::of, new NullProgressMonitor());
    assertTrue(read("a.txt").contains("<<<<<<<"), "a.txt: its own options resolve nothing");
    assertFalse(read("b.txt").contains("<<<<<<<"), "b.txt: resolved with the defaults");
    assertEquals(List.of(a, b), asked);
  }

  /** Java checks whose locator finds an empty import section at the top; {@code fixImports} is recorded. */
  private static JavaChecks importing(List<String> calls) {
    return new JavaChecks() {
      @Override
      public ImportLocator locator() {
        return text -> java.util.Optional.of(new ImportSection(0, 0, List.of()));
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        calls.add("fix");
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        return null;
      }
    };
  }

  @Test
  void theImportsAreFixedOnlyWhileTheJavaImportsSwitchIsOn() throws Exception {
    setUp(files("A.java", WORDS, "B.java", WORDS));
    ResolveAll.Options off = new ResolveAll.Options(ComparisonPolicy.DEFAULT, false,
        ResolverPipeline.defaults(false, id -> !ImportsResolver.ID.equals(id), List.of()));
    List<String> calls = new ArrayList<>();
    ResolveAllReport r = ResolveAll.run(List.of(project.getFile("A.java")), off, f -> importing(calls), Set.of(),
        new NullProgressMonitor());
    assertEquals(FileOutcome.Result.FULL, outcome(r, "A.java").result());
    assertEquals(List.of(), calls, "the switch is off: as in the editor, the imports stay as they are");
    r = ResolveAll.run(List.of(project.getFile("B.java")), OPTIONS, f -> importing(calls), Set.of(),
        new NullProgressMonitor());
    assertEquals(FileOutcome.Result.FULL, outcome(r, "B.java").result());
    assertEquals(List.of("fix"), calls, "the switch is on: the imports are fixed");
  }
}
