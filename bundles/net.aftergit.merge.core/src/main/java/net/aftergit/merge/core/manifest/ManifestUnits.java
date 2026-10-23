package net.aftergit.merge.core.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.Segmenter;
import net.aftergit.merge.core.units.Unit;

/**
 * The headers of a manifest as units (resolvers 6c design §3): a header line and the lines that continue it (starting
 * with one space); blank lines end a section and belong to the next header; blank lines at the end are
 * {@link Unit#TRAILING}. The key is the section's Name ("" for the main section), U+0000 and the lower-case header
 * name. A line that is neither a header nor a continuation makes the text unreadable.
 */
public final class ManifestUnits implements Segmenter {
  public static final ManifestUnits INSTANCE = new ManifestUnits();
  static final Pattern HEADER = Pattern.compile("([A-Za-z0-9][A-Za-z0-9_-]*): ?(.*)");

  private ManifestUnits() {}

  @Override
  public Optional<Body> segment(String text, int from, int to) {
    List<String> lines = LineText.chunkLines(text);
    List<Unit> units = new ArrayList<>();
    String section = "";
    boolean newSection = false;
    int unitStart = 0;
    int i = 0;
    while (i < lines.size()) {
      String line = lines.get(i);
      if (line.isEmpty()) {
        newSection = true;
        i++;
        continue;
      }
      Matcher h = HEADER.matcher(line);
      if (line.startsWith(" ") || !h.matches()) {
        return Optional.empty();
      }
      int end = i + 1;
      StringBuilder value = new StringBuilder(h.group(2));
      while (end < lines.size() && lines.get(end).startsWith(" ")) {
        value.append(lines.get(end), 1, lines.get(end).length());
        end++;
      }
      String name = h.group(1).toLowerCase(Locale.ROOT);
      if (newSection) {
        section = name.equals("name") ? value.toString() : "\u0000section" + i;
        newSection = false;
      }
      units.add(new Unit(section + "\u0000" + name, unitStart, end));
      unitStart = end;
      i = end;
    }
    if (unitStart < lines.size()) {
      units.add(new Unit(Unit.TRAILING, unitStart, lines.size()));
    }
    return Optional.of(new Body(0, lines.size(), units));
  }
}
