package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

class ScrollMapTest {
  // base "a b c", left replaces b with 1 line, right with 3 lines -> conflict block: L=1, R=3, result(base)=1
  static MergeModel model() {
    return HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR1\nR2\nR3\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
  }

  @Test
  void virtualHeightIsMaxPerSegment() {
    ScrollMap s = ScrollMap.build(model(), 3, 3, 5);
    assertEquals(5.0, s.virtualLines(), 1e-9); // 1 (a) + max(1,1,3) + 1 (c)
  }

  @Test
  void linearInterpolationInsideBlock() {
    ScrollMap s = ScrollMap.build(model(), 3, 3, 5);
    // virtual 2.5 = middle of the block (virtual 1..4)
    assertEquals(1.5, s.toPane(PaneKind.LEFT, 2.5), 1e-9);
    assertEquals(2.5, s.toPane(PaneKind.RIGHT, 2.5), 1e-9);
    assertEquals(2.5, s.toVirtual(PaneKind.RIGHT, 2.5), 1e-9);
    assertEquals(4.0, s.toPane(PaneKind.RIGHT, 4.0), 1e-9); // "c" in right
    assertEquals(2.0, s.toPane(PaneKind.LEFT, 4.0), 1e-9);  // "c" in left
  }

  @Test
  void roundTrip() {
    ScrollMap s = ScrollMap.build(model(), 3, 3, 5);
    for (double line = 0; line <= 3; line += 0.25) {
      assertEquals(line, s.toPane(PaneKind.LEFT, s.toVirtual(PaneKind.LEFT, line)), 1e-9);
    }
  }

  @Test
  void anchorIsViewportMiddle() {
    ScrollMap s = ScrollMap.build(model(), 3, 3, 5);
    assertEquals(0.0, s.paneTop(PaneKind.LEFT, 0, 2), 1e-9);
    double top = s.paneTop(PaneKind.LEFT, 1.5, 2); // middle at virtual 2.5 -> left 1.5 -> top 0.5
    assertEquals(0.5, top, 1e-9);
    assertEquals(1.5, s.virtualTop(PaneKind.LEFT, top, 2), 1e-9);
  }

  @Test
  void segmentLengthPerPane() {
    ScrollMap s = ScrollMap.build(model(), 3, 3, 5);
    assertEquals(1, s.segmentLength(PaneKind.LEFT, 2.5));
    assertEquals(3, s.segmentLength(PaneKind.RIGHT, 2.5));
    assertEquals(1, s.segmentLength(PaneKind.RESULT, 0.5));
    assertEquals(0, s.segmentLength(PaneKind.RESULT, 5.0));
  }

  @Test
  void emptyDocumentsMapToZero() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("", "", "", null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    ScrollMap s = ScrollMap.build(m, 0, 0, 0);
    assertEquals(0.0, s.virtualLines(), 1e-9);
    assertEquals(0.0, s.toPane(PaneKind.RESULT, 3), 1e-9);
    assertEquals(0.0, s.paneTop(PaneKind.LEFT, 10, 20), 1e-9);
  }

  @Test
  void largeModelIsLinear() {
    StringBuilder base = new StringBuilder();
    StringBuilder left = new StringBuilder();
    StringBuilder right = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      base.append("line ").append(i).append('\n');
      left.append(i % 17 == 0 ? "L " : "line ").append(i).append('\n');
      right.append(i % 17 == 0 ? "R " : "line ").append(i).append('\n');
    }
    MergeModel m = HybridInitializer.initialize(new MergeInput(base.toString(), left.toString(), right.toString(), null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    long t0 = System.nanoTime();
    ScrollMap s = ScrollMap.build(m, 5000, 5000, 5000);
    for (int i = 0; i < 10_000; i++) {
      s.paneTop(PaneKind.RIGHT, i % 5000, 40);
    }
    assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "mapping must not be quadratic");
    assertEquals(5000.0, s.virtualLines(), 1e-9);
  }
}
