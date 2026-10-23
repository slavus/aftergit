package net.aftergit.merge.core.imports;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.text.LineText;

/**
 * The imports of a merge (spec §5.3): (base minus what ours deleted minus what theirs deleted) plus what ours added
 * plus what theirs added, static and normal imports apart. Works on import sections as text: every line must be a
 * one-line import declaration, blank, or a one-line comment, else nothing is merged. Ours is the template: its
 * lines keep their order; imports theirs deleted go; imports theirs added come after the nearest import before
 * them in theirs that the result has (after ours' own additions there). The section is not sorted. When ours has no
 * imports but the result has, theirs is the template: ours has no layout to keep then. Two template blank lines that
 * deleted imports leave next to each other become one.
 * Blank and comment lines only theirs has are dropped on purpose: the block stays flagged for review.
 */
public final class ImportMerge {
  private static final String ID = "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*";
  private static final Pattern IMPORT = Pattern.compile("\\s*import\\s+(static\\s+|module\\s+)?(" + ID
      + "(?:\\s*\\.\\s*" + ID + ")*)(\\s*\\.\\s*\\*)?\\s*;\\s*(?://.*)?");
  private static final Pattern OTHER = Pattern.compile("\\s*(?://.*|/\\*(?:(?!\\*/).)*\\*/\\s*)?");

  private ImportMerge() {}

  /** One line of a section; {@code key} as {@link ImportDecl#key()}, null for blank and comment lines. */
  private record Line(String text, String key) {}

  /** The merged section (a chunk), or empty when one of the sections is not only imports, blanks and comments. */
  public static Optional<String> merge(String left, String base, String right) {
    List<Line> l = parse(left);
    List<Line> b = parse(base);
    List<Line> r = parse(right);
    if (l == null || b == null || r == null) {
      return Optional.empty();
    }
    List<Line> out = merge(l, b, r);
    if (keys(l).isEmpty() && !keys(out).isEmpty()) {
      out = merge(r, b, l);
    }
    StringBuilder sb = new StringBuilder();
    for (Line x : out) {
      sb.append(x.text()).append('\n');
    }
    return Optional.of(sb.toString());
  }

  /** The merged lines with {@code l} as the template. */
  private static List<Line> merge(List<Line> l, List<Line> b, List<Line> r) {
    Set<String> inBase = keys(b);
    Set<String> inRight = keys(r);
    List<Line> out = new ArrayList<>();
    for (Line x : l) {
      boolean deletedByRight = x.key() != null && inBase.contains(x.key()) && !inRight.contains(x.key());
      if (!deletedByRight) {
        out.add(x);
      }
    }
    Set<String> present = keys(out);
    for (int i = 0; i < r.size(); i++) {
      Line x = r.get(i);
      if (x.key() == null || inBase.contains(x.key()) || present.contains(x.key())) {
        continue; // not an import; kept or deleted by ours already; or ours added it too
      }
      out.add(insertionPoint(out, r, i, inBase, inRight), x);
      present.add(x.key());
    }
    return collapseBlanks(out, l);
  }

  /**
   * One blank line where deleted imports left two template blank lines next to each other (a removed import group);
   * blank lines the template already has next to each other stay.
   */
  private static List<Line> collapseBlanks(List<Line> out, List<Line> template) {
    Map<Line, Integer> at = new IdentityHashMap<>();
    for (int k = 0; k < template.size(); k++) {
      at.put(template.get(k), k);
    }
    List<Line> kept = new ArrayList<>();
    for (Line x : out) {
      Line prev = kept.isEmpty() ? null : kept.get(kept.size() - 1);
      boolean stacked = prev != null && isBlank(prev) && isBlank(x) && at.containsKey(prev) && at.containsKey(x)
          && at.get(x) != at.get(prev) + 1;
      if (!stacked) {
        kept.add(x);
      }
    }
    return kept;
  }

  private static boolean isBlank(Line x) {
    return x.key() == null && x.text().isBlank();
  }

  /**
   * Where an import theirs added goes: after the nearest import before it in theirs that the result has, past the
   * imports only ours added right there; else before the nearest such import after it; else after the result's
   * last import (at the end when it has none).
   */
  private static int insertionPoint(List<Line> out, List<Line> right, int i, Set<String> inBase,
      Set<String> inRight) {
    for (int k = i - 1; k >= 0; k--) {
      int at = indexOf(out, right.get(k).key());
      if (at >= 0) {
        at++;
        while (at < out.size() && out.get(at).key() != null && !inBase.contains(out.get(at).key())
            && !inRight.contains(out.get(at).key())) {
          at++;
        }
        return at;
      }
    }
    for (int k = i + 1; k < right.size(); k++) {
      int at = indexOf(out, right.get(k).key());
      if (at >= 0) {
        return at;
      }
    }
    for (int k = out.size() - 1; k >= 0; k--) {
      if (out.get(k).key() != null) {
        return k + 1;
      }
    }
    return out.size();
  }

  private static int indexOf(List<Line> lines, String key) {
    if (key == null) {
      return -1;
    }
    for (int k = 0; k < lines.size(); k++) {
      if (key.equals(lines.get(k).key())) {
        return k;
      }
    }
    return -1;
  }

  private static Set<String> keys(List<Line> lines) {
    Set<String> out = new LinkedHashSet<>();
    for (Line x : lines) {
      if (x.key() != null) {
        out.add(x.key());
      }
    }
    return out;
  }

  /** The lines of a chunk, or null when one is not an import, a blank line or a one-line comment. */
  private static List<Line> parse(String chunk) {
    List<Line> out = new ArrayList<>();
    for (String s : LineText.chunkLines(chunk)) {
      Matcher m = IMPORT.matcher(s);
      if (m.matches()) {
        String kind = m.group(1) == null ? "" : m.group(1).strip() + " ";
        out.add(new Line(s, kind + m.group(2).replaceAll("\\s", "") + (m.group(3) == null ? "" : ".*")));
      } else if (OTHER.matcher(s).matches()) {
        out.add(new Line(s, null));
      } else {
        return null;
      }
    }
    return out;
  }
}
