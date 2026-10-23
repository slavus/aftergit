package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import org.junit.jupiter.api.Test;

/**
 * Seeded random 3-way inputs over a tiny alphabet of words, punctuation, spaces, tabs and line
 * breaks. Both word resolvers, under both policies, must either give up or produce a result that
 * {@link MergeOracle} accepts.
 */
class WordResolverFuzzTest {
  private static final String[] PIECES = {"a", "b", "ab", "X", "(", ")", ";", " ", " ", "\t", "  ", "\n"};
  private static final int CASES = 4000;

  /** Digits, letters and joiners, so that versions and qualified names are made and edited. */
  private static final String[] DOTTED = {"6", "7", "a", "b", ".", ".", "-", ":", " ", " ", "\n"};

  private static String[] alphabet = PIECES;

  private static String piece(Random rnd) {
    return alphabet[rnd.nextInt(alphabet.length)];
  }

  private static List<String> randomBase(Random rnd) {
    List<String> base = new ArrayList<>();
    int n = 2 + rnd.nextInt(8);
    for (int i = 0; i < n; i++) {
      base.add(piece(rnd));
    }
    return base;
  }

  private static String mutate(Random rnd, List<String> base) {
    StringBuilder sb = new StringBuilder();
    if (rnd.nextInt(10) == 0) {
      sb.append(piece(rnd));
    }
    for (String p : base) {
      int op = rnd.nextInt(10);
      if (op == 0) {
        continue;
      }
      sb.append(op == 1 ? piece(rnd) : p);
      if (op == 2) {
        sb.append(piece(rnd));
      }
    }
    return sb.toString();
  }

  @Test
  void resultsNeverGlueOrLoseOneSidedEdits() {
    alphabet = PIECES;
    run(new Random(20261014L));
  }

  /** No automatic word-level resolution contains a compound token that none of the inputs has. */
  @Test
  void resultsNeverMakeUpACompoundToken() {
    alphabet = DOTTED;
    run(new Random(20261016L));
  }

  private static void run(Random rnd) {
    int[] resolved = new int[4];
    List<String> failures = new ArrayList<>();
    for (int i = 0; i < CASES; i++) {
      List<String> pieces = randomBase(rnd);
      String base = String.join("", pieces);
      String left = mutate(rnd, pieces);
      String right = mutate(rnd, pieces);
      int k = 0;
      for (ComparisonPolicy policy : List.of(ComparisonPolicy.DEFAULT, ComparisonPolicy.IGNORE_WHITESPACES)) {
        String simple = SimpleResolver.resolve(left, base, right, policy);
        String greedy = GreedyResolver.resolve(left, base, right, policy);
        check(failures, "simple " + policy, base, left, right, simple);
        check(failures, "greedy " + policy, base, left, right, greedy);
        resolved[k++] += simple != null ? 1 : 0;
        resolved[k++] += greedy != null ? 1 : 0;
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " bad merges, first ones:\n" + String.join("\n", failures.subList(0, Math.min(15, failures.size()))));
    }
    // the generator must keep exercising real merges, not only give-ups
    for (int n : resolved) {
      assertTrue(n > CASES / 4, "only " + n + " of " + CASES + " cases resolved");
    }
  }

  private static void check(List<String> failures, String who, String base, String left, String right, String result) {
    String why = MergeOracle.violation(left, base, right, result);
    if (why != null) {
      failures.add(who + ": base=" + q(base) + " left=" + q(left) + " right=" + q(right)
          + " -> " + q(result) + ": " + why);
    }
  }

  static String q(String s) {
    return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
  }
}
