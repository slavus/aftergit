package net.aftergit.merge.mcp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.mcp.McpRepos;
import org.junit.jupiter.api.Test;

/** validate composes the result with proposals in place of their blocks and knows where each block went. */
class CompositionTest {
  static ModelView view() {
    return ModelView.of(HybridInitializer.initialize(new MergeInput(McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS,
        null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model());
  }

  @Test
  void withoutReplacementsItIsTheResult() {
    ModelView v = view();
    Composition.Composed c = Composition.apply(v, Map.of());
    assertEquals(v.result(), c.text());
    assertArrayEquals(new int[] {1, 2}, c.ranges().get(v.blocks().get(0).id()));
    assertArrayEquals(new int[] {3, 4}, c.ranges().get(v.blocks().get(1).id()));
  }

  @Test
  void aLongerReplacementMovesTheLaterBlocks() {
    ModelView v = view();
    Composition.Composed c = Composition.apply(v, Map.of(v.blocks().get(0).id(), "x\ny\nz\n"));
    assertEquals("a\nx\ny\nz\nc\nd\ne\n", c.text());
    assertArrayEquals(new int[] {1, 4}, c.ranges().get(v.blocks().get(0).id()));
    assertArrayEquals(new int[] {5, 6}, c.ranges().get(v.blocks().get(1).id()));
  }

  @Test
  void anEmptyReplacementRemovesTheBlocksLines() {
    ModelView v = view();
    Composition.Composed c = Composition.apply(v, Map.of(v.blocks().get(1).id(), ""));
    assertEquals("a\nb\nc\ne\n", c.text());
    assertArrayEquals(new int[] {3, 3}, c.ranges().get(v.blocks().get(1).id()));
  }

  @Test
  void theViewKnowsItsBlocks() {
    ModelView v = view();
    assertEquals(2, v.blocks().size());
    assertEquals(2, v.unresolved());
    ModelView.Block b = v.blocks().get(0);
    assertEquals("L1\n", b.ours());
    assertEquals("b\n", b.base());
    assertEquals("R1\n", b.theirs());
    assertEquals("b\n", b.result());
    assertEquals(b, v.block(new net.aftergit.merge.git.agents.BlockId(5, b.id().hash())).orElseThrow());
    assertEquals(McpRepos.OURS, v.ours());
    assertEquals(McpRepos.THEIRS, v.theirs());
  }
}
