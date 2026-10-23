package net.aftergit.merge.core.model;

import java.util.List;

/** Block positions and states, captured for undo/redo. Document text is restored separately. */
public record ModelSnapshot(List<Entry> entries) {
  public record Entry(int resultStart, int resultEnd, BlockState state, Strategy strategy,
                      String resolvedBy, boolean needsReview, String rationale) {}
}
