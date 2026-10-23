package net.aftergit.merge.core.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable text split into lines without terminators. Only {@code "\n"} and {@code "\r\n"} separate
 * lines; a lone {@code '\r'} is ordinary content. Remembers the first line separator found (default
 * "\n"), whether both separators occur and whether the text ended with a separator.
 */
public final class LineText {
  private final List<String> lines;
  private final String eol;
  private final boolean trailingNewline;
  private final boolean mixedEol;

  private LineText(List<String> lines, String eol, boolean trailingNewline, boolean mixedEol) {
    this.lines = lines;
    this.eol = eol;
    this.trailingNewline = trailingNewline;
    this.mixedEol = mixedEol;
  }

  public static LineText of(String text) {
    List<String> lines = new ArrayList<>();
    String eol = null;
    boolean sawLf = false;
    boolean sawCrlf = false;
    int start = 0;
    int n = text.length();
    for (int i = 0; i < n; i++) {
      if (text.charAt(i) == '\n') {
        boolean crlf = i > start && text.charAt(i - 1) == '\r';
        if (crlf) {
          sawCrlf = true;
        } else {
          sawLf = true;
        }
        if (eol == null) {
          eol = crlf ? "\r\n" : "\n";
        }
        lines.add(text.substring(start, crlf ? i - 1 : i));
        start = i + 1;
      }
    }
    boolean trailing = n > 0 && start == n;
    if (start < n) {
      lines.add(text.substring(start));
    }
    return new LineText(List.copyOf(lines), eol == null ? "\n" : eol, trailing, sawLf && sawCrlf);
  }

  /**
   * End-of-file newline of the merge result: the working-tree file decides when present (git
   * already merged it); otherwise a side that changed it relative to base wins, ours first.
   *
   * @param workingTree git's working-tree file, may be null
   */
  public static boolean mergeTrailingNewline(LineText base, LineText ours, LineText theirs, LineText workingTree) {
    if (workingTree != null) {
      return workingTree.trailingNewline();
    }
    if (ours.trailingNewline() == base.trailingNewline()) {
      return theirs.trailingNewline();
    }
    return ours.trailingNewline();
  }

  public static LineText ofLines(List<String> lines) {
    return new LineText(List.copyOf(lines), "\n", true, false);
  }

  public int size() {
    return lines.size();
  }

  public String line(int index) {
    return lines.get(index);
  }

  public List<String> lines() {
    return Collections.unmodifiableList(lines);
  }

  public String eol() {
    return eol;
  }

  /** True when both "\n" and "\r\n" separate lines of this text; {@link #eol()} is then the first one. */
  public boolean mixedEol() {
    return mixedEol;
  }

  public boolean trailingNewline() {
    return trailingNewline;
  }

  /** Lines [start, end), each terminated by '\n'. */
  public String chunk(int start, int end) {
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < end; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }

  /** Inverse of {@link #chunk}: splits a chunk into lines. A missing final '\n' is tolerated. */
  public static List<String> chunkLines(String chunk) {
    if (chunk.isEmpty()) {
      return List.of();
    }
    String body = chunk.endsWith("\n") ? chunk.substring(0, chunk.length() - 1) : chunk;
    return List.of(body.split("\n", -1));
  }

  /** Joins lines with {@code eol}; appends a final {@code eol} iff {@code trailingNewline}. */
  public static String render(List<String> lines, String eol, boolean trailingNewline) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) {
        sb.append(eol);
      }
      sb.append(lines.get(i));
    }
    if (trailingNewline && !lines.isEmpty()) {
      sb.append(eol);
    }
    return sb.toString();
  }
}
