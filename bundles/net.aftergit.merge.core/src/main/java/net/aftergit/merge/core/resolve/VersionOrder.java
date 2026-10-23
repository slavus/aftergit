package net.aftergit.merge.core.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The order of versions the "Newer version wins" resolver uses.
 *
 * <p>A version is two or more runs of digits joined by '.', optionally followed by a qualifier: words joined by '.'
 * or '-' after a '.' or '-' ({@code 1.2.3-SNAPSHOT}, {@code 1.0.0.qualifier}, {@code 4.30.0.v20260305-0817},
 * {@code 1.0-rc1}). A range is {@code [low,high)} with any of '[', '(' and ']', ')' and an optional upper bound.
 *
 * <p>Order: the numeric segments compare as numbers, a missing one counting as 0 ({@code 1.2} equals {@code 1.2.0}).
 * With equal numbers the qualifier decides:
 * <ul>
 * <li>the OSGi placeholder {@code qualifier} equals every qualifier, so {@code 1.0.0.qualifier} equals
 *     {@code 1.0.0} and {@code 1.0.0.v20260305};</li>
 * <li>otherwise the qualifier is split into items at '.', '-' and between letters and digits, compared item by item,
 *     a missing item counting as the release. Known items, from oldest: {@code dev}; {@code alpha} or {@code a};
 *     {@code beta} or {@code b}; {@code milestone} or {@code m}; {@code rc}, {@code cr}, {@code pre},
 *     {@code preview} or {@code ea}; {@code snapshot}; the release (no item, {@code final}, {@code ga},
 *     {@code release}). So {@code 1.0.0} is newer than {@code 1.0.0-SNAPSHOT} and {@code 1.0.0-rc1}, and
 *     {@code -rc2} newer than {@code -rc1};</li>
 * <li>a number item is newer than any known one; any other word comes after the release and compares with other
 *     words and numbers as text ({@code 1.0.0.v1} is newer than {@code 1.0.0}; timestamps such as
 *     {@code v20260305-0817} order by date).</li>
 * </ul>
 * Ranges compare by their lower bound only. Two versions may compare equal and still be written differently
 * ({@code 1.0} and {@code 1.0.0}); the resolver keeps those a conflict. Because the placeholder equals every
 * qualifier, the order is not transitive ({@code 1.0.0.v1} and {@code 1.0.0.v2} both equal {@code 1.0.0.qualifier}):
 * it is no {@link java.util.Comparator}, do not sort with it.
 */
public final class VersionOrder {
  private static final String VERSION = "\\d+(?:\\.\\d+)+(?:[.-][A-Za-z0-9_]+)*";
  private static final Pattern VERSION_SHAPE = Pattern.compile(VERSION);
  private static final Pattern PARTS = Pattern.compile("(\\d+(?:\\.\\d+)+)(?:[.-](.+))?");
  private static final Pattern ITEM = Pattern.compile("\\d+|[^\\d.\\-]+");

  /** Rank of the release among the known qualifier items. */
  private static final int RELEASE = 6;
  private static final Map<String, Integer> KNOWN = Map.ofEntries(Map.entry("dev", 0), Map.entry("alpha", 1),
      Map.entry("a", 1), Map.entry("beta", 2), Map.entry("b", 2), Map.entry("milestone", 3), Map.entry("m", 3),
      Map.entry("rc", 4), Map.entry("cr", 4), Map.entry("pre", 4), Map.entry("preview", 4), Map.entry("ea", 4),
      Map.entry("snapshot", 5), Map.entry("final", RELEASE), Map.entry("ga", RELEASE),
      Map.entry("release", RELEASE));

  private VersionOrder() {}

  /** Whether {@code word} (a whole compound word) has the shape of a version. */
  public static boolean isVersion(String word) {
    return VERSION_SHAPE.matcher(word).matches();
  }

  /** Negative, zero or positive as version (or range) {@code a} is older than, equal to or newer than {@code b}. */
  public static int compare(String a, String b) {
    return compareVersions(lowerBound(a), lowerBound(b));
  }

  /** The version of {@code v}, or of a range its lower bound. */
  private static String lowerBound(String v) {
    if (v.isEmpty() || (v.charAt(0) != '[' && v.charAt(0) != '(')) {
      return v;
    }
    int comma = v.indexOf(',');
    return v.substring(1, comma < 0 ? v.length() - 1 : comma).strip();
  }

  private static int compareVersions(String a, String b) {
    Matcher ma = PARTS.matcher(a);
    Matcher mb = PARTS.matcher(b);
    if (!ma.matches() || !mb.matches()) {
      return a.compareTo(b); // not a version: only text order is left
    }
    int c = compareNumbers(ma.group(1).split("\\."), mb.group(1).split("\\."));
    if (c != 0) {
      return c;
    }
    return compareQualifiers(ma.group(2), mb.group(2));
  }

  private static int compareNumbers(String[] a, String[] b) {
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int c = compareNumber(i < a.length ? a[i] : "0", i < b.length ? b[i] : "0");
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /** Two runs of digits as numbers of any length. */
  private static int compareNumber(String a, String b) {
    String x = stripZeros(a);
    String y = stripZeros(b);
    return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
  }

  private static String stripZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }

  private static int compareQualifiers(String a, String b) {
    if (isPlaceholder(a) || isPlaceholder(b)) {
      return 0;
    }
    List<String> x = items(a);
    List<String> y = items(b);
    for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
      int c = compareItems(i < x.size() ? x.get(i) : null, i < y.size() ? y.get(i) : null);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  private static boolean isPlaceholder(String q) {
    return "qualifier".equalsIgnoreCase(q);
  }

  private static List<String> items(String q) {
    List<String> out = new ArrayList<>();
    if (q != null) {
      Matcher m = ITEM.matcher(q.toLowerCase(Locale.ROOT));
      while (m.find()) {
        out.add(m.group());
      }
    }
    return out;
  }

  /** Two qualifier items; null is a missing item, which counts as the release. */
  private static int compareItems(String a, String b) {
    boolean na = a != null && Character.isDigit(a.charAt(0));
    boolean nb = b != null && Character.isDigit(b.charAt(0));
    if (na && nb) {
      return compareNumber(a, b);
    }
    Integer ka = a == null ? Integer.valueOf(RELEASE) : KNOWN.get(a);
    Integer kb = b == null ? Integer.valueOf(RELEASE) : KNOWN.get(b);
    if (ka != null && kb != null) {
      return Integer.compare(ka, kb);
    }
    if (ka != null) {
      return -1; // a known item (or none) is older than a number or another word
    }
    if (kb != null) {
      return 1;
    }
    return a.compareTo(b); // a number and a word, or two words: as text
  }
}
