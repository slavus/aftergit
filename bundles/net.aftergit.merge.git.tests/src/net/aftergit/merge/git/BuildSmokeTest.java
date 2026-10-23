package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {
  @Test
  void coreIsUsableInsideOsgi() {
    InitResult r = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false));
    assertEquals(1, r.model().unresolvedConflicts());
  }
}
