package net.aftergit.merge.core.markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses git conflict markers. Only complete, correctly ordered hunks
 * ("<<<<<<<" [ "|||||||" ] "=======" ">>>>>>>") are recognized; everything else is clean text.
 */
public final class ConflictMarkerParser {
  public static final int DEFAULT_MARKER_SIZE = 7;

  private ConflictMarkerParser() {}

  public static ParsedFile parse(List<String> lines) {
    return parse(lines, DEFAULT_MARKER_SIZE);
  }

  public static ParsedFile parse(List<String> lines, int markerSize) {
    List<ParsedFile.Segment> segments = new ArrayList<>();
    List<String> clean = new ArrayList<>();
    boolean diff3 = false;
    int i = 0;
    while (i < lines.size()) {
      int[] end = new int[1];
      ParsedFile.Hunk hunk = isMarker(lines.get(i), '<', markerSize) ? tryHunk(lines, i, markerSize, end) : null;
      if (hunk == null) {
        clean.add(lines.get(i));
        i++;
        continue;
      }
      if (!clean.isEmpty()) {
        segments.add(new ParsedFile.Clean(List.copyOf(clean)));
        clean.clear();
      }
      segments.add(hunk);
      diff3 |= hunk.base() != null;
      i = end[0];
    }
    if (!clean.isEmpty()) {
      segments.add(new ParsedFile.Clean(List.copyOf(clean)));
    }
    return new ParsedFile(List.copyOf(segments), markerSize, diff3);
  }

  private static ParsedFile.Hunk tryHunk(List<String> lines, int start, int size, int[] endOut) {
    List<String> ours = new ArrayList<>();
    List<String> theirs = new ArrayList<>();
    List<String> base = null;
    int state = 0; // 0 = ours, 1 = base, 2 = theirs
    for (int i = start + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (isMarker(line, '<', size)) {
        return null;
      }
      if (state == 0 && isMarker(line, '|', size)) {
        base = new ArrayList<>();
        state = 1;
        continue;
      }
      if (state < 2 && isSeparator(line, size)) {
        state = 2;
        continue;
      }
      if (state == 2 && isMarker(line, '>', size)) {
        endOut[0] = i + 1;
        return new ParsedFile.Hunk(List.copyOf(ours), base == null ? null : List.copyOf(base),
            List.copyOf(theirs), label(lines.get(start), size), label(line, size));
      }
      (state == 0 ? ours : state == 1 ? base : theirs).add(line);
    }
    return null;
  }

  static boolean isMarker(String line, char c, int size) {
    if (line.length() < size) {
      return false;
    }
    for (int k = 0; k < size; k++) {
      if (line.charAt(k) != c) {
        return false;
      }
    }
    return line.length() == size || line.charAt(size) == ' ';
  }

  static boolean isSeparator(String line, int size) {
    return line.length() == size && line.chars().allMatch(ch -> ch == '=');
  }

  private static String label(String markerLine, int size) {
    return markerLine.length() > size + 1 ? markerLine.substring(size + 1) : "";
  }
}
