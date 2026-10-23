package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Imports follow the body: after a Take in the same undo step, and at open after auto-resolution (spec §5.3). */
class JavaImportFixTest {
  static final String PATH = "src/p/B.java";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaPaneSetup.setFixHookForTests(null);
    JavaPaneSetup.setLimitsForTests(JavaPaneSetup.FIX_BUDGET_MILLIS, JavaPaneSetup.MAX_FIXED_CHARS);
    JdtTestHooks.setParseHook(null);
    repos.dispose();
  }

  static String source(String imports, String body) {
    return "package p;\n\n" + imports + "\npublic class B {\n" + body + "}\n";
  }

  static String make(String expression) {
    return "  Object make() {\n    return " + expression + ";\n  }\n";
  }

  private MergeEditorInput open(String base, String ours, String theirs) throws Exception {
    repos.conflict(PATH, base, ours, theirs);
    return ConflictRepos.open(repos.javaProject("import-fix-it", PATH),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  static String result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  @Test
  void takeAddsTheImportItsBodyNeedsInOneUndoStep() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    // ours drops the Set import it does not use; theirs starts using Set in the conflicting line
    MergeEditorInput input = open(source(imports, make("null")),
        source("import java.util.List;\n", make("List.of()")), source(imports, make("Set.of()")));
    MergeModel m = input.session().model();
    String before = result(input);
    assertFalse(before.contains("import java.util.Set;"), before);
    input.viewer().nextConflict();
    input.viewer().takeRight();
    String after = result(input);
    assertTrue(after.contains("import java.util.Set;\n"), after);
    assertTrue(after.contains("return Set.of();"), after);
    assertTrue(after.contains("import java.util.List;\n"), "an import unused before stays: " + after);
    input.viewer().undo();
    assertEquals(before, result(input), "one undo step takes the body and the import back");
    assertEquals(1, m.unresolvedConflicts());
    input.viewer().redo();
    assertEquals(after, result(input));
  }

  @Test
  void takeThatDropsTheOnlyUseRemovesTheImport() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Map;\n";
    MergeEditorInput input = open(source(imports, make("Map.of()")), source(imports, make("Map.of(\"a\", 1)")),
        source(imports, make("List.of(\"b\")")));
    input.viewer().nextConflict();
    input.viewer().takeRight();
    String after = result(input);
    assertFalse(after.contains("import java.util.Map;"), after);
    input.viewer().undo();
    assertTrue(result(input).contains("import java.util.Map;"));
  }

  @Test
  void takingTheImportBlockLeavesTheImportsAsTaken() throws Exception {
    String base = source("import java.util.List;\n", "  int x;\n");
    String ours = source("import java.util.List;\nimport java.util.Map;\n", "  int x;\n");
    String theirs = source("import java.util.List;\nimport java.util.Set;\n", "  int x;\n  Set<String> tags;\n");
    MergeEditorInput input = open(base, ours, theirs);
    MergeBlock block = importConflict(input.session().model());
    input.viewer().selectBlock(block.index());
    input.viewer().takeLeft();
    assertEquals(BlockState.TAKEN_LEFT, block.state());
    assertFalse(result(input).contains("import java.util.Set;"), "the user chose ours' imports");
  }

  @Test
  void anImportRemovedFromATakenImportBlockLeavesItTaken() throws Exception {
    String tail = "  int x;\n  int y;\n";
    // the result uses Map before the Take (the conflict shows base), so the Take leaves Map unused
    String base = source("import java.util.List;\nimport java.util.Map;\n", make("Map.of()") + tail);
    String ours = source("import java.util.List;\nimport java.util.Map;\nimport java.util.Optional;\n",
        make("Map.of(\"a\", 1)") + tail);
    String theirs = source("import java.util.List;\nimport java.util.Set;\n", make("List.of(\"b\")") + tail
        + "  Set<String> tags;\n");
    MergeEditorInput input = open(base, ours, theirs);
    MergeModel m = input.session().model();
    MergeBlock imports = importConflict(m);
    input.viewer().selectBlock(imports.index());
    input.viewer().takeLeft();
    assertTrue(result(input).contains("import java.util.Map;\n"), result(input));
    assertFalse(result(input).contains("import java.util.Set;"), describe(m) + result(input));
    String taken = result(input);
    MergeBlock body = m.blocks().stream().filter(b -> b.region() == Region.BODY && b.state() == BlockState.UNRESOLVED)
        .findFirst().orElseThrow();
    input.viewer().selectBlock(body.index());
    input.viewer().takeRight();
    String after = result(input);
    assertTrue(after.contains("return List.of(\"b\");"), after);
    assertFalse(after.contains("import java.util.Map;"), "the Take dropped the only use of Map: " + describe(m) + after);
    assertEquals(BlockState.TAKEN_LEFT, imports.state(), "the user's choice of the imports stands");
    assertEquals(BlockState.TAKEN_RIGHT, body.state());
    assertFalse(m.resultText(imports.index()).contains("Map"), m.resultText(imports.index()));
    assertTrue(after.contains("import java.util.Optional;\n"), "unused before, so it stays: " + after);
    assertEquals("    return List.of(\"b\");\n", m.resultText(body.index()), "the body block moved with the imports");
    assertEquals(0, m.unresolvedConflicts());
    int line = 0;
    for (MergeBlock b : m.blocks()) {
      assertTrue(b.resultStart() >= line && b.resultEnd() >= b.resultStart(), "blocks stay in order: " + b.index());
      line = b.resultEnd();
    }
    assertTrue(line <= m.document().lineCount());
    input.viewer().undo();
    assertEquals(taken, result(input), "one undo step brings the import and the conflict back");
    assertEquals(BlockState.TAKEN_LEFT, imports.state());
    assertEquals(BlockState.UNRESOLVED, body.state());
    assertTrue(m.resultText(imports.index()).contains("import java.util.Map;"), m.resultText(imports.index()));
    input.viewer().redo();
    assertEquals(after, result(input));
    assertEquals(BlockState.TAKEN_RIGHT, body.state());
  }

  @Test
  void atOpenAnAutoResolvedLineGetsTheImportItNeeds() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    String line = "  Object a = null; Object b = null;\n";
    MergeEditorInput input = open(source(imports, line),
        source("import java.util.List;\n", line.replace("a = null", "a = \"x\"")),
        source(imports, line.replace("b = null", "b = Set.of()")));
    String text = result(input);
    assertTrue(text.contains("Object a = \"x\"; Object b = Set.of();"), text);
    assertTrue(text.contains("import java.util.Set;\n"), text);
    assertFalse(input.viewer().canUndo(), "the fix at open is part of the initial result");
  }

  /** {@code status} logs a {@code thrown}, itself or as the cause of the logged exception. */
  static boolean logs(IStatus status, Class<? extends Throwable> thrown) {
    Throwable e = status.getException();
    return thrown.isInstance(e) || e != null && thrown.isInstance(e.getCause());
  }

  @Test
  void aFailingFixerKeepsTheTakeAndItsUndo() throws Exception {
    JavaPaneSetup.setFixHookForTests(() -> {
      throw new StackOverflowError("an overwhelmed parser");
    });
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      String imports = "import java.util.List;\nimport java.util.Set;\n";
      MergeEditorInput input = open(source(imports, make("null")),
          source("import java.util.List;\n", make("List.of()")), source(imports, make("Set.of()")));
      MergeModel m = input.session().model();
      String before = result(input);
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertTrue(result(input).contains("return Set.of();"), result(input));
      assertFalse(result(input).contains("import java.util.Set;"), "no fix, the Take stays");
      assertEquals(0, m.unresolvedConflicts());
      input.viewer().undo();
      assertEquals(before, result(input));
      assertEquals(1, m.unresolvedConflicts());
      input.viewer().redo();
      input.viewer().undo();
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertEquals(2, logged.stream().filter(st -> logs(st, StackOverflowError.class)).count(),
          "logged once at open and once after changes: " + logged);
      assertTrue(logged.stream().noneMatch(st -> st.getException() instanceof VirtualMachineError),
          "a logged VirtualMachineError would make the workbench offer to exit: " + logged);
    } finally {
      Platform.removeLogListener(l);
    }
  }

  @Test
  void aFailingFixerAtOpenStillOpens() throws Exception {
    JavaPaneSetup.setFixHookForTests(() -> {
      throw new IllegalStateException("a broken fixer");
    });
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    String line = "  Object a = null; Object b = null;\n";
    MergeEditorInput input = open(source(imports, line),
        source("import java.util.List;\n", line.replace("a = null", "a = \"x\"")),
        source(imports, line.replace("b = null", "b = Set.of()")));
    String text = result(input);
    assertTrue(text.contains("Object a = \"x\"; Object b = Set.of();"), text);
    assertFalse(text.contains("import java.util.Set;"), text);
  }

  private static String describe(MergeModel m) {
    StringBuilder sb = new StringBuilder();
    for (MergeBlock b : m.blocks()) {
      sb.append(b.index()).append(' ').append(b.region()).append(' ').append(b.state()).append(' ')
          .append(b.isConflict()).append(" [").append(b.resultStart()).append(',').append(b.resultEnd()).append(")\n");
    }
    return sb.toString();
  }

  private static MergeBlock importConflict(MergeModel m) {
    return m.blocks().stream().filter(b -> b.region() == Region.IMPORTS && b.isConflict()).findFirst().orElseThrow();
  }

  /** The conflict of {@link #takeAddsTheImportItsBodyNeedsInOneUndoStep}: taking theirs needs the Set import. */
  private MergeEditorInput openNeedingSet() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    return open(source(imports, make("null")), source("import java.util.List;\n", make("List.of()")),
        source(imports, make("Set.of()")));
  }

  private static void takeRightTwice(MergeEditorInput input) {
    input.viewer().nextConflict();
    input.viewer().takeRight();
    input.viewer().undo();
    input.viewer().nextConflict();
    input.viewer().takeRight();
  }

  @Test
  void aResultTooLargeOrAFixTooSlowIsSkippedWithOneNoteEach() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      MergeEditorInput input = openNeedingSet();
      JavaPaneSetup.setLimitsForTests(JavaPaneSetup.FIX_BUDGET_MILLIS, 10);
      takeRightTwice(input);
      assertTrue(result(input).contains("return Set.of();"), result(input));
      assertFalse(result(input).contains("import java.util.Set;"), "too large: no fix");
      JavaPaneSetup.setLimitsForTests(0, JavaPaneSetup.MAX_FIXED_CHARS);
      takeRightTwice(input);
      assertFalse(result(input).contains("import java.util.Set;"), "too slow: no fix");
      List<IStatus> notes = logged.stream().filter(st -> st.getSeverity() == IStatus.INFO
          && st.getMessage().contains("/import-fix-it/src/p/B.java")).toList();
      assertEquals(2, notes.size(), "one note for the size, one for the time: " + logged);
      JavaPaneSetup.setLimitsForTests(JavaPaneSetup.FIX_BUDGET_MILLIS, JavaPaneSetup.MAX_FIXED_CHARS);
      takeRightTwice(input);
      assertTrue(result(input).contains("import java.util.Set;\n"), "within the limits again: " + result(input));
    } finally {
      Platform.removeLogListener(l);
    }
  }

  @Test
  void aStackOverflowStopsTheFixerForTheSession() throws Exception {
    int[] calls = {0};
    JavaPaneSetup.setFixHookForTests(() -> {
      calls[0]++;
      throw new StackOverflowError("an overwhelmed parser");
    });
    MergeEditorInput input = openNeedingSet();
    assertEquals(1, calls[0], "the fix at open");
    takeRightTwice(input);
    assertEquals(2, calls[0], "after the first overflow the session makes no more attempts");
    assertTrue(result(input).contains("return Set.of();"), result(input));
  }

  @Test
  void aFixerFailingInsideIsLoggedOncePerSession() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      MergeEditorInput input = openNeedingSet();
      JdtTestHooks.setParseHook(() -> {
        throw new IllegalStateException("a broken rewrite");
      });
      takeRightTwice(input);
      takeRightTwice(input);
      assertEquals(1, logged.stream().filter(st -> logs(st, IllegalStateException.class)).count(),
          "four failed fixes, one warning: " + logged);
      assertTrue(result(input).contains("return Set.of();"), result(input));
    } finally {
      Platform.removeLogListener(l);
    }
  }

  @Test
  void aFixThatRunsPastItsTimeIsDropped() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      MergeEditorInput input = openNeedingSet();
      JavaPaneSetup.setLimitsForTests(300, JavaPaneSetup.MAX_FIXED_CHARS);
      // the time runs out inside the fix, after its deadline started: the later stages must give up
      JdtTestHooks.setParseHook(() -> {
        try {
          Thread.sleep(600);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertTrue(result(input).contains("return Set.of();"), result(input));
      assertFalse(result(input).contains("import java.util.Set;"), "too slow: no fix");
      assertEquals(1, logged.stream().filter(st -> st.getSeverity() == IStatus.INFO
          && st.getMessage().contains("longer than")).count(), logged.toString());
      assertEquals(0, JdtTestHooks.openScratchCopies());
    } finally {
      Platform.removeLogListener(l);
    }
  }

  @Test
  void revertDropsTheImportTheTakeAdded() throws Exception {
    MergeEditorInput input = openNeedingSet();
    String before = result(input);
    input.viewer().nextConflict();
    input.viewer().takeRight();
    assertTrue(result(input).contains("import java.util.Set;\n"), result(input));
    input.viewer().revert();
    assertEquals(before, result(input), "the base text no longer uses Set");
    assertEquals(1, input.session().model().unresolvedConflicts());
    input.viewer().undo();
    assertTrue(result(input).contains("import java.util.Set;\n"), "one undo step: " + result(input));
  }

  @Test
  void revertingAndRerunningAutoResolutionFollowTheImports() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    String line = "  Object a = null; Object b = null;\n";
    MergeEditorInput input = open(source(imports, line),
        source("import java.util.List;\n", line.replace("a = null", "a = \"x\"")),
        source(imports, line.replace("b = null", "b = Set.of()")));
    assertTrue(result(input).contains("import java.util.Set;\n"), result(input));
    input.viewer().revertAllAutoResolved();
    assertFalse(result(input).contains("Set"), "base text again, without the import: " + result(input));
    input.viewer().rerunAutoResolve();
    String text = result(input);
    assertTrue(text.contains("Object a = \"x\"; Object b = Set.of();"), text);
    assertTrue(text.contains("import java.util.Set;\n"), text);
    input.viewer().undo();
    assertFalse(result(input).contains("Set"), "the rerun and its import were one step: " + result(input));
  }

  @Test
  void takeBlockReplacingTheOtherSideSwapsTheImports() throws Exception {
    MergeEditorInput input = openNeedingSet();
    MergeModel m = input.session().model();
    MergeBlock body = m.blocks().stream().filter(b -> b.region() == Region.BODY && b.isConflict()).findFirst()
        .orElseThrow();
    input.viewer().takeBlock(body.index(), net.aftergit.merge.core.merge.ThreeSide.RIGHT, false);
    String right = result(input);
    assertTrue(right.contains("import java.util.Set;\n"), right);
    input.viewer().takeBlock(body.index(), net.aftergit.merge.core.merge.ThreeSide.LEFT, true);
    String left = result(input);
    assertTrue(left.contains("return List.of();"), left);
    assertFalse(left.contains("Set"), "ours replaced theirs, so Set went with it: " + left);
    assertEquals(BlockState.TAKEN_LEFT, body.state());
    input.viewer().undo();
    assertEquals(right, result(input), "the replace and its import fix were one step");
  }

  @Test
  void aCancelledOpenStopsAfterTheImportSections() throws Exception {
    String imports = "import java.util.List;\nimport java.util.Set;\n";
    repos.conflict(PATH, source(imports, make("null")), source("import java.util.List;\n", make("List.of()")),
        source(imports, make("Set.of()")));
    org.eclipse.core.resources.IFile file = repos.javaProject("import-fix-it", PATH);
    org.eclipse.core.runtime.NullProgressMonitor monitor = new org.eclipse.core.runtime.NullProgressMonitor();
    int[] parses = {0};
    JavaBridge.setImportLocatorForTests(text -> {
      parses[0]++;
      monitor.setCanceled(true); // the user cancels while the first text is parsed
      return java.util.Optional.empty();
    });
    try {
      MergeEditorInput input = new MergeEditorInput(file);
      assertThrows(InterruptedException.class, () -> input.prepareInput(monitor));
      assertEquals(1, parses[0], "the other two texts are not parsed");
      assertNull(input.session());
    } finally {
      JavaBridge.setImportLocatorForTests(null);
    }
  }

  @Test
  void withTheJavaImportsSwitchOffNoImportIsFixedAtOpenAfterATakeOrAReRun() throws Exception {
    MergePreferences.setResolverEnabled(ImportsResolver.ID, false);
    int[] fixes = {0};
    JavaPaneSetup.setFixHookForTests(() -> fixes[0]++);
    try {
      String imports = "import java.util.List;\nimport java.util.Set;\n";
      String line = "  Object a = null; Object b = null;\n";
      MergeEditorInput input = open(source(imports, line + make("null")),
          source("import java.util.List;\n", line.replace("a = null", "a = \"x\"") + make("List.of()")),
          source(imports, line.replace("b = null", "b = Set.of()") + make("Set.of()")));
      String opened = result(input);
      assertTrue(opened.contains("Object a = \"x\"; Object b = Set.of();"), "auto-resolved: " + opened);
      assertFalse(opened.contains("import java.util.Set;"), "not fixed at open: " + opened);
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertTrue(result(input).contains("return Set.of();"), result(input));
      assertFalse(result(input).contains("import java.util.Set;"), "not fixed after a Take: " + result(input));
      input.viewer().rerunAutoResolve();
      assertFalse(result(input).contains("import java.util.Set;"), "not fixed after a re-run: " + result(input));
      assertEquals(0, fixes[0], "the fixer never ran");
    } finally {
      MergePreferences.reset();
    }
  }

  @Test
  void switchingTheJavaImportsOffInTheOpenEditorStopsTheFixAtOnce() throws Exception {
    try {
      String imports = "import java.util.List;\nimport java.util.Set;\n";
      MergeEditorInput input = open(source(imports, make("null")),
          source("import java.util.List;\n", make("List.of()")), source(imports, make("Set.of()")));
      int[] fixes = {0};
      JavaPaneSetup.setFixHookForTests(() -> fixes[0]++);
      UiDriver.clickMore(input.viewer(), Messages.settingResolverImports);
      assertFalse(MergePreferences.resolverEnabled(ImportsResolver.ID), "switched off in the toolbar");
      input.viewer().nextConflict();
      input.viewer().takeRight();
      assertTrue(result(input).contains("return Set.of();"), result(input));
      assertFalse(result(input).contains("import java.util.Set;"), "not fixed after the switch: " + result(input));
      assertEquals(0, fixes[0], "the fixer did not run");
    } finally {
      MergePreferences.reset();
    }
  }
}
