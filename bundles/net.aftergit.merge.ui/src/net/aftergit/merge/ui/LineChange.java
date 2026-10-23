package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.text.IDocument;

/** A document edit expressed in whole lines, as required by MergeModel.onExternalEdit. */
public record LineChange(int startLine, int endLine, int newLineCount) {

  /** See {@link #of(String, int, int, String)}; reads the text of {@code before}. */
  public static LineChange of(IDocument before, int offset, int length, String text) {
    return of(before.get(), offset, length, text);
  }

  /**
   * Computes the line change for replacing [offset, offset+length) of {@code old} by {@code text}. Lines are
   * model lines: only '\n' ends a line ("\r\n" through its '\n'; any other '\r' is content), an unterminated
   * last line counts, the empty line after a final '\n' does not. The document's line tracker is not used, so
   * a tracker that also splits at '\r' cannot disturb the model. Returns {@code null} for a no-op edit.
   */
  public static LineChange of(String old, int offset, int length, String text) {
    if (length == 0 && text.isEmpty()) {
      return null;
    }
    int startLine = count(old, 0, offset);
    boolean atLineStart = offset == 0 || old.charAt(offset - 1) == '\n';
    if (length == 0 && atLineStart && text.endsWith("\n")) {
      return new LineChange(startLine, startLine, count(text, 0, text.length()));
    }
    int end = offset + length;
    int modelBefore = modelLines(old);
    int modelAfter = modelLines(old.substring(0, offset) + text + old.substring(end));
    int endLine = count(old, 0, end) + 1;
    // a range that covers whole lines (column 0 to column 0) and is replaced by whole lines does not touch the end line
    boolean wholeLines = length > 0 && atLineStart && (end == 0 || old.charAt(end - 1) == '\n')
        && (text.isEmpty() || text.endsWith("\n"));
    if (wholeLines) {
      endLine--;
    }
    endLine = Math.min(endLine, Math.max(startLine, modelBefore));
    return new LineChange(startLine, endLine, (endLine - startLine) + (modelAfter - modelBefore));
  }

  /**
   * True when the edit replaced text across a line boundary (two or more old lines, as a formatter's indent edit or
   * a paste over a selection does): its lines are then mapped line by line, see MergeModel.onExternalReplace.
   */
  public boolean spansLines() {
    return endLine - startLine >= 2;
  }

  /** True when the edit changed one line and left one line (typing, or a formatter's edit inside a line). */
  public boolean withinOneLine() {
    return endLine - startLine == 1 && newLineCount == 1;
  }

  /** Model lines [from, to) of {@code text}, without their '\n' and without the '\r' of "\r\n". */
  public static List<String> lines(String text, int from, int to) {
    return lines(text, from, to, false);
  }

  /**
   * Like {@link #lines(String, int, int)}; with {@code trailingCrEndsLine} a lone '\r' at the very end of the text
   * ends the last line instead of being part of it, as in a shared buffer (see ResultDocumentAdapter.chunk).
   */
  public static List<String> lines(String text, int from, int to, boolean trailingCrEndsLine) {
    List<String> out = new ArrayList<>(Math.min(Math.max(0, to - from), 1024));
    int line = 0;
    int start = 0;
    while (line < to && start < text.length()) {
      int nl = text.indexOf('\n', start);
      int end = nl < 0 ? text.length() : nl;
      if (nl < 0 && trailingCrEndsLine && end > start && text.charAt(end - 1) == '\r') {
        end--;
      }
      if (line >= from) {
        out.add(text.substring(start, nl > start && text.charAt(nl - 1) == '\r' ? nl - 1 : end));
      }
      line++;
      start = nl < 0 ? text.length() : nl + 1;
    }
    return out;
  }

  private static int modelLines(String s) {
    return count(s, 0, s.length()) + (!s.isEmpty() && !s.endsWith("\n") ? 1 : 0);
  }

  private static int count(String s, int from, int to) {
    int n = 0;
    for (int i = from; i < to; i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }
}
