package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/**
 * Virtual scroll space shared by the three panes, as in SmartGit's Conflict Solver: every block and every gap between
 * blocks is one segment whose virtual height is the largest of its heights in the panes. Panes are
 * placed by linear interpolation inside the segment that holds the viewport middle.
 */
final class ScrollMap {
  /** start/length indexed by {@link PaneKind#ordinal()}. */
  record Segment(int[] start, int[] length, double vStart, double vLength) {}

  private final List<Segment> segments;
  private final int[] totals;
  private final double virtualLines;

  private ScrollMap(List<Segment> segments, int[] totals, double virtualLines) {
    this.segments = segments;
    this.totals = totals;
    this.virtualLines = virtualLines;
  }

  static ScrollMap build(MergeModel model, int leftLines, int resultLines, int rightLines) {
    int[] totals = new int[3];
    totals[PaneKind.LEFT.ordinal()] = leftLines;
    totals[PaneKind.RESULT.ordinal()] = resultLines;
    totals[PaneKind.RIGHT.ordinal()] = rightLines;
    List<Segment> out = new ArrayList<>();
    int[] pos = new int[3];
    double v = 0;
    for (MergeBlock b : model.blocks()) {
      int[] start = new int[3];
      int[] end = new int[3];
      for (PaneKind k : PaneKind.values()) {
        start[k.ordinal()] = clamp(BlockStyles.start(b, k), totals[k.ordinal()]);
        end[k.ordinal()] = clamp(BlockStyles.end(b, k), totals[k.ordinal()]);
      }
      v = add(out, pos, start, v);
      v = add(out, start, end, v);
      pos = end;
    }
    add(out, pos, totals, v);
    double total = out.isEmpty() ? 0 : out.get(out.size() - 1).vStart() + out.get(out.size() - 1).vLength();
    return new ScrollMap(List.copyOf(out), totals, total);
  }

  private static int clamp(int line, int total) {
    return Math.max(0, Math.min(line, total));
  }

  private static double add(List<Segment> out, int[] from, int[] to, double v) {
    int[] len = new int[3];
    int max = 0;
    for (int i = 0; i < 3; i++) {
      len[i] = Math.max(0, to[i] - from[i]);
      max = Math.max(max, len[i]);
    }
    if (max == 0) {
      return v;
    }
    out.add(new Segment(from.clone(), len, v, max));
    return v + max;
  }

  double virtualLines() {
    return virtualLines;
  }

  double toPane(PaneKind pane, double virtualLine) {
    int p = pane.ordinal();
    if (segments.isEmpty() || virtualLine <= 0) {
      return 0;
    }
    if (virtualLine >= virtualLines) {
      return totals[p];
    }
    Segment s = find(virtualLine);
    double f = (virtualLine - s.vStart()) / s.vLength();
    return s.start()[p] + f * s.length()[p];
  }

  double toVirtual(PaneKind pane, double paneLine) {
    int p = pane.ordinal();
    if (segments.isEmpty() || paneLine <= 0) {
      return 0;
    }
    if (paneLine >= totals[p]) {
      return virtualLines;
    }
    for (Segment s : segments) {
      int len = s.length()[p];
      if (len > 0 && paneLine < s.start()[p] + len) {
        double f = (paneLine - s.start()[p]) / len;
        return s.vStart() + f * s.vLength();
      }
    }
    return virtualLines;
  }

  /** Lines {@code pane} has in the segment holding {@code virtualLine}; 0 when there is none. */
  int segmentLength(PaneKind pane, double virtualLine) {
    if (segments.isEmpty() || virtualLine < 0 || virtualLine >= virtualLines) {
      return 0;
    }
    return find(virtualLine).length()[pane.ordinal()];
  }

  double paneTop(PaneKind pane, double virtualTop, double viewportLines) {
    return Math.max(0, toPane(pane, virtualTop + viewportLines / 2) - viewportLines / 2);
  }

  double virtualTop(PaneKind pane, double paneTop, double viewportLines) {
    return Math.max(0, toVirtual(pane, paneTop + viewportLines / 2) - viewportLines / 2);
  }

  private Segment find(double v) {
    int lo = 0;
    int hi = segments.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (segments.get(mid).vStart() <= v) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return segments.get(lo);
  }
}
