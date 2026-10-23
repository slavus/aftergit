package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class MergeModelMultiLineTest {
  // result: 0 "{", 1 "  a = 1;" (block 0: conflict), 2 "  b;", 3 "  c = 3;" (block 1: theirs only), 4 "}"
  static MergeModel model() {
    LineText l = LineText.of("{\n  a = 2;\n  b;\n  c;\n}\n");
    LineText b = LineText.of("{\n  a = 1;\n  b;\n  c;\n}\n");
    LineText r = LineText.of("{\n  a = 3;\n  b;\n  c = 3;\n}\n");
    return MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
  }

  static List<String> lines(MergeModel m) {
    return LineText.chunkLines(m.document().chunk(0, m.document().lineCount()));
  }

  static String chunk(List<String> lines) {
    StringBuilder sb = new StringBuilder();
    for (String s : lines) {
      sb.append(s).append('\n');
    }
    return sb.toString();
  }

  /** Puts {@code now} into the document behind the model's back and reports it as the UI adapter does. */
  static void replaceAll(MergeModel m, List<String> now) {
    List<String> old = lines(m);
    m.document().replaceLines(0, old.size(), chunk(now));
    m.onExternalReplace(0, old, now);
  }

  static List<String> reindented(List<String> lines) {
    List<String> out = new ArrayList<>();
    for (String s : lines) {
      out.add(s.replace("  ", "\t"));
    }
    return out;
  }

  @Test
  void aReindentKeepsEveryBlockOnItsLines() {
    MergeModel m = model();
    m.setFormattingElsewhere(true); // another editor's save formats the file
    replaceAll(m, reindented(lines(m)));
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(2, m.blocks().get(0).resultEnd());
    assertEquals("\ta = 1;\n", m.resultText(0));
    assertEquals("\tc = 3;\n", m.resultText(1));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state(), "reindenting resolves nothing");
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aReindentKeepsTheReviewFlagOfAnAutoResolvedBlock() {
    MergeModel m = model();
    m.applyResolution(0, "  a = 23;\n", BlockState.AUTO_RESOLVED, Strategy.DEFAULT, "auto", null);
    assertTrue(m.blocks().get(0).needsReview());
    m.setFormattingElsewhere(true);
    replaceAll(m, reindented(lines(m)));
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertTrue(m.blocks().get(0).needsReview(), "nothing to review was added or taken away");
  }

  @Test
  void aOneLineReindentKeepsTheConflictOpen() {
    MergeModel m = model();
    int[] fired = {0};
    m.addChangeListener(() -> fired[0]++);
    m.document().replaceLines(1, 2, "\ta = 1;\n");
    m.onExternalReplace(1, List.of("  a = 1;"), List.of("\ta = 1;"));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(1, fired[0]);
  }

  @Test
  void aReindentHereEditsResolvedBlocksButLeavesConflictsOpen() {
    MergeModel m = model(); // nobody else is formatting: the user changed whitespace, which may mean something
    m.take(0, ThreeSide.LEFT);
    replaceAll(m, reindented(lines(m)));
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals(BlockState.EDITED, m.blocks().get(1).state());
    MergeModel open = model();
    replaceAll(open, reindented(lines(open)));
    assertEquals(BlockState.UNRESOLVED, open.blocks().get(0).state(), "a conflict stays open whoever reindents");
    assertEquals(1, open.unresolvedConflicts());
  }

  @Test
  void aOneLineRespaceOfAResolvedBlockEditsItUnlessFormattedElsewhere() {
    MergeModel m = model();
    m.take(0, ThreeSide.LEFT);
    m.document().replaceLines(1, 2, "\ta = 2;\n");
    m.setFormattingElsewhere(true);
    m.onExternalReplace(1, List.of("  a = 2;"), List.of("\ta = 2;"));
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state(), "formatted by another editor's save");
    m.setFormattingElsewhere(false);
    m.document().replaceLines(1, 2, "\ta=2;\n");
    m.onExternalReplace(1, List.of("\ta = 2;"), List.of("\ta=2;"));
    assertEquals(BlockState.EDITED, m.blocks().get(0).state(), "typed here");
  }

  @Test
  void aRealChangeAmongReindentedLinesStillEditsTheBlock() {
    MergeModel m = model();
    m.setFormattingElsewhere(true);
    List<String> now = reindented(lines(m));
    now.set(1, "\ta = 9;");
    replaceAll(m, now);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state(), "its own line changed");
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state(), "only reindented");
  }

  @Test
  void takeAfterAReformatReplacesOnlyTheBlock() {
    MergeModel m = model();
    replaceAll(m, reindented(lines(m)));
    m.take(0, ThreeSide.LEFT);
    assertEquals(List.of("{", "  a = 2;", "\tb;", "\tc = 3;", "}"), lines(m));
  }

  @Test
  void aChangedLineOutsideTheBlocksLeavesThemAlone() {
    MergeModel m = model();
    List<String> now = new ArrayList<>(lines(m));
    now.set(2, "  bb;");
    now.set(4, "} // end");
    replaceAll(m, now);
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals("  a = 1;\n", m.resultText(0));
    assertEquals("  c = 3;\n", m.resultText(1));
  }

  @Test
  void joinedLinesAreOneEditAsBefore() {
    MergeModel m = model();
    List<String> now = new ArrayList<>(lines(m));
    now.subList(1, 3).clear();
    now.add(1, "  a = 1; b;");
    replaceAll(m, now);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals(2, m.blocks().get(1).resultStart());
    assertEquals("  c = 3;\n", m.resultText(1));
  }

  @Test
  void aReplacementOfOneLineIsAPlainEdit() {
    MergeModel a = model();
    MergeModel b = model();
    a.onExternalReplace(1, List.of("  a = 1;"), List.of("  a = 1;", "  x;"));
    b.onExternalEdit(1, 2, 2);
    assertEquals(b.snapshot(), a.snapshot());
  }

  @Test
  void aMultiLineReplaceNotifiesOnce() {
    MergeModel m = model();
    int[] fired = {0};
    m.addChangeListener(() -> fired[0]++);
    replaceAll(m, reindented(lines(m)));
    assertEquals(1, fired[0]);
  }

  @Test
  void derivedTextShiftsBlocksAndKeepsTheirStates() {
    MergeModel m = model();
    m.applyDerivedText("import x.X;\n" + m.document().chunk(0, m.document().lineCount()));
    assertEquals(2, m.blocks().get(0).resultStart());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertTrue(m.isAutoResolvable(0), "the block still holds its base text");
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
  }

  @Test
  void derivedTextInsideABlockKeepsItsState() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    List<String> now = new ArrayList<>(lines(m));
    now.set(1, "  a = 33;");
    m.applyDerivedText(chunk(now));
    assertEquals(BlockState.TAKEN_RIGHT, m.blocks().get(0).state());
    assertEquals("  a = 33;\n", m.resultText(0));
  }

  @Test
  void textBeforeAutoResolutionHasTheBaseTextOfAutoResolvedBlocks() {
    LineText l = LineText.of("x\nint a = 1; int b = 0;\n");
    LineText b = LineText.of("x\nint a = 0; int b = 0;\n");
    LineText r = LineText.of("x\nint a = 0; int b = 3;\n");
    MergeModel m = MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    ResolverPipeline.defaults(false).run(m);
    assertEquals("x\nint a = 1; int b = 3;\n", m.document().chunk(0, m.document().lineCount()));
    assertEquals("x\nint a = 0; int b = 0;\n", m.textBeforeAutoResolution());
  }

  /** A document that runs {@code during} once, right after its first replaceLines, while the model applies it. */
  static final class Hooked implements ResultDocument {
    final LinesResultDocument delegate;
    Runnable during;

    Hooked(MergeModel m) {
      delegate = new LinesResultDocument(lines(m));
    }

    @Override
    public int lineCount() {
      return delegate.lineCount();
    }

    @Override
    public String chunk(int startLine, int endLine) {
      return delegate.chunk(startLine, endLine);
    }

    @Override
    public void replaceLines(int startLine, int endLine, String chunk) {
      delegate.replaceLines(startLine, endLine, chunk);
      Runnable r = during;
      during = null;
      if (r != null) {
        r.run();
      }
    }
  }

  static Hooked hook(MergeModel m) {
    Hooked d = new Hooked(m);
    m.rebindDocument(d);
    return d;
  }

  @Test
  void derivedTextThatReplacesAResolvedBlockLetsRevertReplaceItAgain() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    m.applyDerivedText("{\nZZ\n  c = 3;\n}\n"); // "  a = 3;" and "  b;" became "ZZ"
    assertEquals("ZZ\n", m.resultText(0), "the block holds the line that replaced its own");
    assertEquals(BlockState.TAKEN_RIGHT, m.blocks().get(0).state());
    assertEquals("  c = 3;\n", m.resultText(1));
    m.revert(0);
    assertEquals(List.of("{", "  a = 1;", "  c = 3;", "}"), lines(m));
    assertEquals("  c = 3;\n", m.resultText(1));
  }

  @Test
  void derivedTextPartlyOverABlockGrowsItOverTheWholeRun() {
    MergeModel m = model();
    m.takeBoth(0, ThreeSide.LEFT); // block 0 = [1,3): "  a = 2;", "  a = 3;"
    List<String> now = new ArrayList<>(lines(m));
    now.subList(2, 4).clear(); // "  a = 3;" (the block's last line) and "  b;" (outside) ...
    now.add(2, "  ab;"); // ... became one line
    m.applyDerivedText(chunk(now));
    assertEquals("  a = 2;\n  ab;\n", m.resultText(0));
    assertEquals(BlockState.TAKEN_BOTH, m.blocks().get(0).state());
    assertEquals("  c = 3;\n", m.resultText(1));
    m.revert(0);
    assertEquals(List.of("{", "  a = 1;", "  c = 3;", "}"), lines(m));
  }

  @Test
  void derivedTextOverTwoBlocksGoesToTheFirstAndEditsTheOther() {
    MergeModel m = model();
    m.take(0, ThreeSide.RIGHT);
    m.applyDerivedText("{\nZZ\n}\n"); // both blocks' lines and the one between became "ZZ"
    assertEquals("ZZ\n", m.resultText(0));
    assertEquals(BlockState.TAKEN_RIGHT, m.blocks().get(0).state());
    assertEquals(2, m.blocks().get(1).resultStart());
    assertEquals(2, m.blocks().get(1).resultEnd());
    assertEquals(BlockState.EDITED, m.blocks().get(1).state());
    m.revert(0);
    assertEquals(List.of("{", "  a = 1;", "}"), lines(m));
    assertEquals(2, m.blocks().get(1).resultStart(), "the other block stays after the reverted one");
  }

  @Test
  void aDerivedEditInsideTheModelsOwnEditKeepsTheGuardUp() {
    MergeModel m = model();
    Hooked d = hook(m);
    boolean[] guarded = {false};
    d.during = () -> {
      m.applyDerivedText("// x\n" + m.document().chunk(0, m.document().lineCount()));
      guarded[0] = m.isApplyingOwnEdit();
    };
    m.take(0, ThreeSide.LEFT);
    assertTrue(guarded[0], "the outer edit is still being applied");
    assertFalse(m.isApplyingOwnEdit());
  }

  @Test
  void aTakeInsideADerivedEditKeepsTheGuardUp() {
    MergeModel m = model();
    Hooked d = hook(m);
    boolean[] guarded = {false};
    d.during = () -> {
      m.take(1, ThreeSide.LEFT);
      guarded[0] = m.isApplyingOwnEdit();
    };
    m.applyDerivedText("// x\n" + m.document().chunk(0, m.document().lineCount()));
    assertTrue(guarded[0], "the outer edit is still being applied");
    assertFalse(m.isApplyingOwnEdit());
  }

  @Test
  void anExternalReplaceDuringTheModelsOwnEditIsIgnored() {
    MergeModel m = model();
    Hooked d = hook(m);
    d.during = () -> m.onExternalReplace(2, List.of("  b;", "  c = 3;"), List.of("  bb;", "  cc;"));
    int[] fired = {0};
    m.addChangeListener(() -> fired[0]++);
    m.take(0, ThreeSide.LEFT);
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals(3, m.blocks().get(1).resultStart());
    assertEquals(1, fired[0], "only the Take notifies");
  }

  @Test
  void aMultiLineReplaceInsertingAtAnEmptyConflictBlockFillsIt() {
    LineText l = LineText.of("p\nx\nq\n");
    LineText b = LineText.of("p\nq\n");
    LineText r = LineText.of("p\ny\nq\n");
    MergeModel m = MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(1, m.blocks().get(0).resultEnd(), "an insertion conflict is empty in the result");
    replaceAll(m, List.of("p", "new", "q"));
    assertEquals("new\n", m.resultText(0));
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
  }

  @Test
  void aReplaceThatChangesNoModelLineStillNotifies() {
    MergeModel m = model();
    int[] fired = {0};
    m.addChangeListener(() -> fired[0]++);
    m.onExternalReplace(3, List.of("  c = 3;", "}"), List.of("  c = 3;", "}"));
    assertEquals(1, fired[0]);
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state(), "no line of the block changed");
  }

  @Test
  void textBeforeAutoResolutionKeepsOtherBlocksAndHandlesAdjacentOnes() {
    ConflictType conflict = new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    LineText side = LineText.of("x\na\nb\nc\ny\n");
    List<MergeBlock> blocks = List.of(
        new MergeBlock(0, new MergeRange(1, 2, 1, 2, 1, 2), conflict, "a\n", 1, 2, BlockState.AUTO_RESOLVED,
            Strategy.DEFAULT, "auto"),
        new MergeBlock(1, new MergeRange(2, 3, 2, 3, 2, 3), conflict, "b\n", 2, 3, BlockState.AUTO_RESOLVED,
            Strategy.DEFAULT, "auto"),
        new MergeBlock(2, new MergeRange(3, 4, 3, 4, 3, 4), conflict, "c\n", 3, 4, BlockState.TAKEN_LEFT,
            Strategy.NONE, null));
    MergeModel m = new MergeModel(side, side, side, new LinesResultDocument(List.of("x", "A", "B", "C", "y")),
        blocks);
    assertEquals("x\na\nb\nC\ny\n", m.textBeforeAutoResolution());
  }

  /**
   * "package p;", "", imports block [2,3) in {@code state} (base "import a.B;"), an empty block at 3, "",
   * "class X {}".
   */
  static MergeModel withImports(BlockState state) {
    ConflictType conflict = new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    LineText side = LineText.of("package p;\n\nimport a.B;\n\nclass X {}\n");
    List<MergeBlock> blocks = List.of(
        new MergeBlock(0, new MergeRange(2, 3, 2, 3, 2, 3), conflict, Region.IMPORTS, "import a.B;\n", 2, 3, state,
            Strategy.IMPORTS, "imports"),
        new MergeBlock(1, new MergeRange(3, 3, 3, 3, 3, 3), conflict, Region.BODY, "", 3, 3, BlockState.UNRESOLVED,
            Strategy.NONE, null));
    return new MergeModel(side, side, side,
        new LinesResultDocument(List.of("package p;", "", "import a.A;", "", "class X {}")), blocks);
  }

  @Test
  void anImportAddedAfterAutoResolvedImportsJoinsThem() {
    MergeModel m = withImports(BlockState.AUTO_RESOLVED);
    m.applyDerivedText("package p;\n\nimport a.A;\nimport b.C;\n\nclass X {}\n");
    assertEquals("import a.A;\nimport b.C;\n", m.resultText(0));
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertEquals(4, m.blocks().get(1).resultStart(), "the empty block stays after the imports");
    assertEquals(4, m.blocks().get(1).resultEnd());
    m.revert(0);
    assertEquals(List.of("package p;", "", "import a.B;", "", "class X {}"), lines(m), "reverted with them");
  }

  @Test
  void anImportAddedNextToImportsTheUserTookStaysOutside() {
    MergeModel m = withImports(BlockState.TAKEN_LEFT);
    m.applyDerivedText("package p;\n\nimport a.A;\nimport b.C;\n\nclass X {}\n");
    assertEquals("import a.A;\n", m.resultText(0), "the user's choice is not widened");
  }
}
