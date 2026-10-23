package net.aftergit.merge.mcp.service;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.check.LineProblem;

/**
 * The compile errors a block brings in (design §5.4, as {@code CompileCheck}): errors on its lines whose message ours
 * and theirs do not have in their part of the block; a side whose text is its own (from markers) counts whole.
 */
public final class BlockErrors {
  private BlockErrors() {}

  public static List<LineProblem> newErrors(ModelView.Block block, int[] range, List<LineProblem> result,
      List<LineProblem> ours, List<LineProblem> theirs) {
    List<LineProblem> out = new ArrayList<>();
    for (LineProblem e : result) {
      if (e.line() >= range[0] && e.line() < range[1]
          && !inherited(e, block.ownOurs(), block.oursStart(), block.oursEnd(), ours)
          && !inherited(e, block.ownTheirs(), block.theirsStart(), block.theirsEnd(), theirs)) {
        out.add(e);
      }
    }
    return out;
  }

  private static boolean inherited(LineProblem e, boolean ownText, int start, int end, List<LineProblem> side) {
    for (LineProblem s : side) {
      if (s.message().equals(e.message()) && (ownText || s.line() >= start && s.line() < end)) {
        return true;
      }
    }
    return false;
  }
}
