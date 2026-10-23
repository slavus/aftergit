package net.aftergit.merge.core.structure;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/** What the structure needs of one block, copied where the model lives so the tree can be built on another thread. */
public record BlockFacts(int index, BlockState state, boolean conflict, boolean needsReview, MergeRange source,
    int resultStart, int resultEnd) {

  /** Every block of {@code m}, in index order. */
  public static List<BlockFacts> of(MergeModel m) {
    List<BlockFacts> out = new ArrayList<>();
    for (MergeBlock b : m.blocks()) {
      out.add(new BlockFacts(b.index(), b.state(), b.isConflict(), b.needsReview(), b.source(), b.resultStart(),
          b.resultEnd()));
    }
    return List.copyOf(out);
  }
}
