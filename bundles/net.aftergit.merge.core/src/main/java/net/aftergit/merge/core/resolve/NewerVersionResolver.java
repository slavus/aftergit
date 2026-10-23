package net.aftergit.merge.core.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.model.Strategy;

/**
 * "Newer version wins" (off by default): a conflict whose base, ours and theirs differ only in versions takes, at each
 * place a version stands, the higher of the two sides' versions ({@link VersionOrder}). Only in build and bundle
 * descriptors ({@link #appliesTo}): in code or prose a number with a dot (a decimal, an IP address) is no version.
 *
 * <p>A place is a compound word with the shape of a version ({@link VersionOrder#isVersion}) or a version range
 * ({@code [1.2,2.0)}, also wrapped across lines); everything else must be the same text on all three sides. At each
 * place the result is exactly one side's text, the whole version or the whole range, never a mix:
 * <ul>
 * <li>a place only one side changed takes that side, as any merge does;</li>
 * <li>a place both sides changed takes the higher one; different places may take different sides, each its own
 *     higher value;</li>
 * <li>the block stays a conflict when a side lowers a version below the base's (a deliberate downgrade), when both
 *     sides changed a place to versions that compare equal but are written differently ({@code 1.0.0.qualifier}
 *     against {@code 1.0.0.v20260305}, {@code 1.0} against {@code 1.0.0}, ranges with the same lower bound), or when
 *     a version may be wrapped onto a continuation line;</li>
 * <li>a block no place of which both sides changed is left to the other resolvers: their merge needs no version
 *     rule.</li>
 * </ul>
 * Not exclusive: a block it declines goes on to the next resolvers. It runs before the file-format resolvers, since
 * those leave a value both sides changed a conflict (MANIFEST.MF's Bundle-Version, a clause's bundle-version range,
 * a .properties value).
 */
public final class NewerVersionResolver implements ConflictResolver {
  public static final String ID = "newer-version";

  /** Rationale of a block this resolver resolved. */
  public static final String RATIONALE = "newer-version";

  private static final String VERSION_MARK = "\u0000V";
  private static final String RANGE_MARK = "\u0000R";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Strategy strategy() {
    return Strategy.SEMANTIC;
  }

  /** Descriptor files named exactly so. */
  private static final java.util.Set<String> NAMES = java.util.Set.of("pom.xml", "MANIFEST.MF", "SOURCE-MANIFEST.MF",
      "feature.xml", "category.xml", "libs.versions.toml", "package.json", "gradle.properties");

  /** Descriptor files by their extension. */
  private static final List<String> SUFFIXES = List.of(".pom", ".target", ".product", ".gradle", ".gradle.kts");

  /**
   * Only in descriptor files: pom.xml and *.pom, MANIFEST.MF, SOURCE-MANIFEST.MF, feature.xml, category.xml, *.target,
   * *.product, *.gradle, *.gradle.kts, libs.versions.toml, package.json and gradle.properties (there only the
   * properties whose key contains "version", see {@link #tryResolve(BlockContext)}); every other file is left alone. A path counts by its last segment ('/' or
   * '\\').
   */
  @Override
  public boolean appliesTo(String fileName) {
    String name = lastSegment(fileName);
    return name != null && (NAMES.contains(name) || SUFFIXES.stream().anyMatch(name::endsWith));
  }

  private static String lastSegment(String path) {
    return path == null ? null : path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
  }

  /** Every line that is not the same on all three sides is a property whose key contains "version". */
  private static boolean onlyVersionKeysDiffer(String left, String base, String right) {
    String[] o = left.split("\n", -1);
    String[] b = base.split("\n", -1);
    String[] t = right.split("\n", -1);
    if (o.length != b.length || t.length != b.length) {
      return false;
    }
    for (int i = 0; i < b.length; i++) {
      if (!(o[i].equals(b[i]) && t[i].equals(b[i])) && !versionKey(b[i])) {
        return false;
      }
    }
    return true;
  }

  private static boolean versionKey(String line) {
    line = line.stripLeading();
    int end = 0;
    while (end < line.length() && "=: \t".indexOf(line.charAt(end)) < 0) {
      end++;
    }
    return line.substring(0, end).toLowerCase(java.util.Locale.ROOT).contains("version");
  }

  /**
   * In gradle.properties only the lines whose key contains "version" (in any case: {@code version},
   * {@code kotlinVersion}, {@code guava.version}) are versions; a block that changes any other line is left alone.
   */
  @Override
  public Optional<Resolution> tryResolve(BlockContext ctx) {
    if ("gradle.properties".equals(lastSegment(ctx.fileName()))
        && !onlyVersionKeysDiffer(ctx.left(), ctx.base(), ctx.right())) {
      return Optional.empty();
    }
    return tryResolve(ctx.left(), ctx.base(), ctx.right()).map(text -> new Resolution(text, false, RATIONALE));
  }

  @Override
  public Optional<String> tryResolve(String left, String base, String right) {
    if (left.equals(right)) {
      return Optional.empty();
    }
    Places o = places(left);
    Places b = places(base);
    Places t = places(right);
    if (o == null || b == null || t == null || !o.skeleton().equals(b.skeleton())
        || !o.skeleton().equals(t.skeleton())) {
      return Optional.empty();
    }
    List<String> values = new ArrayList<>();
    boolean bothChanged = false;
    for (int i = 0; i < b.values().size(); i++) {
      String oi = o.values().get(i);
      String bi = b.values().get(i);
      String ti = t.values().get(i);
      if (lowered(oi, bi) || lowered(ti, bi)) {
        return Optional.empty();
      }
      if (oi.equals(ti) || ti.equals(bi)) {
        values.add(oi);
      } else if (oi.equals(bi)) {
        values.add(ti);
      } else {
        int c = VersionOrder.compare(oi, ti);
        if (c == 0) {
          return Optional.empty();
        }
        values.add(c > 0 ? oi : ti);
        bothChanged = true;
      }
    }
    if (!bothChanged) {
      return Optional.empty();
    }
    return Optional.of(o.fill(left, values));
  }

  /** {@code side} changed {@code base} to a lower version. */
  private static boolean lowered(String side, String base) {
    return !side.equals(base) && VersionOrder.compare(side, base) < 0;
  }

  /**
   * The versions and ranges of a text, in order, with where they stand; {@code skeleton} is the text with each
   * replaced by a mark of its kind.
   */
  private record Places(String skeleton, List<String> values, List<int[]> spans) {
    String fill(String text, List<String> with) {
      StringBuilder sb = new StringBuilder();
      int at = 0;
      for (int i = 0; i < spans.size(); i++) {
        sb.append(text, at, spans.get(i)[0]).append(with.get(i));
        at = spans.get(i)[1];
      }
      return sb.append(text, at, text.length()).toString();
    }
  }

  /** The places of {@code text}, or null when a version may be wrapped onto a continuation line. */
  private static Places places(String text) {
    List<WordTokenizer.Token> tokens = WordTokenizer.compounds(text);
    StringBuilder skeleton = new StringBuilder();
    List<String> values = new ArrayList<>();
    List<int[]> spans = new ArrayList<>();
    int at = 0;
    for (int i = 0; i < tokens.size(); i++) {
      WordTokenizer.Token tok = tokens.get(i);
      int end = rangeEnd(tokens, i);
      String mark;
      int start = tok.start();
      int stop;
      if (end >= 0) {
        stop = tokens.get(end).end();
        mark = RANGE_MARK;
        i = end;
      } else if (VersionOrder.isVersion(tok.text())) {
        stop = tok.end();
        mark = VERSION_MARK;
      } else {
        continue;
      }
      if (wrapped(text, start, stop)) {
        return null;
      }
      skeleton.append(text, at, start).append(mark);
      values.add(text.substring(start, stop));
      spans.add(new int[] {start, stop});
      at = stop;
    }
    skeleton.append(text, at, text.length());
    return new Places(skeleton.toString(), values, spans);
  }

  /**
   * The index of the closing bracket of a range starting at token {@code i} ('[' or '(', a version, ',', an optional
   * version, ']' or ')'; line breaks between them are allowed), or -1.
   */
  private static int rangeEnd(List<WordTokenizer.Token> tokens, int i) {
    String open = tokens.get(i).text();
    if (!open.equals("[") && !open.equals("(")) {
      return -1;
    }
    int j = next(tokens, i);
    if (j < 0 || !VersionOrder.isVersion(tokens.get(j).text())) {
      return -1;
    }
    j = next(tokens, j);
    if (j < 0 || !tokens.get(j).text().equals(",")) {
      return -1;
    }
    j = next(tokens, j);
    if (j >= 0 && VersionOrder.isVersion(tokens.get(j).text())) {
      j = next(tokens, j);
    }
    if (j < 0 || !(tokens.get(j).text().equals("]") || tokens.get(j).text().equals(")"))) {
      return -1;
    }
    return j;
  }

  /** The index of the token after {@code i}, line breaks skipped, or -1. */
  private static int next(List<WordTokenizer.Token> tokens, int i) {
    int j = i + 1;
    while (j < tokens.size() && tokens.get(j).text().equals("\n")) {
      j++;
    }
    return j < tokens.size() ? j : -1;
  }

  /**
   * Whether the place {@code [start, stop)} may be part of a longer word wrapped across lines, as a MANIFEST.MF
   * continuation line (a line break and a space) can split one: a word character, '.', '-' or ':' on the other side
   * of such a break right before or after it.
   */
  private static boolean wrapped(String text, int start, int stop) {
    int after = stop;
    if (after < text.length() && text.charAt(after) == '\r') {
      after++;
    }
    if (after + 2 <= text.length() && text.charAt(after) == '\n' && text.charAt(after + 1) == ' '
        && after + 2 < text.length() && joins(text.charAt(after + 2))) {
      return true;
    }
    int before = start;
    if (before >= 2 && text.charAt(before - 1) == ' ' && text.charAt(before - 2) == '\n') {
      int k = before - 3;
      if (k >= 0 && text.charAt(k) == '\r') {
        k--;
      }
      return k >= 0 && joins(text.charAt(k));
    }
    return false;
  }

  private static boolean joins(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == ':';
  }
}
