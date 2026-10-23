package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.markers.ParsedFile;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class HybridInitializerTest {
  static String fixture(String name) {
    try (InputStream in = HybridInitializerTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  @Test
  void calcWithoutWorkingTree() {
    InitResult r = HybridInitializer.initialize(
        new MergeInput(fixture("calc/base.txt"), fixture("calc/ours.txt"), fixture("calc/theirs.txt"), null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(1, r.autoReport().resolved());      // divide() conflict, like IntelliJ magic wand
    assertEquals(1, r.model().unresolvedConflicts()); // precision
    assertTrue(text(r.model()).contains("import java.util.Objects;"));
    assertTrue(text(r.model()).contains(" * Supports basic arithmetic."));
    assertFalse(text(r.model()).contains("multiply"));
    assertTrue(text(r.model()).contains("    private int precision = 2;")); // base text in unresolved block
  }

  @Test
  void workingTreeWithSameHunksAligns() {
    String w = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(0, r.resolvedByGit());
    assertEquals(1, r.model().unresolvedConflicts());
  }

  @Test
  void gitResolvedConflictWinsLikeRerere() {
    String w = "a\nRERERE\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(1, r.resolvedByGit());
    assertEquals("a\nRERERE\nc\n", text(r.model()));
    assertEquals(BlockState.RESOLVED_BY_GIT, r.model().blocks().get(0).state());
  }

  @Test
  void ourAutoResolutionIsKeptWhereGitHasMarkers() {
    String l = "int a = 1; int b = 0;\n", b = "int a = 0; int b = 0;\n", rt = "int a = 0; int b = 3;\n";
    String w = "<<<<<<< ours\n" + l + "=======\n" + rt + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(b, l, rt, w), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals("int a = 1; int b = 3;\n", text(r.model()));
    assertEquals(BlockState.AUTO_RESOLVED, r.model().blocks().get(0).state());
    assertEquals(1, r.autoReport().resolved());
  }

  @Test
  void refinedGitHunksStillAlign() {
    // git (merge style, zealous) splits our single conflict around the shared line "same"
    String w = "a\n<<<<<<< ours\nO1\n=======\nT1\n>>>>>>> theirs\nsame\n"
        + "<<<<<<< ours\nO2\n=======\nT2\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(
        new MergeInput("a\nb\nc\n", "a\nO1\nsame\nO2\nc\n", "a\nT1\nsame\nT2\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(1, r.model().unresolvedConflicts());
  }

  @Test
  void divergentCleanTextFallsBackToMarkers() {
    String w = "a\nDIFFERENT\n<<<<<<< ours\nL\n||||||| base\nb\n=======\nR\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
    assertEquals("a\nDIFFERENT\nb\nc\n", text(r.model()));
    assertEquals(1, r.model().unresolvedConflicts());
    assertEquals("L\n", r.model().sideText(0, net.aftergit.merge.core.merge.ThreeSide.LEFT));
  }

  @Test
  void addAddWithEmptyBase() {
    InitResult r = HybridInitializer.initialize(new MergeInput("", "x\n", "y\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(true));
    assertEquals(1, r.model().unresolvedConflicts());
    assertEquals("", text(r.model()));
  }

  @Test
  void gitDiffersFromOurAutoResolutionGitWins() {
    InitResult r = HybridInitializer.initialize(new MergeInput("int a = 0; int b = 0;\n",
        "int a = 1; int b = 0;\n", "int a = 0; int b = 3;\n", "int a = 1; int b = 4;\n"),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals("int a = 1; int b = 4;\n", text(r.model()));
    assertEquals(BlockState.RESOLVED_BY_GIT, r.model().blocks().get(0).state());
    assertFalse(r.model().blocks().get(0).needsReview());
    assertEquals(1, r.resolvedByGit());
    assertEquals(0, r.autoReport().resolved()); // git overrode our auto-resolution
  }

  @Test
  void cleanDivergenceInsideNonConflictBlockUsesWorkingTreeAndFlags() {
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nB\nc\n", "a\nb\nc\n",
        "a\nB2\nc\n"), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals("a\nB2\nc\n", text(r.model()));
    assertEquals(BlockState.RESOLVED_BY_GIT, r.model().blocks().get(0).state());
    assertTrue(r.model().blocks().get(0).needsReview());
  }

  @Test
  void divergenceOutsideBlocksStillFallsBack() {
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\n", "a\nB\nc\nd\n",
        "a\nb\nc\nd\n", "a\nB\nc\nD\n"), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
  }

  @Test
  void insertionAtEndOfFileAfterBlockFallsBack() {
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\n", "a\nB\n", "a\nb\n", "a\nB\nextra\n"),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
  }

  @Test
  void pureInsertionStrictlyInsideBlockIsApplied() {
    String w = "a\nB\nNEW\nC\nz\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\nz\n", "a\nB\nC\nz\n", "a\nb\nc\nz\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(w, text(r.model()));
    assertTrue(r.model().blocks().get(0).needsReview());
  }

  @Test
  void fallbackKeepsEditedHunkTextThatIsNotInTheStages() {
    String w = "a\nEXTRA\n<<<<<<< ours\nB1 edited\n=======\nB2\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nB1\nc\n", "a\nB2\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
    assertEquals("B1 edited\n", r.model().sideText(0, ThreeSide.LEFT));
    assertEquals("B2\n", r.model().sideText(0, ThreeSide.RIGHT));
    String rendered = ResultWriter.render(r.model(), MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true);
    assertEquals(w, rendered);
    ParsedFile.Hunk h = ConflictMarkerParser.parse(LineText.of(rendered).lines()).hunks().get(0);
    assertEquals(List.of("B1 edited"), h.ours());
    assertEquals(List.of("B2"), h.theirs());
  }

  @Test
  void missingStagesUseTheMarkerFallback() {
    String w = "a\n<<<<<<< HEAD\nmine\n=======\nyours\n>>>>>>> feature\nz\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(null, null, null, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(true));
    assertTrue(r.usedFallback());
    assertEquals(1, r.model().unresolvedConflicts());
    assertEquals("mine\n", r.model().sideText(0, ThreeSide.LEFT));
    assertEquals("yours\n", r.model().sideText(0, ThreeSide.RIGHT));
    ParsedFile.Hunk h = ConflictMarkerParser.parse(LineText.of(w).lines()).hunks().get(0);
    assertEquals(w, ResultWriter.render(r.model(), MarkerStyle.MERGE, h.oursLabel(), h.theirsLabel(), 7,
        "\n", true));
  }

  @Test
  void missingStagesAndWorkingTreeAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> HybridInitializer.initialize(
        new MergeInput(null, null, null, null), ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)));
  }

  @Test
  void editsInsideMarkersSurviveAlignment() {
    // git left markers; the user edited the "ours" section before opening the tool
    String w = "a\n<<<<<<< ours\nL edited\n=======\nR\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    MergeModel m = r.model();
    assertEquals(1, m.unresolvedConflicts());
    assertEquals("L edited\n", m.sideText(0, ThreeSide.LEFT));
    assertEquals("R\n", m.sideText(0, ThreeSide.RIGHT));
    assertEquals(w, ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true));
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\nL edited\nc\n", text(m));
  }

  @Test
  void unchangedHunksDoNotGetOverrides() {
    String w = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertNull(r.model().blocks().get(0).leftOverride());
    assertNull(r.model().blocks().get(0).rightOverride());
  }

  static final String MARKED = "a\n<<<<<<< ours\nfoo bar\n=======\nfoo baz\n>>>>>>> theirs\nc\n";

  @Test
  void aMissingStageIsShownAsTheMarkersShowIt() {
    InitResult r = HybridInitializer.initialize(new MergeInput(null, null, "a\nfoo baz\nc\n", MARKED),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    assertTrue(r.usedFallback());
    assertEquals(Set.of(ThreeSide.LEFT), r.sidesFromMarkers());
    MergeModel m = r.model();
    assertEquals(List.of("a", "foo bar", "c"), m.left().lines());
    assertEquals(List.of("a", "foo baz", "c"), m.right().lines(), "theirs stage holds its hunk: shown as it is");
    MergeBlock b = m.blocks().get(0);
    assertEquals(1, b.source().start(ThreeSide.LEFT));
    assertEquals(2, b.source().end(ThreeSide.LEFT));
    assertEquals(1, b.source().start(ThreeSide.RIGHT));
    assertEquals(2, b.source().end(ThreeSide.RIGHT));
    assertNull(b.leftOverride());
    assertNull(b.rightOverride());
    assertEquals("foo bar\n", m.sideText(0, ThreeSide.LEFT));
    assertEquals(MARKED, ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true));
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\nfoo bar\nc\n", text(m));
  }

  @Test
  void aStageWithoutTheHunkTextIsReplacedByTheMarkers() {
    // ours exists, but the user edited inside git's markers: the stage does not hold the hunk's text
    InitResult r = HybridInitializer.initialize(new MergeInput(null, "a\nX\nc\n", "a\nfoo baz\nc\n", MARKED),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    assertEquals(Set.of(ThreeSide.LEFT), r.sidesFromMarkers());
    assertEquals(List.of("a", "foo bar", "c"), r.model().left().lines());
    assertNull(r.model().blocks().get(0).leftOverride());
  }

  @Test
  void stagesThatHoldTheirHunksStayAsTheyAre() {
    String ours = "x\na\nfoo bar\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(null, ours, "a\nfoo baz\nc\n", MARKED),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    assertTrue(r.usedFallback());
    assertEquals(Set.of(), r.sidesFromMarkers());
    assertEquals(LineText.of(ours).lines(), r.model().left().lines());
    MergeBlock b = r.model().blocks().get(0);
    assertEquals(2, b.source().start(ThreeSide.LEFT));
    assertEquals(3, b.source().end(ThreeSide.LEFT));
  }

  @Test
  void bothSidesFromTheMarkersKeepEveryHunkOnItsOwnLines() {
    String w = "h\n<<<<<<< o\nA\n=======\nB\n>>>>>>> t\nmid\nmid\n<<<<<<< o\nC1\nC2\n=======\n>>>>>>> t\nend\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(null, null, null, w), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none());
    assertEquals(Set.of(ThreeSide.LEFT, ThreeSide.RIGHT), r.sidesFromMarkers());
    MergeModel m = r.model();
    assertEquals(List.of("h", "A", "mid", "mid", "C1", "C2", "end"), m.left().lines());
    assertEquals(List.of("h", "B", "mid", "mid", "end"), m.right().lines());
    MergeBlock second = m.blocks().get(1);
    assertEquals(4, second.source().start(ThreeSide.LEFT));
    assertEquals(6, second.source().end(ThreeSide.LEFT));
    assertEquals(4, second.source().start(ThreeSide.RIGHT));
    assertEquals(4, second.source().end(ThreeSide.RIGHT));
    assertEquals("", m.sideText(1, ThreeSide.RIGHT));
  }

  @Test
  void alignedModelsShowTheStages() {
    String w = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    assertFalse(r.usedFallback());
    assertEquals(Set.of(), r.sidesFromMarkers());
  }

  private static final String ESETH_BASE = "twas bri1lig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the m0me raths outgrabe\n";
  private static final String ESETH_OURS = "twas brillig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the mome raths outgrabe\n";
  private static final String ESETH_THEIRS = "twas bri1lig, and the slithy toves\nDid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the m0me raths outgrabe!\n";
  /** What git merge-file writes for the eseth inputs: one hunk over all five lines. */
  private static final String ESETH_GIT = "<<<<<<< ours\n" + ESETH_OURS + "=======\n" + ESETH_THEIRS + ">>>>>>> theirs\n";

  @Test
  void oneGitHunkOverSeveralAutoResolvedBlocksKeepsOurs() {
    InitResult r = HybridInitializer.initialize(new MergeInput(ESETH_BASE, ESETH_OURS, ESETH_THEIRS, ESETH_GIT),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback(), "the alignment keeps our blocks");
    assertEquals(2, r.autoReport().resolved());
    assertEquals(0, r.model().unresolvedConflicts());
    assertEquals("twas brillig, and the slithy toves\nDid gyre\nand gimble in the wabe\n"
        + "all mimsy were the borogoves\nand the mome raths outgrabe!\n", text(r.model()));
  }

  @Test
  void oneGitHunkOverCleanTextStillFallsBack() {
    // git conflicts where we see no change at all: nothing of ours explains the hunk
    String w = "a\n<<<<<<< ours\nb\n=======\nb\n>>>>>>> theirs\nc\n";
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nb\nc\n", "a\nb\nc\n", w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
  }

  @Test
  void oneGitHunkOverAnOpenAndAResolvedBlockKeepsTheOpenOne() {
    // line 1: both change the same word (open); line 5: different words (auto-resolved); git: one hunk
    String base = "a x\nb\nc\nd\ne y w z\n";
    String ours = "a X\nb\nc\nd\ne Y w z\n";
    String theirs = "a Q\nb\nc\nd\ne y w Z\n";
    String w = "<<<<<<< ours\n" + ours + "=======\n" + theirs + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(base, ours, theirs, w), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(1, r.model().unresolvedConflicts(), "the open conflict stays open");
    assertEquals(BlockState.UNRESOLVED, r.model().blocks().get(0).state());
    assertEquals(BlockState.AUTO_RESOLVED, r.model().blocks().get(1).state());
    assertTrue(text(r.model()).endsWith("e Y w Z\n"));
  }

  @Test
  void oneGitHunkOverAResolvedBlockAndAOneSidedChangeKeepsOurs() {
    String base = "a x w y\nb\nc\nd\ne\n";
    String ours = "a X w y\nb\nc\nd\ne\n";
    String theirs = "a x w Y\nb\nc\nD\ne\n";
    String w = "<<<<<<< ours\n" + ours + "=======\n" + theirs + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(base, ours, theirs, w), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals(0, r.model().unresolvedConflicts());
    assertEquals("a X w Y\nb\nc\nD\ne\n", text(r.model()));
  }

  // Hand edits saved inside git's markers (final review I1/M5): a keep-ours rule must never drop them.
  private static final String INT_BASE = "int a = 0; int b = 0;\n";
  private static final String INT_OURS = "int a = 1; int b = 0;\n";
  private static final String INT_THEIRS = "int a = 0; int b = 3;\n";

  @Test
  void anEditedHunkOverAnAutoResolvedBlockKeepsTheEdit() {
    String w = "<<<<<<< ours\n" + INT_OURS + "// KEEP ME\n=======\n" + INT_THEIRS + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(INT_BASE, INT_OURS, INT_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertEquals(1, r.model().unresolvedConflicts(), "the edited hunk stays open");
    assertEquals(w, ResultWriter.render(r.model(), MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true));
  }

  @Test
  void anEditedHunkOverSeveralAutoResolvedBlocksKeepsTheEdit() {
    String edited = ESETH_THEIRS.replace("Did gyre", "Did gyre KEEP ME");
    String w = "<<<<<<< ours\n" + ESETH_OURS + "=======\n" + edited + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(ESETH_BASE, ESETH_OURS, ESETH_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.model().unresolvedConflicts() > 0, "the edited hunk stays open");
    assertTrue(ResultWriter.render(r.model(), MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true)
        .contains("Did gyre KEEP ME\n"));
  }

  @Test
  void anUneditedDiff3HunkOverAnAutoResolvedBlockKeepsOurs() {
    String w = "<<<<<<< ours\n" + INT_OURS + "||||||| base\n" + INT_BASE + "=======\n" + INT_THEIRS
        + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(INT_BASE, INT_OURS, INT_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertFalse(r.usedFallback());
    assertEquals("int a = 1; int b = 3;\n", text(r.model()));
  }

  @Test
  void anEditedDiff3BaseOverAnAutoResolvedBlockKeepsTheEdit() {
    String w = "<<<<<<< ours\n" + INT_OURS + "||||||| base\n" + INT_BASE + "// KEEP ME\n=======\n" + INT_THEIRS
        + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(INT_BASE, INT_OURS, INT_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertEquals(1, r.model().unresolvedConflicts(), "the edited hunk stays open");
    assertEquals(w, ResultWriter.render(r.model(), MarkerStyle.DIFF3, "ours", "base", "theirs", 7, "\n", true));
  }

  @Test
  void anEditedHunkInTheFallbackIsNotAutoResolved() {
    // clean text differs too (fallback); the hunk's base section is no longer in the base stage
    String w = "EXTRA\n<<<<<<< ours\n" + INT_OURS + "||||||| base\n" + INT_BASE + "// KEEP ME\n=======\n"
        + INT_THEIRS + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(INT_BASE, INT_OURS, INT_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
    assertEquals(1, r.model().unresolvedConflicts());
    assertEquals(w, ResultWriter.render(r.model(), MarkerStyle.DIFF3, "ours", "base", "theirs", 7, "\n", true));
  }

  @Test
  void anUneditedHunkInTheFallbackIsStillAutoResolved() {
    String w = "EXTRA\n<<<<<<< ours\n" + INT_OURS + "||||||| base\n" + INT_BASE + "=======\n" + INT_THEIRS
        + ">>>>>>> theirs\n";
    InitResult r = HybridInitializer.initialize(new MergeInput(INT_BASE, INT_OURS, INT_THEIRS, w),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertTrue(r.usedFallback());
    assertEquals(0, r.model().unresolvedConflicts());
    assertEquals("EXTRA\nint a = 1; int b = 3;\n", text(r.model()));
  }
}
