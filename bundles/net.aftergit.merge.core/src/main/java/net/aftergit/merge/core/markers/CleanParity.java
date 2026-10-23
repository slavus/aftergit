package net.aftergit.merge.core.markers;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.Diffs;
import net.aftergit.merge.core.diff.Range;
import net.aftergit.merge.core.markers.ParsedFile.Hunk;
import net.aftergit.merge.core.text.LineText;

/**
 * Compares the clean parts (text outside conflict markers) of two merge results of the same inputs, ours and git's
 * (spec §10.2, §10.3). Each conflict hunk becomes a sentinel line of its own kind for each text, so a hunk is never
 * paired with a hunk of the other text. The two sequences are aligned; every differing region is judged by content:
 * <ul>
 * <li>each clean line of git's in the region must occur on at least one side of one of our hunks in that region:
 * git's clean text may only move into our conflict (a coarser hunk, also where git merged cleanly), never vanish;
 * with repeated lines the alignment of the duplicates is ambiguous, so one side is enough;</li>
 * <li>our clean lines in the region need at least one git hunk in that region (we resolved inside git's conflict);
 * their content is not checked, resolvers produce new lines.</li>
 * </ul>
 * A region violating either rule is one deviation. Differing hunk granularity (git coalesces or splits hunks) is no
 * deviation. Marker style, labels and line ends do not matter; the final newline is not compared.
 */
public final class CleanParity {
  private static final String OURS_HUNK = "\u0000aftergit-parity-ours\u0000";
  private static final String GIT_HUNK = "\u0000aftergit-parity-git\u0000";

  private CleanParity() {}

  /** Number of deviating regions and a description of the first one (null when there is none). */
  public record Result(int deviations, String first) {
    public boolean clean() {
      return deviations == 0;
    }
  }

  public static Result compare(String ours, String git) {
    ParsedFile po = ConflictMarkerParser.parse(LineText.of(ours).lines());
    ParsedFile pg = ConflictMarkerParser.parse(LineText.of(git).lines());
    List<String> a = po.linesWithSentinels(OURS_HUNK);
    List<String> b = pg.linesWithSentinels(GIT_HUNK);
    List<Hunk> oursHunks = po.hunks();
    List<Range> unchanged = new ArrayList<>(Diffs.unchanged(a, b));
    unchanged.add(new Range(a.size(), a.size(), b.size(), b.size()));
    int deviations = 0;
    String first = null;
    int ia = 0;
    int ib = 0;
    int hunksBefore = 0;
    for (Range u : unchanged) {
      List<String> oursSegments = a.subList(ia, u.start1());
      List<String> gitSegments = b.subList(ib, u.start2());
      int hunks = (int) oursSegments.stream().filter(OURS_HUNK::equals).count();
      if (!oursSegments.isEmpty() || !gitSegments.isEmpty()) {
        String problem = problem(oursSegments, gitSegments, oursHunks.subList(hunksBefore, hunksBefore + hunks));
        if (problem != null) {
          deviations++;
          if (first == null) {
            first = problem + ": ours " + oursSegments + " vs git " + gitSegments
                + " (segments " + ia + ".." + u.start1() + " / " + ib + ".." + u.start2()
                + ", a hunk counts as one segment)";
          }
        }
      }
      hunksBefore += hunks;
      ia = u.end1();
      ib = u.end2();
    }
    return new Result(deviations, first);
  }

  private static String problem(List<String> oursSegments, List<String> gitSegments, List<Hunk> oursHunks) {
    boolean gitHunk = gitSegments.contains(GIT_HUNK);
    if (!gitHunk && oursSegments.stream().anyMatch(l -> !OURS_HUNK.equals(l))) {
      return "clean text of ours without a git hunk";
    }
    for (String line : gitSegments) {
      if (GIT_HUNK.equals(line)) {
        continue;
      }
      if (oursHunks.stream().noneMatch(h -> h.ours().contains(line) || h.theirs().contains(line))) {
        return "clean line of git missing from ours";
      }
    }
    return null;
  }
}
