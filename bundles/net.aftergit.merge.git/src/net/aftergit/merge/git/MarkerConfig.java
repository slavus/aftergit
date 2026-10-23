package net.aftergit.merge.git;

import java.util.List;
import java.util.Locale;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.markers.ParsedFile;
import net.aftergit.merge.core.model.MarkerStyle;
import net.aftergit.merge.core.text.LineText;
import org.eclipse.jgit.lib.Repository;

/**
 * How unresolved conflicts are written back (spec §5.5, §8.3): the repository's marker style and git's own labels.
 * The style is {@code merge.conflictStyle} (diff3 and zdiff3 write diff3), else that of git's file, else merge.
 */
public record MarkerConfig(MarkerStyle style, String oursLabel, String baseLabel, String theirsLabel) {
  public static final MarkerConfig DEFAULT = new MarkerConfig(MarkerStyle.MERGE, "ours", "base", "theirs");

  /** The style and labels for a file whose git result is {@code workingTree} (may be null). */
  public static MarkerConfig of(Repository repo, String workingTree) {
    List<String> lines = workingTree == null ? List.of() : LineText.of(workingTree).lines();
    ParsedFile parsed = ConflictMarkerParser.parse(lines);
    MarkerStyle style = style(repo.getConfig().getString("merge", null, "conflictStyle"), parsed.diff3());
    if (!parsed.hasConflicts()) {
      return new MarkerConfig(style, DEFAULT.oursLabel(), DEFAULT.baseLabel(), DEFAULT.theirsLabel());
    }
    ParsedFile.Hunk first = parsed.hunks().get(0);
    String base = baseLabel(lines);
    return new MarkerConfig(style, first.oursLabel(), base == null ? DEFAULT.baseLabel() : base,
        first.theirsLabel());
  }

  static MarkerStyle style(String configured, boolean workingTreeDiff3) {
    if (configured != null) {
      String v = configured.trim().toLowerCase(Locale.ROOT);
      if (v.equals("diff3") || v.equals("zdiff3")) {
        return MarkerStyle.DIFF3;
      }
      if (v.equals("merge")) {
        return MarkerStyle.MERGE;
      }
    }
    return workingTreeDiff3 ? MarkerStyle.DIFF3 : MarkerStyle.MERGE;
  }

  /** Label of the first "|||||||" marker between a "<<<<<<<" and its "=======" ("" when bare), or null. */
  static String baseLabel(List<String> lines) {
    boolean inHunk = false;
    int size = ConflictMarkerParser.DEFAULT_MARKER_SIZE;
    for (String line : lines) {
      if (isMarker(line, '<', size)) {
        inHunk = true;
      } else if (inHunk && line.equals("=".repeat(size))) {
        inHunk = false;
      } else if (inHunk && isMarker(line, '|', size)) {
        return line.length() > size + 1 ? line.substring(size + 1) : "";
      }
    }
    return null;
  }

  private static boolean isMarker(String line, char c, int size) {
    if (line.length() < size || !line.substring(0, size).equals(String.valueOf(c).repeat(size))) {
      return false;
    }
    return line.length() == size || line.charAt(size) == ' ';
  }
}
