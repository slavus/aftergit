package net.aftergit.merge.core.manifest;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import net.aftergit.merge.core.text.LineText;

/**
 * One header unit: the blank lines above it ({@code gap}), its first line as written, its name as written, its value
 * with continuation lines joined, whether the first line already holds a clause, and its continuation lines as written
 * (each with its leading space).
 */
record Header(String gap, String headerLine, String name, String value, boolean firstClauseOnHeaderLine,
    List<String> continuations) {
  Header {
    continuations = List.copyOf(continuations);
  }

  static Optional<Header> parse(String unit) {
    List<String> lines = LineText.chunkLines(unit);
    int i = 0;
    StringBuilder gap = new StringBuilder();
    while (i < lines.size() && lines.get(i).isEmpty()) {
      gap.append('\n');
      i++;
    }
    if (i == lines.size()) {
      return Optional.empty();
    }
    Matcher h = ManifestUnits.HEADER.matcher(lines.get(i));
    if (!h.matches()) {
      return Optional.empty();
    }
    StringBuilder value = new StringBuilder(h.group(2));
    for (int k = i + 1; k < lines.size(); k++) {
      if (!lines.get(k).startsWith(" ")) {
        return Optional.empty();
      }
      value.append(lines.get(k), 1, lines.get(k).length());
    }
    return Optional.of(new Header(gap.toString(), lines.get(i), h.group(1), value.toString(), !h.group(2).isBlank(),
        lines.subList(i + 1, lines.size())));
  }
}
