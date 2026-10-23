package net.aftergit.merge.mcp.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.agents.BlockId;

/** The result text with some blocks replaced by proposals, and where every conflict block lies in it. */
public final class Composition {
  /** {@code ranges}: model lines [start, end) of each conflict block in {@code text}. */
  public record Composed(String text, Map<BlockId, int[]> ranges) {}

  private Composition() {}

  /** {@code replacements}: chunks by the ids of {@code view}'s blocks. */
  public static Composed apply(ModelView view, Map<BlockId, String> replacements) {
    List<String> lines = LineText.chunkLines(view.result());
    StringBuilder sb = new StringBuilder();
    Map<BlockId, int[]> ranges = new LinkedHashMap<>();
    int line = 0;
    int out = 0;
    for (ModelView.Block b : view.blocks()) {
      for (; line < b.resultStart(); line++, out++) {
        sb.append(lines.get(line)).append('\n');
      }
      String chunk = replacements.getOrDefault(b.id(), b.result());
      int n = LineText.chunkLines(chunk).size();
      sb.append(chunk);
      ranges.put(b.id(), new int[] {out, out + n});
      out += n;
      line = b.resultEnd();
    }
    for (; line < lines.size(); line++) {
      sb.append(lines.get(line)).append('\n');
    }
    return new Composed(sb.toString(), ranges);
  }
}
