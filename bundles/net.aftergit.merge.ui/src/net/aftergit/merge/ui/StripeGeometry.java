package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/** Markers of the 8px overview stripe (as in SmartGit's Conflict Solver). */
final class StripeGeometry {
  static final int WIDTH = 8;
  private static final int HIT_DISTANCE = 10;
  private static final int MIN_VIEWPORT = 5;

  private StripeGeometry() {}

  record Marker(int block, int y, int height, LineStyle.Kind kind, boolean strong) {}

  /** {@code origins}: {@link BlockStyles#origins} of the model as of the last refresh, by block index. */
  static List<Marker> markers(MergeModel model, PaneKind pane, int lineCount, int height,
      PaneStyle.Origin[] origins) {
    double scale = (height - 1) / (double) Math.max(1, lineCount);
    List<Marker> out = new ArrayList<>();
    for (MergeBlock b : model.blocks()) {
      if (!BlockStyles.painted(b, pane)) {
        continue;
      }
      int start = BlockStyles.start(b, pane);
      int end = BlockStyles.end(b, pane);
      int y = (int) Math.round(start * scale);
      int h = end > start ? Math.max(1, (int) Math.round((end - start) * scale)) : 2;
      boolean strong = b.isConflict() && b.state() == BlockState.UNRESOLVED;
      PaneStyle.Origin origin = b.index() < origins.length ? origins[b.index()] : BlockStyles.origin(model, b);
      out.add(new Marker(b.index(), y, h, BlockStyles.kind(b, pane, origin), strong));
    }
    return List.copyOf(out);
  }

  /** Y of the 2px error tick of each line (stripe of the result pane), scaled like the block markers. */
  static int[] errorMarks(int[] lines, int lineCount, int height) {
    double scale = (height - 1) / (double) Math.max(1, lineCount);
    int[] ys = new int[lines.length];
    for (int i = 0; i < lines.length; i++) {
      ys[i] = (int) Math.round(lines[i] * scale);
    }
    return ys;
  }

  static int hit(List<Marker> markers, int y) {
    int best = -1;
    int bestDistance = HIT_DISTANCE + 1;
    for (Marker m : markers) {
      int d = y < m.y() ? m.y() - y : (y >= m.y() + m.height() ? y - (m.y() + m.height() - 1) : 0);
      if (d < bestDistance) {
        bestDistance = d;
        best = m.block();
      }
    }
    return best;
  }

  static int[] viewport(int topLine, int visibleLines, int lineCount, int height) {
    double scale = (height - 1) / (double) Math.max(1, lineCount);
    int y = (int) Math.round(topLine * scale);
    int h = Math.max(MIN_VIEWPORT, (int) Math.round(visibleLines * scale));
    // keep the rectangle inside the stripe: a short file or the last lines would overflow it
    int min = Math.min(MIN_VIEWPORT, height);
    y = Math.max(0, Math.min(y, height - min));
    h = Math.max(min, Math.min(h, height - y));
    return new int[] {y, h};
  }
}
