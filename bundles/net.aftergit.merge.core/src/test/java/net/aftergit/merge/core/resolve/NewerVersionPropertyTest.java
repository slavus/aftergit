package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import org.junit.jupiter.api.Test;

/**
 * "Newer version wins" (seeded): a resolved block has, at each version place, exactly one side's version (never a
 * made-up one), the higher of the two, never one below the base; any other difference leaves the block a conflict.
 */
class NewerVersionPropertyTest {
  static final int CASES = 2_000;
  static final String[] QUALIFIERS = {"", "", "", "-SNAPSHOT", ".qualifier", "-rc1", "-rc2", ".v20260305-0817",
      ".v20260401-1200", ".Final", "-beta1"};
  static final String[] LINES = {"  <version>%s</version>\n", "<plugin id=\"p\" version=\"%s\"/>\n",
      "Bundle-Version: %s\n", "Require-Bundle: org.b;bundle-version=\"%s\"\n", "x = %s\n"};

  static String version(Random rnd) {
    int n = 2 + rnd.nextInt(3);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(i == 0 ? "" : ".").append(rnd.nextInt(4));
    }
    return sb + QUALIFIERS[rnd.nextInt(QUALIFIERS.length)];
  }

  static String value(Random rnd, boolean range) {
    return range ? "[" + version(rnd) + "," + version(rnd) + (rnd.nextBoolean() ? ")" : "]") : version(rnd);
  }

  /** A side's value at a place: the base's, a raised one or any other (which may be lower). */
  static String side(Random rnd, String base, boolean range) {
    return switch (rnd.nextInt(3)) {
      case 0 -> base;
      case 1 -> raised(rnd, base);
      default -> value(rnd, range);
    };
  }

  /** {@code v} with one of its first two numbers raised (for a range: of its lower bound). */
  static String raised(Random rnd, String v) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(v);
    int skip = rnd.nextInt(2);
    for (int i = 0; i <= skip; i++) {
      m.find();
    }
    return v.substring(0, m.start()) + (Integer.parseInt(m.group()) + 1 + rnd.nextInt(2)) + v.substring(m.end());
  }

  static String fill(List<String> lines, List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      sb.append(lines.get(i).formatted(values.get(i)));
    }
    return sb.toString();
  }

  /** The rule, place by place: null when the block stays a conflict. */
  static List<String> oracle(List<String> o, List<String> b, List<String> t) {
    List<String> out = new ArrayList<>();
    boolean both = false;
    for (int i = 0; i < b.size(); i++) {
      String oi = o.get(i), bi = b.get(i), ti = t.get(i);
      if ((!oi.equals(bi) && VersionOrder.compare(oi, bi) < 0) || (!ti.equals(bi) && VersionOrder.compare(ti, bi) < 0)) {
        return null;
      }
      if (oi.equals(ti) || ti.equals(bi)) {
        out.add(oi);
      } else if (oi.equals(bi)) {
        out.add(ti);
      } else {
        both = true;
        int c = VersionOrder.compare(oi, ti);
        if (c == 0) {
          return null;
        }
        out.add(c > 0 ? oi : ti);
      }
    }
    return both ? out : null;
  }

  @Test
  void resultVersionsAreTheHigherSidesOwn() {
    Random rnd = new Random(20261016);
    NewerVersionResolver r = new NewerVersionResolver();
    int resolved = 0;
    for (int n = 0; n < CASES; n++) {
      int places = 1 + rnd.nextInt(4);
      List<String> lines = new ArrayList<>();
      List<Boolean> ranges = new ArrayList<>();
      List<String> b = new ArrayList<>();
      List<String> o = new ArrayList<>();
      List<String> t = new ArrayList<>();
      for (int i = 0; i < places; i++) {
        lines.add(LINES[rnd.nextInt(LINES.length)]);
        boolean range = rnd.nextInt(4) == 0;
        ranges.add(range);
        String base = value(rnd, range);
        b.add(base);
        o.add(side(rnd, base, range));
        t.add(side(rnd, base, range));
      }
      String base = fill(lines, b);
      String ours = fill(lines, o);
      String theirs = fill(lines, t);
      boolean textChange = rnd.nextInt(8) == 0;
      if (textChange) {
        ours = ours.replaceFirst("\n", " // changed\n");
      }
      if (ours.equals(theirs)) {
        continue;
      }
      Optional<String> got = r.tryResolve(ours, base, theirs);
      String why = "ours " + ours + " base " + base + " theirs " + theirs;
      if (textChange) {
        assertEquals(Optional.empty(), got, why);
        continue;
      }
      List<String> expected = oracle(o, b, t);
      assertEquals(Optional.ofNullable(expected).map(e -> fill(lines, e)), got, why);
      if (expected != null) {
        resolved++;
        for (int i = 0; i < places; i++) {
          String v = expected.get(i);
          assertTrue(v.equals(o.get(i)) || v.equals(t.get(i)), why);
          assertTrue(VersionOrder.compare(v, b.get(i)) >= 0, why);
        }
        assertNoMadeUpCompound(base, ours, theirs);
      }
    }
    assertTrue(resolved > CASES / 10, "resolved " + resolved);
  }

  /** Through the pipeline, as in the plugin: every auto-resolved block has only compounds of its sides. */
  private static void assertNoMadeUpCompound(String base, String ours, String theirs) {
    String head = "head\n\n";
    String tail = "\n\ntail\n";
    MergeModel m = HybridInitializer.initialize(new MergeInput(head + base + tail, head + ours + tail,
        head + theirs + tail, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false, id -> true, List.of()).forFile("pom.xml")).model();
    for (MergeBlock blk : m.blocks()) {
      if (!blk.isConflict() || blk.state() != BlockState.AUTO_RESOLVED) {
        continue;
      }
      Set<String> known = new HashSet<>();
      for (ThreeSide s : ThreeSide.values()) {
        known.addAll(WordTokenizer.texts(WordTokenizer.compounds(m.sideText(blk.index(), s))));
      }
      for (String c : WordTokenizer.texts(WordTokenizer.compounds(m.resultText(blk.index())))) {
        assertTrue(known.contains(c), "made-up " + c + " in " + m.resultText(blk.index()));
      }
    }
  }
}
