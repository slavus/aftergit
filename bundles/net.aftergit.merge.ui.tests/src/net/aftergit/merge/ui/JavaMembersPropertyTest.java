package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import java.util.Optional;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ConflictResolver;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.jdt.JavaMembersResolver;
import org.junit.jupiter.api.Test;

/**
 * Resolvers 6c design, testing (seeded): with the JDT parser, a change made by one side only is never lost, and a
 * member both sides changed differently is never resolved.
 */
class JavaMembersPropertyTest {
  static final long SEED = 6307;
  static final int CASES = 150;
  static final Integer CONFLICT = Integer.MIN_VALUE;
  static final ResolverPipeline PLAIN = new ResolverPipeline(List.of()).forFile("M.java");
  static final ResolverPipeline PIPELINE = new ResolverPipeline(List.of(new JavaMembersResolver())).forFile("M.java");
  static final Pattern METHOD = Pattern.compile("(?m)^  int (\\w+)\\(\\) \\{\\n    return (\\d+);\\n  \\}$");

  static final int METHOD_LINES = 3;

  record Method(String name, int value) {}

  /** A method's value and the line its declaration starts on; it spans {@link #METHOD_LINES} lines. */
  record Found(int value, int line) {}

  static String render(List<Method> methods) {
    StringBuilder sb = new StringBuilder("package p;\n\npublic class M {\n");
    for (Method m : methods) {
      sb.append("\n  int ").append(m.name()).append("() {\n    return ").append(m.value()).append(";\n  }\n");
    }
    return sb.append("}\n").toString();
  }

  /** Every copy of every method in {@code text}. */
  static Map<String, List<Found>> read(String text) {
    Map<String, List<Found>> out = new LinkedHashMap<>();
    Matcher mt = METHOD.matcher(text);
    while (mt.find()) {
      int line = (int) text.substring(0, mt.start()).chars().filter(c -> c == '\n').count();
      out.computeIfAbsent(mt.group(1), k -> new ArrayList<>()).add(new Found(Integer.parseInt(mt.group(2)), line));
    }
    return out;
  }

  static Map<String, Integer> values(List<Method> methods) {
    Map<String, Integer> out = new LinkedHashMap<>();
    methods.forEach(m -> out.put(m.name(), m.value()));
    return out;
  }

  /** One side: each base method kept, changed or deleted; new methods (and {@code shared}) at random places. */
  static List<Method> side(Random rnd, List<Method> base, String prefix, List<Method> shared) {
    List<Method> out = new ArrayList<>();
    for (Method m : base) {
      int op = rnd.nextInt(10);
      if (op == 0) {
        continue;
      }
      out.add(op <= 2 ? new Method(m.name(), m.value() + 1 + rnd.nextInt(5)) : m);
    }
    int adds = rnd.nextInt(3);
    for (int k = 0; k < adds; k++) {
      out.add(rnd.nextInt(out.size() + 1), new Method(prefix + k, rnd.nextInt(100)));
    }
    for (Method s : shared) {
      out.add(rnd.nextInt(out.size() + 1), s);
    }
    return out;
  }

  /** What the key must end as: its value, null (gone) or CONFLICT. */
  static Integer expected(Integer b, Integer o, Integer t) {
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

  /** Whether any line of the method that starts on {@code line} lies in a block still in conflict. */
  static boolean inOpenBlock(MergeModel m, int line) {
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED && line < b.resultEnd() && line + METHOD_LINES > b.resultStart()) {
        return true;
      }
    }
    return false;
  }

  /** Whether the open block {@code blk} of {@code plain} holds a copy of a conflict key or has it in one of its sides. */
  static boolean holdsAny(MergeModel plain, MergeBlock blk, Map<String, List<Found>> copies, Set<String> keys) {
    for (String k : keys) {
      boolean overlaps = copies.getOrDefault(k, List.of()).stream()
          .anyMatch(f -> f.line() < blk.resultEnd() && f.line() + METHOD_LINES > blk.resultStart());
      boolean inSide = false;
      for (ThreeSide s : ThreeSide.values()) {
        inSide |= plain.sideText(blk.index(), s).contains("int " + k + "(");
      }
      if (overlaps || inSide) {
        return true;
      }
    }
    return false;
  }

  /** The conflicting blocks of the line merge that hold a double-changed member, matched by their ranges. */
  static void assertDoubleChangesStayOpen(MergeModel m, MergeModel plain, Map<String, List<Found>> plainCopies,
      Set<String> conflictKeys, String where) {
    for (MergeBlock p : plain.blocks()) {
      if (!p.isConflict() || p.state() != BlockState.UNRESOLVED || !holdsAny(plain, p, plainCopies, conflictKeys)) {
        continue;
      }
      assertTrue(m.blocks().stream().anyMatch(x -> x.source().equals(p.source()) && x.isConflict()
          && x.state() == BlockState.UNRESOLVED), "a block with a member both sides changed differently was closed: "
          + where);
    }
  }

  /** The ranges of the conflicting blocks still open. */
  static Set<MergeRange> openRanges(MergeModel m) {
    Set<MergeRange> out = new LinkedHashSet<>();
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        out.add(b.source());
      }
    }
    return out;
  }

  /** A resolver with a bug: marks every body block resolved but keeps its base text. */
  static final class KeepsBase implements ConflictResolver {
    @Override
    public String id() {
      return "keeps-base";
    }

    @Override
    public Strategy strategy() {
      return Strategy.SEMANTIC;
    }

    @Override
    public boolean isApplicable(net.aftergit.merge.core.resolve.BlockContext ctx) {
      return ctx.block().region() == Region.BODY;
    }

    @Override
    public Optional<String> tryResolve(String left, String base, String right) {
      return Optional.of(base);
    }
  }

  @Test
  void aOneSidedChangeIsNeverLostAndADoubleChangeIsNeverResolved() {
    int[] counts = run(PIPELINE);
    assertTrue(counts[0] > 10, "cases with a double change: " + counts[0]);
    assertTrue(counts[1] > 10, "cases the resolver resolved fully: " + counts[1]);
    assertTrue(counts[2] > 10, "cases the resolver changed: " + counts[2]);
  }

  @Test
  void aResolverThatClosesBlocksWithTheirBaseTextIsCaught() {
    assertThrows(AssertionError.class,
        () -> run(new ResolverPipeline(List.of(new KeepsBase())).forFile("M.java")));
  }

  /** Runs the cases; returns the coverage counters: double changes, fully resolved, changed by the resolver. */
  static int[] run(ResolverPipeline pipeline) {
    Random rnd = new Random(SEED);
    int withConflict = 0;
    int resolvedFully = 0;
    int changedByResolver = 0;
    for (int c = 0; c < CASES; c++) {
      List<Method> base = new ArrayList<>();
      int n = 1 + rnd.nextInt(5);
      for (int i = 0; i < n; i++) {
        base.add(new Method("m" + i, rnd.nextInt(10)));
      }
      List<Method> sharedOurs = new ArrayList<>();
      List<Method> sharedTheirs = new ArrayList<>();
      int kind = rnd.nextInt(4);
      if (kind < 2) {
        sharedOurs.add(new Method("s", 7));
        sharedTheirs.add(new Method("s", kind == 0 ? 7 : 8));
      }
      List<Method> ours = side(rnd, base, "o", sharedOurs);
      List<Method> theirs = side(rnd, base, "t", sharedTheirs);
      MergeInput input = new MergeInput(render(base), render(ours), render(theirs), null);
      MergeModel m = HybridInitializer.initialize(input, ComparisonPolicy.DEFAULT, pipeline).model();
      // The plain line merge, without the resolver, is the oracle for what is legitimately clean.
      MergeModel plain = HybridInitializer.initialize(input, ComparisonPolicy.DEFAULT, PLAIN).model();
      String text = m.document().chunk(0, m.document().lineCount());
      String plainText = plain.document().chunk(0, plain.document().lineCount());
      String where = "case " + c + " (seed " + SEED + ")\n" + text + "\nplain line merge:\n" + plainText;
      Map<String, List<Found>> got = read(text);
      Map<String, List<Found>> plainGot = read(plainText);
      Map<String, Integer> b = values(base);
      Map<String, Integer> o = values(ours);
      Map<String, Integer> t = values(theirs);
      Set<String> keys = new LinkedHashSet<>(b.keySet());
      keys.addAll(o.keySet());
      keys.addAll(t.keySet());
      Set<String> conflictKeys = new LinkedHashSet<>();
      for (String k : keys) {
        if (CONFLICT.equals(expected(b.get(k), o.get(k), t.get(k)))) {
          conflictKeys.add(k);
        }
      }
      if (!conflictKeys.isEmpty()) {
        withConflict++;
      }
      if (m.unresolvedConflicts() == 0 && plain.unresolvedConflicts() > 0) {
        resolvedFully++;
      }
      if (!text.equals(plainText)) {
        changedByResolver++;
      }
      assertDoubleChangesStayOpen(m, plain, plainGot, conflictKeys, where);
      boolean closedNothing = openRanges(m).equals(openRanges(plain));
      for (String k : keys) {
        Integer e = expected(b.get(k), o.get(k), t.get(k));
        List<Found> copies = got.getOrDefault(k, List.of());
        if (CONFLICT.equals(e)) {
          // Outside every open block only a clean line-merge outcome is legitimate: the same value, also outside the
          // open blocks of the line merge.
          for (Found g : copies) {
            if (!inOpenBlock(m, g.line())) {
              assertTrue(plainGot.getOrDefault(k, List.of()).stream()
                  .anyMatch(p -> p.value() == g.value() && !inOpenBlock(plain, p.line())),
                  k + " is a conflict but the resolver took it out of the open blocks: " + where);
            }
          }
        } else if (closedNothing && text.equals(plainText)) {
          continue; // the resolver closed no block: what is left is the line merge's own outcome, not its doing
        } else if (m.unresolvedConflicts() == 0) {
          assertEquals(e == null, copies.isEmpty(), k + ": " + where);
          copies.forEach(g -> assertEquals(e, (Integer) g.value(), k + ": " + where));
        } else {
          for (Found g : copies) {
            if (!inOpenBlock(m, g.line())) {
              assertEquals(e, (Integer) g.value(), k + " outside the open blocks: " + where);
            }
          }
        }
      }
    }
    return new int[] {withConflict, resolvedFully, changedByResolver};
  }
}
