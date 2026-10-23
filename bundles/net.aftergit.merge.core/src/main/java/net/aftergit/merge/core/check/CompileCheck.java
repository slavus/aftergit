package net.aftergit.merge.core.check;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/**
 * "Auto-resolved, but does not compile" (spec §5.4): an auto-resolved or AI-resolved block with a compile error on
 * one of its lines that the corresponding part of ours and of theirs does not have. Errors are compared by message;
 * a block whose side text is its own (from git's conflict markers) is compared with that whole side.
 */
public final class CompileCheck {
  private CompileCheck() {}

  /** Indices of such blocks; empty while the result, ours or theirs has not been checked (null). */
  public static SortedSet<Integer> failingBlocks(MergeModel model, List<LineProblem> resultErrors,
      List<LineProblem> leftErrors, List<LineProblem> rightErrors) {
    return new TreeSet<>(newErrors(model, resultErrors, leftErrors, rightErrors).keySet());
  }

  /**
   * The errors that make each such block fail, by block index, in the order of {@code resultErrors}: those on its
   * lines that the corresponding part of ours and of theirs does not have. Empty while one of the three is unknown.
   */
  public static SortedMap<Integer, List<LineProblem>> newErrors(MergeModel model, List<LineProblem> resultErrors,
      List<LineProblem> leftErrors, List<LineProblem> rightErrors) {
    SortedMap<Integer, List<LineProblem>> out = new TreeMap<>();
    if (resultErrors == null || leftErrors == null || rightErrors == null) {
      return out;
    }
    for (MergeBlock b : model.blocks()) {
      if (b.state() != BlockState.AUTO_RESOLVED && b.state() != BlockState.AI_RESOLVED) {
        continue;
      }
      List<LineProblem> found = new ArrayList<>();
      for (LineProblem e : resultErrors) {
        if (e.line() >= b.resultStart() && e.line() < b.resultEnd()
            && !inherited(e, b, ThreeSide.LEFT, leftErrors) && !inherited(e, b, ThreeSide.RIGHT, rightErrors)) {
          found.add(e);
        }
      }
      if (!found.isEmpty()) {
        out.put(b.index(), List.copyOf(found));
      }
    }
    return out;
  }

  private static boolean inherited(LineProblem e, MergeBlock b, ThreeSide side, List<LineProblem> sideErrors) {
    boolean ownText = (side == ThreeSide.LEFT ? b.leftOverride() : b.rightOverride()) != null;
    int start = b.source().start(side);
    int end = b.source().end(side);
    for (LineProblem s : sideErrors) {
      if (s.message().equals(e.message()) && (ownText || s.line() >= start && s.line() < end)) {
        return true;
      }
    }
    return false;
  }
}
