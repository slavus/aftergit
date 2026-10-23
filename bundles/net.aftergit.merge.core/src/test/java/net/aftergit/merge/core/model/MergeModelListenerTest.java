package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

class MergeModelListenerTest {
  @Test
  void removedListenerIsNotCalled() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    int[] calls = {0};
    Runnable l = () -> calls[0]++;
    m.addChangeListener(l);
    m.take(0, ThreeSide.LEFT);
    m.removeChangeListener(l);
    m.revert(0);
    assertEquals(1, calls[0]);
  }
}
