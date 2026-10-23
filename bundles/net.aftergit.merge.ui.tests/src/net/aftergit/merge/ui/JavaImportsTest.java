package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Import conflicts of Java files disappear when the resolver opens (spec §5.3, §10.4). */
class JavaImportsTest {
  static final String PATH = "src/p/I.java";
  static final String LIST = "import java.util.List;\n";
  static final String MAP = "import java.util.Map;\n";
  static final String SET = "import java.util.Set;\n";

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
    JavaBridge.setImportLocatorForTests(null);
    repos.dispose();
  }

  static String source(String imports) {
    return "package p;\n\n" + imports + "\npublic class I {\n  int x;\n}\n";
  }

  private MergeEditorInput open(String base, String ours, String theirs) throws Exception {
    repos.conflict(PATH, source(base), source(ours), source(theirs));
    return ConflictRepos.open(repos.javaProject("imports-it", PATH),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  static String result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  /** The import conflict was resolved by pass 1 (not by a body resolver that happens to give the same text). */
  static void assertResolvedByImports(MergeEditorInput input) {
    MergeModel m = input.session().model();
    assertNotNull(input.session().imports(), "the merge was split at the import sections");
    MergeBlock b = importBlock(m);
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals("imports", b.resolvedBy());
    assertEquals(0, m.unresolvedConflicts());
  }

  static MergeBlock importBlock(MergeModel m) {
    return m.blocks().stream().filter(b -> b.region() == Region.IMPORTS && b.isConflict()).findFirst().orElseThrow();
  }

  @Test
  void importsAddedOnBothSides() throws Exception {
    MergeEditorInput input = open(LIST, LIST + MAP, LIST + SET);
    assertTrue(repos.read(PATH).contains("<<<<<<< "), "git left markers in the imports");
    MergeModel m = input.session().model();
    MergeBlock b = importBlock(m);
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(Strategy.IMPORTS, b.strategy());
    assertEquals("imports", b.resolvedBy());
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(source(LIST + MAP + SET), result(input));
    input.viewer().selectBlock(b.index());
    input.viewer().revert();
    assertEquals(source(LIST), result(input), "revert gives the base imports back");
    input.viewer().undo();
    assertEquals(BlockState.AUTO_RESOLVED, importBlock(m).state());
    assertEquals(source(LIST + MAP + SET), result(input));
  }

  @Test
  void anImportDeletedOnOneSideStaysDeleted() throws Exception {
    MergeEditorInput input = open(LIST + MAP, LIST, LIST + MAP + SET);
    assertEquals(source(LIST + SET), result(input));
    assertResolvedByImports(input);
  }

  @Test
  void staticAndNormalImportsAreMergedApart() throws Exception {
    String max = "import static java.lang.Math.max;\n";
    String min = "import static java.lang.Math.min;\n";
    MergeEditorInput input = open(LIST, LIST + max, LIST + min + MAP);
    assertEquals(source(LIST + max + min + MAP), result(input));
    assertResolvedByImports(input);
  }

  @Test
  void theSameImportAddedOnBothSidesAppearsOnce() throws Exception {
    MergeEditorInput input = open(LIST, LIST + MAP, LIST + MAP + SET);
    assertEquals(source(LIST + MAP + SET), result(input));
    assertResolvedByImports(input);
  }

  @Test
  void wildcardAndSingleTypeImports() throws Exception {
    // both sides replace the single import, so the texts conflict unless the imports are merged as a set
    MergeEditorInput input = open(LIST, "import java.util.*;\n", MAP);
    assertTrue(repos.read(PATH).contains("<<<<<<< "), "git left markers in the imports");
    assertEquals(source("import java.util.*;\n" + MAP), result(input));
    assertResolvedByImports(input);
  }

  @Test
  void withoutJavaSupportTheImportsStayAConflict() throws Exception {
    JavaBridge.setAvailableForTests(false);
    MergeEditorInput input = open(LIST, LIST + MAP, LIST + SET);
    assertNull(input.session().imports());
    assertEquals(1, input.session().model().unresolvedConflicts());
  }

  @Test
  void nonJavaFilesKeepThePlainMerge() throws Exception {
    String path = "src/p/I.txt";
    repos.conflict(path, source(LIST), source(LIST + MAP), source(LIST + SET));
    MergeEditorInput input = ConflictRepos.open(repos.javaProject("imports-txt", path),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertNull(input.session().imports());
    MergeModel m = input.session().model();
    assertTrue(m.blocks().stream().noneMatch(b -> b.region() == Region.IMPORTS));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void javaFilesOutsideJavaProjectsAreSplitToo() throws Exception {
    repos.conflict(PATH, source(LIST), source(LIST + MAP), source(LIST + SET));
    MergeEditorInput input = ConflictRepos.open(repos.plainProject("imports-plain", PATH),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertEquals(source(LIST + MAP + SET), result(input));
    assertResolvedByImports(input);
  }

  @Test
  void aLocatorThrowingAnExceptionGivesThePlainMerge() throws Exception {
    assertPlainMergeWith(text -> {
      throw new IllegalStateException("broken parser");
    }, IllegalStateException.class);
  }

  @Test
  void aLocatorThrowingAnErrorGivesThePlainMerge() throws Exception {
    assertPlainMergeWith(text -> {
      throw new StackOverflowError("deeply nested source");
    }, StackOverflowError.class);
  }

  @Test
  void aLocatorThrowingALinkageErrorGivesThePlainMerge() throws Exception {
    assertPlainMergeWith(text -> {
      throw new NoClassDefFoundError("org/eclipse/jdt/core/dom/ASTParser");
    }, NoClassDefFoundError.class);
  }

  private void assertPlainMergeWith(ImportLocator broken, Class<? extends Throwable> thrown) throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    JavaBridge.setImportLocatorForTests(broken);
    try {
      MergeEditorInput input = open(LIST, LIST + MAP, LIST + SET);
      assertNotNull(input.viewer(), "the resolver opens");
      assertNull(input.session().imports());
      assertEquals(1, input.session().model().unresolvedConflicts());
      assertEquals(1, logged.stream().filter(st -> JavaImportFixTest.logs(st, thrown)).count(),
          "the failure is logged once: " + logged);
      assertTrue(logged.stream().noneMatch(st -> st.getException() instanceof VirtualMachineError),
          "a logged VirtualMachineError would make the workbench offer to exit: " + logged);
    } finally {
      Platform.removeLogListener(l);
    }
  }

  @Test
  void anImportOursAddedTwiceIsKeptTwice() throws Exception {
    MergeEditorInput input = open(LIST, LIST + MAP + MAP, LIST + SET);
    assertResolvedByImports(input);
    String text = result(input);
    assertTrue(text.contains(MAP + MAP), "redundant but legal, and no text is lost: " + text);
    assertTrue(text.contains(SET), text);
  }
}
