package net.aftergit.merge.core.model;

import java.util.Objects;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;

/** One change of the merge with its position in the result and its resolution state. */
public final class MergeBlock {
  private final int index;
  private final MergeRange source;
  private final ConflictType type;
  private final Region region;
  private final String baseText;
  private int resultStart;
  private int resultEnd;
  private BlockState state;
  private Strategy strategy;
  private String resolvedBy;
  private boolean needsReview;
  private String rationale;
  private String leftOverride;
  private String rightOverride;

  public MergeBlock(int index, MergeRange source, ConflictType type, String baseText,
                    int resultStart, int resultEnd, BlockState state, Strategy strategy, String resolvedBy) {
    this(index, source, type, Region.BODY, baseText, resultStart, resultEnd, state, strategy, resolvedBy);
  }

  public MergeBlock(int index, MergeRange source, ConflictType type, Region region, String baseText,
                    int resultStart, int resultEnd, BlockState state, Strategy strategy, String resolvedBy) {
    this.index = index;
    this.source = source;
    this.type = type;
    this.region = Objects.requireNonNull(region, "region");
    this.baseText = baseText;
    this.resultStart = resultStart;
    this.resultEnd = resultEnd;
    this.state = state;
    this.strategy = strategy;
    this.resolvedBy = resolvedBy;
  }

  public int index() { return index; }
  public MergeRange source() { return source; }
  public ConflictType type() { return type; }

  /** The part of the file the block lies in; never changes. */
  public Region region() { return region; }

  public String baseText() { return baseText; }
  public int resultStart() { return resultStart; }
  public int resultEnd() { return resultEnd; }
  public BlockState state() { return state; }
  public Strategy strategy() { return strategy; }
  public String resolvedBy() { return resolvedBy; }
  public boolean needsReview() { return needsReview; }
  public String rationale() { return rationale; }

  /** Own text of the left side (a chunk), or null when the side is the stage slice {@link #source()}. */
  public String leftOverride() { return leftOverride; }

  /** Own text of the right side (a chunk), or null when the side is the stage slice {@link #source()}. */
  public String rightOverride() { return rightOverride; }

  public boolean isConflict() {
    return type.isConflict();
  }

  void setResult(int start, int end) {
    this.resultStart = start;
    this.resultEnd = end;
  }

  void setResolution(BlockState state, Strategy strategy, String resolvedBy, boolean needsReview, String rationale) {
    this.state = state;
    this.strategy = strategy;
    this.resolvedBy = resolvedBy;
    this.needsReview = needsReview;
    this.rationale = rationale;
  }

  /**
   * Gives the block its own side texts (chunks), used instead of the stage slices. Needed when the
   * sides come from a working-tree conflict hunk whose text need not occur in the stages.
   */
  void setSideOverrides(String left, String right) {
    this.leftOverride = left;
    this.rightOverride = right;
  }

  void setState(BlockState state) {
    this.state = state;
  }

  void setNeedsReview(boolean needsReview) {
    this.needsReview = needsReview;
  }

  /** The user changed the block's lines (block-states design §4): it is the user's own text, nothing to review. */
  void markEdited() {
    this.state = BlockState.EDITED;
    this.needsReview = false;
    // typed text is the user's own: no resolver or agent stands behind it any more
    this.resolvedBy = null;
    this.rationale = null;
  }
}
