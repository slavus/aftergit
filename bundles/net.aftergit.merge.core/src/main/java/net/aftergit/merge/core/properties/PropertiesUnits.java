package net.aftergit.merge.core.properties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.Segmenter;
import net.aftergit.merge.core.units.Unit;

/**
 * The logical entries of a .properties text as units (resolvers 6c design §2): an entry is its key line and the lines
 * a trailing backslash continues; the blank and comment lines above it belong to it; blank and comment lines after the
 * last entry are {@link Unit#TRAILING}. A blank line does not end that ownership: every blank and comment line between
 * two entries belongs to the second one, so a section header travels with the entry below it (and goes with it when
 * that entry is deleted). Keys are compared as written: escapes and backslash-u sequences are not
 * decoded, so a re-encoding is a change.
 */
public final class PropertiesUnits implements Segmenter {
  public static final PropertiesUnits INSTANCE = new PropertiesUnits();

  private PropertiesUnits() {}

  @Override
  public Optional<Body> segment(String text, int from, int to) {
    List<String> lines = LineText.chunkLines(text);
    List<Unit> units = new ArrayList<>();
    int unitStart = 0;
    int i = 0;
    while (i < lines.size()) {
      String line = lines.get(i);
      if (blankOrComment(line)) {
        i++;
        continue;
      }
      int end = i + 1;
      while (end < lines.size() && continues(lines.get(end - 1))) {
        end++;
      }
      units.add(new Unit(key(line), unitStart, end));
      unitStart = end;
      i = end;
    }
    if (unitStart < lines.size()) {
      units.add(new Unit(Unit.TRAILING, unitStart, lines.size()));
    }
    return Optional.of(new Body(0, lines.size(), units));
  }

  /** The key of an entry line as written: up to the first '=', ':' or whitespace that no backslash escapes. */
  public static String key(String line) {
    int i = 0;
    while (i < line.length() && white(line.charAt(i))) {
      i++;
    }
    StringBuilder sb = new StringBuilder();
    for (; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && i + 1 < line.length()) {
        sb.append(c).append(line.charAt(++i));
        continue;
      }
      if (c == '=' || c == ':' || white(c)) {
        break;
      }
      sb.append(c);
    }
    return sb.toString();
  }

  private static boolean blankOrComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (!white(c)) {
        return c == '#' || c == '!';
      }
    }
    return true;
  }

  /** The line ends with an odd number of backslashes: the next line continues the value. */
  public static boolean continues(String line) {
    int n = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      n++;
    }
    return n % 2 == 1;
  }

  private static boolean white(char c) {
    return c == ' ' || c == '\t' || c == '\f';
  }
}
