package net.aftergit.merge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Random;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.markers.CleanParity;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MarkerStyle;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.ResultWriter;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec §10.2: the non-conflicting part of the result equals git merge-file's for the same inputs. */
class GitMergeFilePropertyTest {
  @TempDir
  Path tmp;

  @Test
  void cleanPartsEqualGitMergeFile() throws Exception {
    assumeTrue(GitParityTest.gitMergeFile(tmp, "a\n", "a\n", "a\n") != null, "git not available");
    Random rnd = new Random(Long.getLong("aftergit.property.seed", 17));
    int runs = 1000;
    int conflicted = 0;
    int fallbacks = 0;
    for (int i = 0; i < runs; i++) {
      String base = PropertyTest.randomBase(rnd);
      String ours = PropertyTest.mutate(rnd, base);
      String theirs = PropertyTest.mutate(rnd, base);
      String git = GitParityTest.gitMergeFile(tmp, base, ours, theirs);
      String inputs = base + "|" + ours + "|" + theirs;
      // the engine on its own: git's clean text, hunk boundaries aside
      CleanParity.Result raw = CleanParity.compare(
          GitParityTest.ourMerge(new GitParityTest.Scenario("random", base, ours, theirs)), git);
      assertEquals(0, raw.deviations(), raw.first() + " for " + inputs);
      // aligned with git's result and auto-resolved, as the resolver opens it: still git's clean text
      InitResult r = HybridInitializer.initialize(new MergeInput(base, ours, theirs, git), ComparisonPolicy.DEFAULT,
          ResolverPipeline.defaults(false));
      String result = ResultWriter.render(r.model(), MarkerStyle.MERGE, "ours", "theirs", 7, "\n",
          LineText.of(git).trailingNewline());
      CleanParity.Result aligned = CleanParity.compare(result, git);
      assertEquals(0, aligned.deviations(), aligned.first() + " for " + inputs);
      if (git.contains("<<<<<<<")) {
        conflicted++;
      }
      if (r.usedFallback()) {
        fallbacks++;
      }
    }
    assertTrue(conflicted >= 300, "only " + conflicted + " of " + runs + " runs had conflicts");
    assertTrue(fallbacks <= runs / 50, fallbacks + " of " + runs + " runs fell back to git's markers");
  }
}
