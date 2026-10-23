package net.aftergit.merge.ui;

import java.util.List;

/**
 * Decorations of one pane: per-line styles, thin markers where a block has no lines, frames of failing blocks, and
 * in the result the ruler marks of resolved conflicts (block-states design §1, §4).
 */
public record PaneStyle(LineStyle[] lines, List<Marker> emptyMarkers, List<Frame> errorFrames,
    List<Gutter> gutters) {
  public PaneStyle(LineStyle[] lines, List<Marker> emptyMarkers) {
    this(lines, emptyMarkers, List.of(), List.of());
  }

  public PaneStyle(LineStyle[] lines, List<Marker> emptyMarkers, List<Frame> errorFrames) {
    this(lines, emptyMarkers, errorFrames, List.of());
  }

  /** A line drawn at the top of {@code line} (or at the end of the text when line == lineCount). */
  public record Marker(int line, LineStyle.Kind kind) {}

  /** Lines [start, end) of an auto-resolved block that does not compile, framed in the error color (spec §6.4). */
  public record Frame(int start, int end) {}

  /** Where the result of a block that was a conflict came from: ◀ ours, ▶ theirs, ◆ both, ✎ typed by hand. */
  public enum Origin { NONE, LEFT, RIGHT, BOTH, EDITED }

  /** The bar in the result's ruler: taken (taken.strong), edited (edited.strong), auto (auto.strong). */
  public enum Bar { NONE, TAKEN, EDITED, AUTO }

  /** The "A" of an automatic resolution: filled while it needs review, outlined once reviewed. */
  public enum Badge { NONE, PENDING, REVIEWED }

  /** The ruler marks of block {@code block} on result lines [start, end). */
  public record Gutter(int block, int start, int end, Bar bar, Origin origin, Badge badge) {}
}
