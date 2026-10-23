package net.aftergit.merge.core.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The clauses of a list header: split on commas outside double quotes, trimmed; keyed by their first segment. */
final class ManifestClauses {
  private ManifestClauses() {}

  /** The clauses, or empty when one is empty or a quote is not closed. */
  static Optional<List<String>> split(String value) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        if (!add(out, cur)) {
          return Optional.empty();
        }
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    if (quoted || !add(out, cur)) {
      return Optional.empty();
    }
    return Optional.of(out);
  }

  /** The bundle, package, path or namespace a clause is about: its text up to the first ';'. */
  static String key(String clause) {
    int semi = clause.indexOf(';');
    return (semi < 0 ? clause : clause.substring(0, semi)).trim();
  }

  private static boolean add(List<String> out, StringBuilder cur) {
    String c = cur.toString().trim();
    if (c.isEmpty()) {
      return false;
    }
    out.add(c);
    return true;
  }
}
