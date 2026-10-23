package net.aftergit.merge.mcp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.agents.BlockId;

/**
 * What the tools read of a model, copied at once (on the UI thread for an open resolver), so no model is shared
 * between threads: the full ours, theirs and result texts and every conflict block. Lines are 0-based model lines.
 */
public record ModelView(String ours, String theirs, String result, List<Block> blocks) {
  public record Block(BlockId id, BlockState state, boolean needsReview, String resolvedBy, String rationale,
      String ours, String base, String theirs, String result, int resultStart, int resultEnd, int oursStart,
      int oursEnd, int theirsStart, int theirsEnd, boolean ownOurs, boolean ownTheirs) {}

  public ModelView {
    blocks = List.copyOf(blocks);
  }

  public static ModelView of(MergeModel m) {
    List<Block> blocks = new ArrayList<>();
    for (MergeBlock b : m.blocks()) {
      if (!b.isConflict()) {
        continue;
      }
      int i = b.index();
      blocks.add(new Block(BlockId.of(m, i), b.state(), b.needsReview(), b.resolvedBy(), b.rationale(),
          m.sideText(i, ThreeSide.LEFT), b.baseText(), m.sideText(i, ThreeSide.RIGHT), m.resultText(i),
          b.resultStart(), b.resultEnd(), b.source().start(ThreeSide.LEFT), b.source().end(ThreeSide.LEFT),
          b.source().start(ThreeSide.RIGHT), b.source().end(ThreeSide.RIGHT), b.leftOverride() != null,
          b.rightOverride() != null));
    }
    return new ModelView(LineText.render(m.left().lines(), "\n", true), LineText.render(m.right().lines(), "\n", true),
        m.document().chunk(0, m.document().lineCount()), blocks);
  }

  /** The block {@code id} names: the same id, else the first with its hash (its index moved). */
  public Optional<Block> block(BlockId id) {
    for (Block b : blocks) {
      if (b.id().equals(id)) {
        return Optional.of(b);
      }
    }
    for (Block b : blocks) {
      if (b.id().hash().equals(id.hash())) {
        return Optional.of(b);
      }
    }
    return Optional.empty();
  }

  public int unresolved() {
    int n = 0;
    for (Block b : blocks) {
      if (b.state() == BlockState.UNRESOLVED) {
        n++;
      }
    }
    return n;
  }
}
