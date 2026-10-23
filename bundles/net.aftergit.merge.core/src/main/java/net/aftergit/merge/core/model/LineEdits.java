package net.aftergit.merge.core.model;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.Diffs;
import net.aftergit.merge.core.diff.Range;

/**
 * What really changed when a run of lines was replaced: the hunks of a line diff between the old and the new lines.
 * Inside a differing run, lines that differ only in whitespace, and runs of as many new lines as old ones, are paired
 * line for line, so a reformatting changes lines but never melts them into one big replacement.
 *
 * <p>Only the lines are compared, so repeated identical lines make the result ambiguous: removing one of two equal
 * adjacent lines gives the same new run whichever was removed, and the hunk lands where the diff aligns it, not
 * necessarily on the line that was edited.
 */
public final class LineEdits {
  /** Old lines [start, end) became new lines [newStart, newEnd); indices relative to the two runs. */
  public record Hunk(int start, int end, int newStart, int newEnd) {}

  private LineEdits() {}

  /** Hunks in increasing order, not overlapping; empty when the runs are equal. */
  public static List<Hunk> between(List<String> oldLines, List<String> newLines) {
    List<Hunk> out = new ArrayList<>();
    for (Hunk h : differing(oldLines, newLines)) {
      List<String> a = normalized(oldLines.subList(h.start(), h.end()));
      List<String> b = normalized(newLines.subList(h.newStart(), h.newEnd()));
      int i = 0;
      int j = 0;
      for (Range u : withEnd(Diffs.unchanged(a, b), a.size(), b.size())) {
        pairOrKeep(oldLines, newLines, h.start() + i, h.start() + u.start1(), h.newStart() + j,
            h.newStart() + u.start2(), out);
        for (int k = 0; k < u.end1() - u.start1(); k++) {
          line(oldLines, newLines, h.start() + u.start1() + k, h.newStart() + u.start2() + k, out);
        }
        i = u.end1();
        j = u.end2();
      }
    }
    return out;
  }

  private static List<Hunk> differing(List<String> a, List<String> b) {
    List<Hunk> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    for (Range u : withEnd(Diffs.unchanged(a, b), a.size(), b.size())) {
      if (u.start1() > i || u.start2() > j) {
        out.add(new Hunk(i, u.start1(), j, u.start2()));
      }
      i = u.end1();
      j = u.end2();
    }
    return out;
  }

  private static List<Range> withEnd(List<Range> unchanged, int sizeA, int sizeB) {
    List<Range> all = new ArrayList<>(unchanged);
    all.add(new Range(sizeA, sizeA, sizeB, sizeB));
    return all;
  }

  /** k old lines replaced by k new lines are k line edits; any other run stays one hunk. */
  private static void pairOrKeep(List<String> oldLines, List<String> newLines, int start, int end, int newStart,
      int newEnd, List<Hunk> out) {
    if (end - start == newEnd - newStart) {
      for (int k = 0; k < end - start; k++) {
        line(oldLines, newLines, start + k, newStart + k, out);
      }
    } else {
      out.add(new Hunk(start, end, newStart, newEnd));
    }
  }

  private static void line(List<String> oldLines, List<String> newLines, int at, int newAt, List<Hunk> out) {
    if (!oldLines.get(at).equals(newLines.get(newAt))) {
      out.add(new Hunk(at, at + 1, newAt, newAt + 1));
    }
  }

  private static List<String> normalized(List<String> lines) {
    List<String> out = new ArrayList<>(lines.size());
    for (String s : lines) {
      out.add(ComparisonPolicy.IGNORE_WHITESPACES.normalize(s));
    }
    return out;
  }
}
