package net.aftergit.merge.core.manifest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes merged list headers in the style of ours: a clause of ours that has its own physical lines keeps them byte
 * for byte, even past 72 bytes; only the lines the merge writes (a clause that is new or shared a line, a comma added)
 * are written one clause per line and wrapped at 72 bytes (UTF-8).
 */
final class ManifestWriter {
  static final int MAX_BYTES = 72;

  private ManifestWriter() {}

  /** A clause of ours that has its own physical lines: those lines, and whether the last one ends with its comma. */
  private record Lines(List<String> lines, boolean onHeaderLine, boolean comma) {}

  /**
   * {@code clauses} as the header {@code ours} is written: its blank lines above, its own header line when that holds no
   * clause (else the first clause right after "Name: "), then the clauses, commas at the ends of their lines. A clause
   * of ours keeps its lines; the others go one per continuation line.
   */
  static String listHeader(Header ours, List<String> clauses) {
    Map<String, Deque<Lines>> kept = ownLines(ours);
    StringBuilder sb = new StringBuilder(ours.gap());
    if (!ours.firstClauseOnHeaderLine()) {
      sb.append(ours.headerLine()).append('\n');
    }
    for (int i = 0; i < clauses.size(); i++) {
      boolean comma = i + 1 < clauses.size();
      boolean first = i == 0 && ours.firstClauseOnHeaderLine();
      Deque<Lines> candidates = kept.get(clauses.get(i));
      Lines own = candidates == null || candidates.isEmpty() ? null : candidates.peekFirst();
      if (own != null && own.onHeaderLine() == first) {
        candidates.removeFirst();
        appendKept(sb, own, comma);
      } else {
        String text = clauses.get(i) + (comma ? "," : "");
        sb.append(wrap(first ? ours.name() + ": " + text : " " + text));
      }
    }
    return sb.toString();
  }

  /** {@code own}'s lines, with only the comma at the end of the last one added or taken off as {@code comma} says. */
  private static void appendKept(StringBuilder sb, Lines own, boolean comma) {
    List<String> lines = own.lines();
    for (int k = 0; k + 1 < lines.size(); k++) {
      sb.append(lines.get(k)).append('\n');
    }
    String last = lines.get(lines.size() - 1);
    if (comma == own.comma()) {
      sb.append(last).append('\n');
    } else if (comma) {
      sb.append(wrap(last + ","));
    } else {
      String bare = last.substring(0, last.length() - 1);
      if (!bare.isBlank()) { // a comma alone on its line goes with it
        sb.append(bare).append('\n');
      }
    }
  }

  /**
   * The clauses of {@code ours} (by their trimmed text) that start at the start of a line's value and end, with their
   * comma, at the end of a line: the lines they fill. A clause that shares a line with another is not among them.
   */
  private static Map<String, Deque<Lines>> ownLines(Header ours) {
    List<String> lines = new ArrayList<>();
    List<Integer> valueStart = new ArrayList<>(); // where each line's part of the value starts in the line
    if (ours.firstClauseOnHeaderLine()) {
      lines.add(ours.headerLine());
      valueStart.add(ours.headerLine().length() - (ours.value().length() - continuationLength(ours)));
    }
    for (String c : ours.continuations()) {
      lines.add(c);
      valueStart.add(1);
    }
    StringBuilder value = new StringBuilder();
    List<Integer> lineAt = new ArrayList<>(); // the line of each character of the value
    for (int k = 0; k < lines.size(); k++) {
      String part = lines.get(k).substring(valueStart.get(k));
      value.append(part);
      for (int c = 0; c < part.length(); c++) {
        lineAt.add(k);
      }
    }
    Map<String, Deque<Lines>> out = new HashMap<>();
    boolean quoted = false;
    int start = 0;
    for (int i = 0; i <= value.length(); i++) {
      boolean end = i == value.length();
      if (!end && value.charAt(i) == '"') {
        quoted = !quoted;
      }
      if (!end && (value.charAt(i) != ',' || quoted)) {
        continue;
      }
      if (start < i) {
        int first = lineAt.get(start);
        int last = end ? lines.size() - 1 : lineAt.get(i);
        boolean startsLine = start == 0 || lineAt.get(start - 1) != first;
        boolean endsLine = end || i + 1 == value.length() || lineAt.get(i + 1) != last;
        String text = value.substring(start, i).trim();
        if (startsLine && endsLine && !text.isEmpty()) {
          out.computeIfAbsent(text, t -> new ArrayDeque<>())
              .add(new Lines(lines.subList(first, last + 1), ours.firstClauseOnHeaderLine() && first == 0, !end));
        }
      }
      start = i + 1;
    }
    return out;
  }

  private static int continuationLength(Header ours) {
    return ours.continuations().stream().mapToInt(c -> c.length() - 1).sum();
  }

  /** {@code line} as physical lines of at most 72 bytes, continuations starting with one space; no character split. */
  static String wrap(String line) {
    StringBuilder sb = new StringBuilder();
    int bytes = 0;
    int i = 0;
    while (i < line.length()) {
      int cp = line.codePointAt(i);
      int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytes + n > MAX_BYTES) {
        sb.append("\n ");
        bytes = 1;
      }
      sb.appendCodePoint(cp);
      bytes += n;
      i += Character.charCount(cp);
    }
    return sb.append('\n').toString();
  }
}
