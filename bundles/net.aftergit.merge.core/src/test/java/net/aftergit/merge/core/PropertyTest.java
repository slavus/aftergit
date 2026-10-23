package net.aftergit.merge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.Random;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.LinesResultDocument;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.GreedyResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.resolve.SimpleResolver;
import org.junit.jupiter.api.Test;

class PropertyTest {
  private static final String[] WORDS = {"a", "b", "c", "d", "e"};

  /** Random line-based text derived from base by random edits. */
  static String mutate(Random rnd, String base) {
    String[] lines = base.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length - 1; i++) {
      int op = rnd.nextInt(10);
      if (op == 0) {
        continue; // delete
      }
      if (op == 1) {
        sb.append(words(rnd)).append('\n'); // insert before
      }
      sb.append(op == 2 ? words(rnd) : lines[i]).append('\n'); // modify or keep
    }
    return sb.toString();
  }

  static String words(Random rnd) {
    int n = 1 + rnd.nextInt(4);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(WORDS[rnd.nextInt(WORDS.length)]);
    }
    return sb.toString();
  }

  static String randomBase(Random rnd) {
    StringBuilder sb = new StringBuilder();
    int n = 3 + rnd.nextInt(8);
    for (int i = 0; i < n; i++) {
      sb.append(words(rnd)).append('\n');
    }
    return sb.toString();
  }

  static String text(MergeModel m) {
    return ((LinesResultDocument) m.document()).text();
  }

  @Test
  void resolversAreSymmetric() {
    Random rnd = new Random(42);
    for (int i = 0; i < 2000; i++) {
      String base = words(rnd) + " " + words(rnd);
      String left = mutateWords(rnd, base);
      String right = mutateWords(rnd, base);
      assertEquals(SimpleResolver.tryResolve(left, base, right), SimpleResolver.tryResolve(right, base, left),
          "simple " + base + " | " + left + " | " + right);
      Optional<String> g1 = GreedyResolver.tryResolve(left, base, right);
      Optional<String> g2 = GreedyResolver.tryResolve(right, base, left);
      assertEquals(g1.isPresent(), g2.isPresent(), "greedy presence " + base + " | " + left + " | " + right);
    }
  }

  static String mutateWords(Random rnd, String base) {
    String[] w = base.split(" ");
    StringBuilder sb = new StringBuilder();
    for (String word : w) {
      int op = rnd.nextInt(6);
      if (op == 0) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(' ');
      }
      if (op == 1) {
        sb.append(WORDS[rnd.nextInt(WORDS.length)]).append(' ');
      }
      sb.append(op == 2 ? WORDS[rnd.nextInt(WORDS.length)] : word);
    }
    return sb.toString();
  }

  @Test
  void initializationIsSymmetric() {
    Random rnd = new Random(7);
    for (int i = 0; i < 500; i++) {
      String base = randomBase(rnd);
      String left = mutate(rnd, base);
      String right = mutate(rnd, base);
      InitResult a = HybridInitializer.initialize(new MergeInput(base, left, right, null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
      InitResult b = HybridInitializer.initialize(new MergeInput(base, right, left, null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
      // symmetric modulo whitespace: the whitespace-insensitive resolver pass keeps the left side's layout
      assertTrue(ComparisonPolicy.IGNORE_WHITESPACES.equalTexts(text(a.model()), text(b.model())),
          base + "|" + left + "|" + right);
      assertEquals(a.model().unresolvedConflicts(), b.model().unresolvedConflicts());
    }
  }

  @Test
  void pipelineIsIdempotent() {
    Random rnd = new Random(11);
    int exercised = 0;
    for (int i = 0; i < 500; i++) {
      String base = randomBase(rnd);
      InitResult r = HybridInitializer.initialize(new MergeInput(base, mutate(rnd, base), mutate(rnd, base), null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(true));
      if (r.autoReport().resolved() > 0) {
        exercised++;
      }
      String before = text(r.model());
      assertEquals(0, ResolverPipeline.defaults(true).run(r.model()).resolved());
      assertEquals(before, text(r.model()));
    }
    assertTrue(exercised >= 20, "only " + exercised + " iterations exercised");
  }

  @Test
  void editedBlocksAreNeverTouched() {
    Random rnd = new Random(13);
    int exercised = 0;
    int emptyExercised = 0;
    for (int i = 0; i < 500; i++) {
      String base = randomBase(rnd);
      String left = mutate(rnd, base);
      String right = mutate(rnd, base);
      if (editFirstConflict(base, left, right, false)) {
        exercised++;
      }
      if (editFirstConflict(base, left, right, true)) {
        emptyExercised++;
      }
    }
    assertTrue(exercised >= 50, "only " + exercised + " iterations exercised");
    assertTrue(emptyExercised >= 10, "only " + emptyExercised + " iterations exercised empty blocks");
  }

  /**
   * Types into the first non-empty (replacing its first line) or empty (inserting a line at its
   * position) conflict block, runs the resolvers and checks the block kept the manual text.
   */
  private static boolean editFirstConflict(String base, String left, String right, boolean empty) {
    InitResult r = HybridInitializer.initialize(new MergeInput(base, left, right, null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    MergeModel m = r.model();
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && (b.resultEnd() == b.resultStart()) == empty) {
        LinesResultDocument d = (LinesResultDocument) m.document();
        int end = empty ? b.resultStart() : b.resultStart() + 1;
        d.replaceLines(b.resultStart(), end, "MANUAL\n");
        m.onExternalEdit(b.resultStart(), end, 1);
        String manual = m.resultText(b.index());
        assertEquals("MANUAL\n", empty ? manual : manual.substring(0, "MANUAL\n".length()));
        ResolverPipeline.defaults(true).run(m);
        assertEquals(BlockState.EDITED, b.state());
        assertEquals(manual, m.resultText(b.index()));
        return true;
      }
    }
    return false;
  }

  @Test
  void swappingSidesKeepsLineBreaks() {
    // The whitespace-insensitive resolver pass must not drop a side's line breaks, so swapping
    // the sides gives the same text.
    String base = "a c b\na b\nb c d b\ne\ne a c\nc b c b\na\n";
    String x = "a c b\na b\nd a b a\ne a c\nc b c b\na\n";
    String y = "a b\nb c d b\na\ne\nc b c b\na\n";
    InitResult a = HybridInitializer.initialize(new MergeInput(base, x, y, null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    InitResult b = HybridInitializer.initialize(new MergeInput(base, y, x, null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertEquals(text(a.model()), text(b.model()));
    assertEquals(a.model().unresolvedConflicts(), b.model().unresolvedConflicts());
  }
}
