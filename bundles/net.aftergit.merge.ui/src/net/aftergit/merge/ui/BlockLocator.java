package net.aftergit.merge.ui;

import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/** Finds blocks by line and navigates between conflicts and changes. */
public final class BlockLocator {
  private BlockLocator() {}

  /** Index of the block covering {@code line} in {@code pane}; an empty block exactly at the line counts. */
  public static int blockAt(MergeModel model, PaneKind pane, int line) {
    int emptyHit = -1;
    for (MergeBlock b : model.blocks()) {
      int s = BlockStyles.start(b, pane);
      int e = BlockStyles.end(b, pane);
      if (s <= line && line < e) {
        return b.index();
      }
      if (s == e && s == line && emptyHit < 0) {
        emptyHit = b.index();
      }
    }
    return emptyHit;
  }

  /**
   * The current block at {@code line} of {@code pane} (block-states design §2): in a side pane only a block that side
   * changed, in the result any block; an empty block exactly at the line counts. -1 outside every block.
   */
  public static int currentAt(MergeModel model, PaneKind pane, int line) {
    return currentAt(model, pane, line, -1);
  }

  /**
   * As {@link #currentAt(MergeModel, PaneKind, int)}, but {@code current} stays the current block while it still
   * covers {@code line} of {@code pane}; an empty block covers only its marker line. Without this an empty block
   * right before another block would lose to it on every caret event or focus gain.
   */
  public static int currentAt(MergeModel model, PaneKind pane, int line, int current) {
    if (current >= 0 && current < model.blocks().size()) {
      MergeBlock c = model.blocks().get(current);
      int s = BlockStyles.start(c, pane);
      int e = BlockStyles.end(c, pane);
      if ((pane == PaneKind.RESULT || BlockStyles.visible(c, pane))
          && (s <= line && line < e || s == e && s == line)) {
        return current;
      }
    }
    int emptyHit = -1;
    for (MergeBlock b : model.blocks()) {
      if (pane != PaneKind.RESULT && !BlockStyles.visible(b, pane)) {
        continue;
      }
      int s = BlockStyles.start(b, pane);
      int e = BlockStyles.end(b, pane);
      if (s <= line && line < e) {
        return b.index();
      }
      if (s == e && s == line && emptyHit < 0) {
        emptyHit = b.index();
      }
    }
    return emptyHit;
  }

  /**
   * Navigation between unresolved conflicts (toolbar "Unresolved ▶").
   * Forward: the first one with {@code resultStart > from}.
   * Backward: the last one with {@code resultStart < from && resultEnd <= from}.
   * -1 if none. Callers pass {@code caretLine - 1} to include a conflict starting at the caret, or
   * {@code max(resultStart, resultEnd - 1)} of the selected block to move past it.
   */
  public static int nextConflict(MergeModel model, int from, boolean forward) {
    int found = -1;
    for (MergeBlock b : model.blocks()) {
      if (!b.isConflict() || b.state() != BlockState.UNRESOLVED) {
        continue;
      }
      if (forward && b.resultStart() > from) {
        return b.index();
      }
      if (!forward && b.resultStart() < from && b.resultEnd() <= from) {
        found = b.index();
      }
    }
    return forward ? -1 : found;
  }

  /**
   * Next/previous block that was a conflict, in any state (block-states design §3). From the current block
   * ({@code fromBlock >= 0}) by block order, so a block without lines never holds the walk up; without one from
   * {@code fromLine} with the semantics of {@link #nextConflict}. -1 if none.
   */
  public static int nextAnyConflict(MergeModel model, int fromBlock, int fromLine, boolean forward) {
    java.util.List<MergeBlock> blocks = model.blocks();
    if (fromBlock >= 0) {
      int step = forward ? 1 : -1;
      for (int i = fromBlock + step; i >= 0 && i < blocks.size(); i += step) {
        if (blocks.get(i).isConflict()) {
          return i;
        }
      }
      return -1;
    }
    int found = -1;
    for (MergeBlock b : blocks) {
      if (!b.isConflict()) {
        continue;
      }
      if (forward && b.resultStart() > fromLine) {
        return b.index();
      }
      if (!forward && b.resultStart() < fromLine && b.resultEnd() <= fromLine) {
        found = b.index();
      }
    }
    return forward ? -1 : found;
  }

  /** Next/previous block visible in {@code pane}; same semantics as {@link #nextConflict} on pane lines. */
  public static int nextChange(MergeModel model, PaneKind pane, int from, boolean forward) {
    int found = -1;
    for (MergeBlock b : model.blocks()) {
      if (!BlockStyles.visible(b, pane)) {
        continue;
      }
      int s = BlockStyles.start(b, pane);
      int e = BlockStyles.end(b, pane);
      if (forward && s > from) {
        return b.index();
      }
      if (!forward && s < from && e <= from) {
        found = b.index();
      }
    }
    return forward ? -1 : found;
  }

  /** Next/previous auto-resolved (rule or AI) block in the result pane. */
  public static int nextAutoResolved(MergeModel model, int from, boolean forward) {
    int found = -1;
    for (MergeBlock b : model.blocks()) {
      if (b.state() != BlockState.AUTO_RESOLVED && b.state() != BlockState.AI_RESOLVED) {
        continue;
      }
      if (forward && b.resultStart() > from) {
        return b.index();
      }
      if (!forward && b.resultStart() < from && b.resultEnd() <= from) {
        found = b.index();
      }
    }
    return forward ? -1 : found;
  }
}
