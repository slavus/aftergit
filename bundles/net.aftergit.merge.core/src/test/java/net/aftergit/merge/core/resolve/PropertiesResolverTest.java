package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.properties.PropertiesUnits;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.Unit;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design §2: .properties entries merged by key. */
class PropertiesResolverTest {
  static MergeModel resolve(String fileName, String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        new ResolverPipeline(List.of(new PropertiesResolver(), new SimpleTextResolver())).forFile(fileName)).model();
  }

  static MergeModel resolve(String base, String ours, String theirs) {
    return resolve("messages.properties", base, ours, theirs);
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  static MergeBlock conflict(MergeModel m) {
    return m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow();
  }

  @Test
  void keysAddedOnBothSidesAreBothTakenOursFirst() {
    MergeModel m = resolve("a=1\nb=2\n", "a=1\nb=2\nc=3\n", "a=1\nb=2\nd=4\n");
    assertEquals("a=1\nb=2\nc=3\nd=4\n", text(m));
    MergeBlock b = conflict(m);
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(PropertiesResolver.ID, b.resolvedBy());
    assertEquals(Strategy.SEMANTIC, b.strategy());
    assertTrue(b.needsReview());
  }

  @Test
  void aKeyChangedOnBothSidesStaysAConflictAndNoTextResolverGuesses() {
    MergeModel m = resolve("a=x y z\n", "a=X y z\n", "a=x y Z\n"); // simple would merge the words
    assertEquals(1, m.unresolvedConflicts());
    assertEquals("a=x y z\n", text(m));
  }

  @Test
  void otherFilesAreLeftToTheTextResolvers() {
    MergeModel m = resolve("messages.txt", "a=x y z\n", "a=X y z\n", "a=x y Z\n");
    assertEquals("a=X y Z\n", text(m));
    assertEquals("simple", conflict(m).resolvedBy());
  }

  @Test
  void theSameKeyAddedOnBothSides() {
    assertEquals("a=1\nb=2\nc=3\nd=4\n", text(resolve("a=1\n", "a=1\nb=2\nc=3\n", "a=1\nb=2\nd=4\n")));
    assertEquals(1, resolve("a=1\n", "a=1\nb=2\n", "a=1\nb=3\n").unresolvedConflicts());
  }

  @Test
  void aKeyDeletedOnOneSideAndUntouchedOnTheOtherIsDeleted() {
    MergeModel m = resolve("a=1\nb=2\nc=3\n", "a=1\nc=3\n", "a=1\nb=2\nd=4\nc=3\n");
    assertEquals("a=1\nd=4\nc=3\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void aDeleteMeetingAChangeStaysAConflict() {
    assertEquals(1, resolve("a=1\nb=2\n", "a=1\n", "a=1\nb=3\n").unresolvedConflicts());
  }

  @Test
  void commentsAboveAnEntryTravelWithIt() {
    MergeModel m = resolve("a=1\n", "a=1\n# the colour\nc=red\n", "a=1\n# the size\nd=10\n");
    assertEquals("a=1\n# the colour\nc=red\n# the size\nd=10\n", text(m));
  }

  @Test
  void continuationLinesBelongToTheirEntry() {
    MergeModel m = resolve("a=1\n", "a=1\nc=one \\\n  two\n", "a=1\nd=4\n");
    assertEquals("a=1\nc=one \\\n  two\nd=4\n", text(m));
  }

  @Test
  void blankLinesAndCommentsAtTheEndStayAtTheEnd() {
    MergeModel m = resolve("a=1\n\n# end\n", "a=1\nb=2\n\n# end\n", "a=1\nc=3\n\n# end\n");
    assertEquals("a=1\nb=2\nc=3\n\n# end\n", text(m));
  }

  @Test
  void aReEncodingCountsAsAChange() {
    MergeModel m = resolve("a=caf\\u00e9\n", "a=café\n", "a=caf\\u00e9\nb=2\n");
    assertEquals("a=café\nb=2\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void aKeyTwiceInOneSideLeavesTheBlockAlone() {
    assertEquals(1, resolve("a=1\n", "a=1\nb=2\nb=5\n", "a=1\nc=3\n").unresolvedConflicts());
  }

  @Test
  void keysAreReadAsWritten() {
    assertEquals("a\\=b", PropertiesUnits.key("a\\=b=1"));
    assertEquals("key", PropertiesUnits.key("  key : value"));
    assertEquals("key", PropertiesUnits.key("key value"));
    assertEquals("caf\\u00e9", PropertiesUnits.key("caf\\u00e9=1"));
  }

  @Test
  void onlyPropertiesFilesAreResolved() {
    ResolverPipeline p = new ResolverPipeline(List.of(new PropertiesResolver()));
    assertEquals(List.of("properties"), p.forFile("plugin.properties").ids());
    assertEquals(List.of("properties"), p.forFile("Messages.PROPERTIES").ids());
    assertEquals(List.of(), p.forFile("plugin.xml").ids());
    assertEquals(List.of(), p.ids());
  }

  @Test
  void evenAndOddTrailingBackslashes() {
    // two backslashes end in a literal backslash: no continuation, d=4 is its own entry
    MergeModel m = resolve("a=1\n", "a=1\nc=x\\\\\n", "a=1\nd=4\n");
    assertEquals("a=1\nc=x\\\\\nd=4\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
    // three backslashes continue
    m = resolve("a=1\nz=0\n", "a=1\nc=x\\\\\\\n  y\nz=0\n", "a=1\nd=4\nz=0\n");
    assertEquals("a=1\nc=x\\\\\\\n  y\nd=4\nz=0\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void aContinuationChangedOnOneSideWhileAnotherEntryChangesOnTheOther() {
    MergeModel m = resolve("a=one \\\n  two\nb=1\n", "a=one \\\n  three\nb=1\n", "a=one \\\n  two\nb=2\n");
    assertEquals("a=one \\\n  three\nb=2\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void bangCommentsTravelWithTheirEntry() {
    MergeModel m = resolve("a=1\n", "a=1\n! the colour\nc=red\n", "a=1\n! the size\nd=10\n");
    assertEquals("a=1\n! the colour\nc=red\n! the size\nd=10\n", text(m));
  }

  @Test
  void colonAndWhitespaceSeparatorsMerge() {
    MergeModel m = resolve("a:1\nb 2\nc\t3\n", "a:1\nb 2\nc\t3\nx : 9\n", "a:7\nb 2\nc\t3\n");
    assertEquals("a:7\nb 2\nc\t3\nx : 9\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
    // the same key with a different separator is the same key
    assertEquals(1, resolve("a=1\n", "a=1\nb=2\n", "a=1\nb 3\n").unresolvedConflicts());
  }

  @Test
  void crlfLineEndings() {
    MergeModel m = resolve("a=1\r\nb=2\r\n", "a=1\r\nb=2\r\nc=3\r\n", "a=1\r\nb=2\r\nd=4\r\n");
    assertEquals("a=1\nb=2\nc=3\nd=4\n", text(m).replace("\r", ""));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void noFinalNewline() {
    MergeModel m = resolve("a=1\nb=2", "a=1\nb=2\nc=3", "a=1\nb=2\nd=4");
    assertEquals(0, m.unresolvedConflicts());
    String t = text(m);
    assertTrue(t.contains("c=3") && t.contains("d=4"), t);
    assertTrue(t.contains("c=3\nd=4") || t.contains("c=3\r\nd=4"), t);
  }

  @Test
  void aMovedKeyIsAConflict() {
    assertEquals(1, resolve("a=1\nb=2\nc=3\n", "b=2\na=1\nc=3\n", "a=1\nb=2\nc=4\n").unresolvedConflicts());
  }

  @Test
  void aKeyTwiceInBaseLeavesTheBlockAlone() {
    assertEquals(1, resolve("a=1\na=2\n", "a=1\na=2\nb=3\n", "a=1\na=2\nc=4\n").unresolvedConflicts());
  }

  @Test
  void greedyDoesNotGuessEitherWhenTheKeyConflicts() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a=x y z\n", "a=X y z\n", "a=x y Z\n", null),
        ComparisonPolicy.DEFAULT, new ResolverPipeline(List.of(new PropertiesResolver(), new GreedyTextResolver(),
            new SimpleTextResolver())).forFile("m.properties")).model();
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aDanglingContinuationOnTheLastLineDoesNotSwallowTheNextEntry() {
    MergeModel m = resolve("a=1\n", "a=1\nb=2\\\n", "a=1\nd=4\n");
    assertFalse(text(m).contains("b=2\\\nd=4"), text(m));
  }

  @Test
  void segmentTilesTheTextIntoEntries() {
    String text = "# head\n\na=1\n! c\nb=one \\\n  two\\\\\nc:3\n\n# end\n";
    Body body = PropertiesUnits.INSTANCE.segment(text, 0, 0).orElseThrow();
    assertTrue(body.tiles());
    assertEquals(0, body.start());
    assertEquals(9, body.end());
    List<String> keys = body.units().stream().map(Unit::key).toList();
    assertEquals(List.of("a", "b", "c", Unit.TRAILING), keys);
    assertEquals(List.of(0, 3, 6, 7), body.units().stream().map(Unit::start).toList());
  }

  @Test
  void crossBlockAddAddOfTheSameKeyIsAConflict() {
    MergeModel m = resolve("a=1\nc=1\ng=2\n", "a=1\nf=x y\nc=1\ng=3\n", "a=1\nc=2\ng=2\nf=one\n");
    assertNoDuplicateKeys(text(m));
    assertTrue(m.unresolvedConflicts() > 0, text(m));
  }

  @Test
  void crossBlockMoveWithADeleteIsAConflict() {
    MergeModel m = resolve("a=2\nc=one \\\n  three\ne=1\nf=2\ng=1\n", "a=2\ne=1\nf=2\ng=1\n",
        "g=1\na=2\nc=one \\\n  four\ne=1\nf=2\n");
    assertTrue(m.unresolvedConflicts() > 0, text(m));
  }

  @Test
  void crossBlockReorderWithADeleteIsAConflict() {
    MergeModel m = resolve("a=1\nd=1\ne=1\ng=1\n", "g=1\na=1\nd=1\ne=1\n", "a=0\nd=2\ne=1\n");
    assertTrue(m.unresolvedConflicts() > 0, text(m));
  }

  private static void assertNoDuplicateKeys(String text) {
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (String line : text.split("\n")) {
      if (!line.isBlank() && !line.startsWith("#")) {
        assertTrue(seen.add(PropertiesUnits.key(line)), "duplicate key in " + text);
      }
    }
  }
}
