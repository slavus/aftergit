package net.aftergit.merge.ui;

/**
 * How one line of a pane is decorated (block-states design §1, §4). {@code border}: how the top and bottom borders
 * are drawn; {@code hatched}: the stripes of an auto-resolved block that needs review (result only).
 */
public record LineStyle(Kind kind, Fill fill, boolean topBorder, boolean bottomBorder, Border border,
    boolean hatched) {
  public enum Kind { NONE, ADDED, REMOVED, MODIFIED, CONFLICT, AUTO, AI, DROPPED }

  public enum Fill { NONE, LIGHT, STRONG }

  /** Solid for AI blocks, dotted for a block to review, dashed for a side that was not taken. */
  public enum Border { SOLID, DOT, DASH }

  public static final LineStyle NONE = new LineStyle(Kind.NONE, Fill.NONE, false, false, Border.SOLID, false);

  public LineStyle(Kind kind, Fill fill, boolean topBorder, boolean bottomBorder, boolean dotted) {
    this(kind, fill, topBorder, bottomBorder, dotted ? Border.DOT : Border.SOLID, false);
  }

  public boolean dotted() {
    return border == Border.DOT;
  }
}
