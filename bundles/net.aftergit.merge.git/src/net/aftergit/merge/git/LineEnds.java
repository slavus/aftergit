package net.aftergit.merge.git;

import net.aftergit.merge.core.markers.ConflictMarkerParser;

/**
 * Line separator of a file's content. Conflict marker lines do not count: JGit writes them with LF even in a CRLF
 * file, git with the file's separator.
 */
public final class LineEnds {
  private LineEnds() {}

  /**
   * "\r\n" or "\n" when every content line that has a separator uses it, null when both occur. Without any content
   * separator the marker lines decide ("\r\n" when they all use it), else "\n". A lone CR is content, not a
   * separator. {@code text} must not be null.
   */
  public static String ofContent(String text) {
    boolean lf = false;
    boolean crlf = false;
    boolean markerCrlf = false;
    boolean markerLf = false;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '\n') {
        continue;
      }
      boolean cr = i > start && text.charAt(i - 1) == '\r';
      String line = text.substring(start, cr ? i - 1 : i);
      if (!isMarkerLine(line)) {
        lf |= !cr;
        crlf |= cr;
      } else {
        markerCrlf |= cr;
        markerLf |= !cr;
      }
      start = i + 1;
    }
    if (lf && crlf) {
      return null;
    }
    if (!lf && !crlf) {
      return markerCrlf && !markerLf ? "\r\n" : "\n";
    }
    return crlf ? "\r\n" : "\n";
  }

  /**
   * Like {@link #ofContent} but never null: when the content lines mix both separators, the one most content
   * lines use (the first content separator on a tie). Marker lines never count.
   */
  public static String ofContentOrMajority(String text) {
    String e = ofContent(text);
    if (e != null) {
      return e;
    }
    int lf = 0;
    int crlf = 0;
    String first = null;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '\n') {
        continue;
      }
      boolean cr = i > start && text.charAt(i - 1) == '\r';
      if (!isMarkerLine(text.substring(start, cr ? i - 1 : i))) {
        if (cr) {
          crlf++;
        } else {
          lf++;
        }
        if (first == null) {
          first = cr ? "\r\n" : "\n";
        }
      }
      start = i + 1;
    }
    return crlf > lf ? "\r\n" : lf > crlf ? "\n" : first;
  }

  /** A git conflict marker line of the default size: "<<<<<<<", "|||||||" or ">>>>>>>" with an optional label, or "=======". */
  public static boolean isMarkerLine(String line) {
    int size = ConflictMarkerParser.DEFAULT_MARKER_SIZE;
    if (line.equals("=".repeat(size))) {
      return true;
    }
    if (line.length() < size) {
      return false;
    }
    char c = line.charAt(0);
    if (c != '<' && c != '|' && c != '>' || !line.substring(0, size).equals(String.valueOf(c).repeat(size))) {
      return false;
    }
    return line.length() == size || line.charAt(size) == ' ';
  }
}
