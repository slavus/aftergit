package net.aftergit.merge.core.markers;

import java.util.ArrayList;
import java.util.List;

/** A working-tree file split into clean segments and complete conflict hunks. */
public record ParsedFile(List<Segment> segments, int markerSize, boolean diff3) {

  public sealed interface Segment permits Clean, Hunk {}

  public record Clean(List<String> lines) implements Segment {}

  /** {@code base} is null when the hunk has no diff3 "|||||||" section. */
  public record Hunk(List<String> ours, List<String> base, List<String> theirs,
                     String oursLabel, String theirsLabel) implements Segment {}

  public boolean hasConflicts() {
    return segments.stream().anyMatch(s -> s instanceof Hunk);
  }

  public List<Hunk> hunks() {
    List<Hunk> result = new ArrayList<>();
    for (Segment s : segments) {
      if (s instanceof Hunk h) {
        result.add(h);
      }
    }
    return result;
  }

  /** Clean lines in order, each hunk replaced by one {@code sentinel} line. */
  public List<String> linesWithSentinels(String sentinel) {
    List<String> result = new ArrayList<>();
    for (Segment s : segments) {
      if (s instanceof Clean c) {
        result.addAll(c.lines());
      } else {
        result.add(sentinel);
      }
    }
    return result;
  }
}
