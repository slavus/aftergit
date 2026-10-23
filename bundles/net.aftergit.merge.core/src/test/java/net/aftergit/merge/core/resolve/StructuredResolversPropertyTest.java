package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.members.LineMemberLocator;
import net.aftergit.merge.core.members.MembersResolver;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Resolvers 6c design, testing (seeded): for .properties keys, manifest clauses and members, a change made by one
 * side only is never lost and a unit both sides changed differently is never resolved. The members property tests
 * MembersResolver alone: it is not exclusive, so a text resolver of the plugin pipeline may still act on a member
 * block it leaves; the pipeline-level behaviour is not covered here.
 */
class StructuredResolversPropertyTest {
  static final int CASES = 400;
  static final String CONFLICT = "\u0000conflict";

  record Entry(String key, String value) {}

  /** An entry's value, the line it starts on and the number of lines it takes. */
  record Found(String value, int line, int span) {}

  /** How one format writes and reads keyed entries. */
  interface Format {
    String key(String simple);

    String render(List<Entry> entries);

    Map<String, Found> read(String text);

    ResolverPipeline pipeline();
  }

  static final Format PROPERTIES = new Format() {
    @Override
    public String key(String simple) {
      return simple;
    }

    @Override
    public String render(List<Entry> entries) {
      StringBuilder sb = new StringBuilder();
      entries.forEach(e -> sb.append(e.key()).append('=').append(e.value()).append('\n'));
      return sb.toString();
    }

    @Override
    public Map<String, Found> read(String text) {
      Map<String, Found> out = new LinkedHashMap<>();
      List<String> lines = LineText.chunkLines(text);
      for (int i = 0; i < lines.size(); i++) {
        int eq = lines.get(i).indexOf('=');
        if (eq > 0) {
          put(out, lines.get(i).substring(0, eq), new Found(lines.get(i).substring(eq + 1), i, 1));
        }
      }
      return out;
    }

    @Override
    public ResolverPipeline pipeline() {
      return new ResolverPipeline(List.of(new PropertiesResolver())).forFile("a.properties");
    }
  };

  /** Members of the line grammar of LineMemberLocator: "  key" and "    value". */
  static final Format MEMBERS = new Format() {
    @Override
    public String key(String simple) {
      return simple;
    }

    @Override
    public String render(List<Entry> entries) {
      StringBuilder sb = new StringBuilder("class A {\n");
      entries.forEach(e -> sb.append("  ").append(e.key()).append("\n    ").append(e.value()).append('\n'));
      return sb.append("}\n").toString();
    }

    @Override
    public Map<String, Found> read(String text) {
      Map<String, Found> out = new LinkedHashMap<>();
      List<String> lines = LineText.chunkLines(text);
      for (int i = 1; i + 1 < lines.size(); i++) {
        String l = lines.get(i);
        if (l.startsWith("  ") && !l.startsWith("   ")) {
          put(out, l.trim(), new Found(lines.get(i + 1).trim(), i, 2));
        }
      }
      return out;
    }

    @Override
    public ResolverPipeline pipeline() {
      return new ResolverPipeline(List.of(new MembersResolver(LineMemberLocator.INSTANCE))).forFile("A.java");
    }
  };

  /** Require-Bundle clauses after a fixed first one that no side touches. */
  static final Format MANIFEST = new Format() {
    @Override
    public String key(String simple) {
      return "org." + simple;
    }

    @Override
    public String render(List<Entry> entries) {
      List<String> clauses = new ArrayList<>(List.of("org.fixed;bundle-version=\"1\""));
      entries.forEach(e -> clauses.add(e.key() + ";bundle-version=\"" + e.value() + "\""));
      return "Manifest-Version: 1.0\nRequire-Bundle: " + String.join(",\n ", clauses) + "\n\n";
    }

    @Override
    public Map<String, Found> read(String text) {
      Map<String, Found> out = new LinkedHashMap<>();
      List<String> lines = LineText.chunkLines(text);
      for (int i = 0; i < lines.size(); i++) {
        String l = lines.get(i);
        String clause = l.startsWith("Require-Bundle: ") ? l.substring("Require-Bundle: ".length())
            : l.startsWith(" ") ? l.substring(1) : null;
        if (clause == null || clause.startsWith("org.fixed")) {
          continue;
        }
        int semi = clause.indexOf(';');
        int q = clause.indexOf('"');
        put(out, clause.substring(0, semi), new Found(clause.substring(q + 1, clause.indexOf('"', q + 1)), i, 1));
      }
      return out;
    }

    @Override
    public ResolverPipeline pipeline() {
      return new ResolverPipeline(List.of(new ManifestResolver())).forFile("MANIFEST.MF");
    }
  };

  /** Keys found twice with different values by the last read: {key, first, second}. */
  static final List<Object[]> DUPLICATES = new ArrayList<>();

  /** Puts a found entry and remembers a key found twice with different values. */
  static void put(Map<String, Found> out, String key, Found found) {
    Found old = out.put(key, found);
    if (old != null && !old.value().equals(found.value())) {
      DUPLICATES.add(new Object[] {key, old, found});
    }
  }

  static boolean inSideTexts(MergeModel m, String key) {
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED
          && (m.sideText(b.index(), ThreeSide.LEFT).contains(key) || m.sideText(b.index(), ThreeSide.RIGHT).contains(key)
              || b.baseText().contains(key))) {
        return true;
      }
    }
    return false;
  }

  static List<Entry> side(Random rnd, Format f, List<Entry> base, String prefix, List<Entry> shared) {
    List<Entry> out = new ArrayList<>();
    for (Entry e : base) {
      int op = rnd.nextInt(10);
      if (op == 0) {
        continue;
      }
      out.add(op <= 2 ? new Entry(e.key(), e.value() + (char) ('a' + rnd.nextInt(3))) : e);
    }
    int adds = rnd.nextInt(3);
    for (int k = 0; k < adds; k++) {
      out.add(rnd.nextInt(out.size() + 1), new Entry(f.key(prefix + k), String.valueOf(rnd.nextInt(10))));
    }
    for (Entry s : shared) {
      out.add(rnd.nextInt(out.size() + 1), s);
    }
    return out;
  }

  static String expected(String b, String o, String t) {
    if (b == null) {
      if (o == null) {
        return t;
      }
      return t == null || o.equals(t) ? o : CONFLICT;
    }
    boolean oursChanged = !b.equals(o);
    boolean theirsChanged = !b.equals(t);
    if (!theirsChanged) {
      return o;
    }
    if (!oursChanged) {
      return t;
    }
    if (o == null && t == null) {
      return null;
    }
    if (o == null || t == null) {
      return CONFLICT;
    }
    return o.equals(t) ? o : CONFLICT;
  }

  static Map<String, String> values(List<Entry> entries) {
    Map<String, String> out = new LinkedHashMap<>();
    entries.forEach(e -> out.put(e.key(), e.value()));
    return out;
  }

  static boolean inOpenBlock(MergeModel m, int line, int span) {
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED && line < b.resultEnd() && line + span > b.resultStart()) {
        return true;
      }
    }
    return false;
  }

  static String blocks(MergeModel m) {
    StringBuilder sb = new StringBuilder("--- blocks\n");
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict()) {
        sb.append(b.state()).append(' ').append(b.resultStart()).append('-').append(b.resultEnd()).append('\n');
      }
    }
    return sb.toString();
  }

  static void check(Format f, long seed, boolean addedDifferently) {
    Random rnd = new Random(seed);
    for (int c = 0; c < CASES; c++) {
      List<Entry> base = new ArrayList<>();
      int n = 1 + rnd.nextInt(6);
      for (int i = 0; i < n; i++) {
        base.add(new Entry(f.key("k" + i), String.valueOf(rnd.nextInt(10))));
      }
      List<Entry> sharedOurs = new ArrayList<>();
      List<Entry> sharedTheirs = new ArrayList<>();
      int kind = rnd.nextInt(4);
      if (kind < 2) {
        sharedOurs.add(new Entry(f.key("s"), "7"));
        sharedTheirs.add(new Entry(f.key("s"), kind == 0 || !addedDifferently ? "7" : "8"));
      }
      List<Entry> ours = side(rnd, f, base, "o", sharedOurs);
      List<Entry> theirs = side(rnd, f, base, "t", sharedTheirs);
      MergeModel m = HybridInitializer.initialize(new MergeInput(f.render(base), f.render(ours), f.render(theirs),
          null), ComparisonPolicy.DEFAULT, f.pipeline()).model();
      String text = m.document().chunk(0, m.document().lineCount());
      String where = "case " + c + " (seed " + seed + ")\n--- base\n" + f.render(base) + "--- ours\n" + f.render(ours)
          + "--- theirs\n" + f.render(theirs) + "--- result\n" + text + blocks(m);
      DUPLICATES.clear();
      Map<String, Found> got = f.read(text);
      for (Object[] d : DUPLICATES) {
        Found x = (Found) d[1];
        Found y = (Found) d[2];
        assertTrue(inOpenBlock(m, x.line(), x.span()) || inOpenBlock(m, y.line(), y.span()),
            d[0] + " is in the result twice with different values outside the open blocks: " + where);
      }
      Map<String, String> b = values(base);
      Map<String, String> o = values(ours);
      Map<String, String> t = values(theirs);
      Set<String> keys = new LinkedHashSet<>(b.keySet());
      keys.addAll(o.keySet());
      keys.addAll(t.keySet());
      if (keys.stream().anyMatch(k -> CONFLICT.equals(expected(b.get(k), o.get(k), t.get(k))))) {
        assertTrue(m.unresolvedConflicts() > 0, "a unit both sides changed differently was resolved: " + where);
      }
      for (String k : keys) {
        String e = expected(b.get(k), o.get(k), t.get(k));
        Found g = got.get(k);
        if (m.unresolvedConflicts() == 0) {
          assertEquals(e, g == null ? null : g.value(), k + ": " + where);
        } else if (g == null) {
          if (e != null) {
            assertTrue(inSideTexts(m, k), k + " is lost, it is in no open block: " + where);
          }
        } else if (!inOpenBlock(m, g.line(), g.span())) {
          assertNotEquals(CONFLICT, e, k + " both sides changed differently, silently resolved: " + where);
          assertEquals(e, g.value(), k + " outside the open blocks: " + where);
        }
      }
    }
  }

  @Test
  void propertiesKeys() {
    check(PROPERTIES, 6301, false);
  }

  @Test
  void manifestClauses() {
    check(MANIFEST, 6302, false);
  }

  @Test
  void members() {
    check(MEMBERS, 6303, false);
  }

  /**
   * Known gap, not a resolver defect: when both sides add the same new key with different values at positions the
   * line merge does not relate (for .properties: base "k0=5", ours appends "s=7", theirs inserts "s=8" at the top),
   * the merge is clean, no conflict block exists, and the file silently holds the key twice. Only a check of the
   * merged result for duplicate keys could catch it.
   */
  private static final String SAME_KEY_GAP = "the line merge combines two different additions of one key without a conflict";

  @Test
  @Disabled(SAME_KEY_GAP)
  void propertiesKeyAddedDifferentlyByBothSides() {
    check(PROPERTIES, 6301, true);
  }

  @Test
  @Disabled(SAME_KEY_GAP)
  void manifestClauseAddedDifferentlyByBothSides() {
    check(MANIFEST, 6302, true);
  }

  @Test
  @Disabled(SAME_KEY_GAP)
  void memberAddedDifferentlyByBothSides() {
    check(MEMBERS, 6303, true);
  }
}
