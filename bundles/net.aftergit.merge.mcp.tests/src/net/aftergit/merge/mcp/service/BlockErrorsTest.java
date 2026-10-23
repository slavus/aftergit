package net.aftergit.merge.mcp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import org.junit.jupiter.api.Test;

/** New errors of a block are those ours and theirs do not have in the same part (like CompileCheck). */
class BlockErrorsTest {
  static final ModelView.Block BLOCK = new ModelView.Block(new net.aftergit.merge.git.agents.BlockId(0,
      "0123456789ab"), net.aftergit.merge.core.model.BlockState.AI_RESOLVED, true, "ai:test-agent", "",
      "L\n", "b\n", "R\n", "X\n", 1, 2, 1, 2, 1, 2, false, false);

  @Test
  void anErrorOnlyTheResultHasIsNew() {
    List<LineProblem> out = BlockErrors.newErrors(BLOCK, new int[] {1, 2}, List.of(new LineProblem(1, "bad")),
        List.of(), List.of());
    assertEquals(List.of(new LineProblem(1, "bad")), out);
  }

  @Test
  void anErrorOursHasThereIsInherited() {
    assertEquals(List.of(), BlockErrors.newErrors(BLOCK, new int[] {1, 2}, List.of(new LineProblem(1, "bad")),
        List.of(new LineProblem(1, "bad")), List.of()));
  }

  @Test
  void anErrorOutsideTheBlockIsNotItsOwn() {
    assertEquals(List.of(), BlockErrors.newErrors(BLOCK, new int[] {1, 2}, List.of(new LineProblem(4, "bad")),
        List.of(), List.of()));
  }

  @Test
  void anErrorElsewhereInTheSideDoesNotCountUnlessTheSideIsOwnText() {
    assertEquals(1, BlockErrors.newErrors(BLOCK, new int[] {1, 2}, List.of(new LineProblem(1, "bad")),
        List.of(new LineProblem(4, "bad")), List.of()).size());
    ModelView.Block own = new ModelView.Block(BLOCK.id(), BLOCK.state(), true, "", "", "L\n", "b\n", "R\n", "X\n",
        1, 2, 1, 2, 1, 2, true, false);
    assertEquals(0, BlockErrors.newErrors(own, new int[] {1, 2}, List.of(new LineProblem(1, "bad")),
        List.of(new LineProblem(4, "bad")), List.of()).size());
  }
}
