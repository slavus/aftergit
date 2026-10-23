package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StripeGeometryTest {
  @Test
  void scalesBlocksToHeight() {
    var m = BlockStylesTest.model(); // 4 lines; conflict at line 1, right-only change at line 3
    List<StripeGeometry.Marker> right = StripeGeometry.markers(m, PaneKind.RIGHT, 4, 401, BlockStyles.origins(m));
    assertEquals(2, right.size());
    assertEquals(100, right.get(0).y());
    assertEquals(100, right.get(0).height());
    assertTrue(right.get(0).strong());
    assertEquals(300, right.get(1).y());
    assertFalse(right.get(1).strong());
  }

  @Test
  void emptyBlockGetsTwoPixels() {
    var m = net.aftergit.merge.core.model.HybridInitializer.initialize(
        new net.aftergit.merge.core.model.MergeInput("a\nb\nc\n", "a\nc\n", "a\nb\nc\n", null),
        net.aftergit.merge.core.diff.ComparisonPolicy.DEFAULT,
        net.aftergit.merge.core.resolve.ResolverPipeline.none()).model();
    List<StripeGeometry.Marker> left = StripeGeometry.markers(m, PaneKind.LEFT, 2, 201, BlockStyles.origins(m));
    assertEquals(1, left.size());
    assertEquals(2, left.get(0).height());
  }

  @Test
  void aSideNotTakenIsMarkedInTheDroppedColour() {
    var m = BlockStylesTest.model();
    m.take(0, net.aftergit.merge.core.merge.ThreeSide.LEFT);
    List<StripeGeometry.Marker> right = StripeGeometry.markers(m, PaneKind.RIGHT, 4, 401, BlockStyles.origins(m));
    assertEquals(LineStyle.Kind.DROPPED, right.get(0).kind());
    assertFalse(right.get(0).strong());
  }

  @Test
  void hitNearestWithinTenPixels() {
    List<StripeGeometry.Marker> ms = List.of(new StripeGeometry.Marker(0, 100, 5, LineStyle.Kind.CONFLICT, true),
        new StripeGeometry.Marker(1, 300, 5, LineStyle.Kind.ADDED, false));
    assertEquals(0, StripeGeometry.hit(ms, 108));
    assertEquals(1, StripeGeometry.hit(ms, 292));
    assertEquals(-1, StripeGeometry.hit(ms, 200));
  }

  @Test
  void viewportAtLeastFivePixels() {
    assertArrayEquals(new int[] {0, 5}, StripeGeometry.viewport(0, 1, 10000, 400));
    assertArrayEquals(new int[] {100, 100}, StripeGeometry.viewport(100, 100, 400, 401));
  }

  @Test
  void viewportStaysInsideTheStripe() {
    assertArrayEquals(new int[] {0, 400}, StripeGeometry.viewport(0, 40, 5, 400), "short file");
    int[] end = StripeGeometry.viewport(95, 40, 100, 100);
    assertTrue(end[0] >= 0 && end[0] + end[1] <= 100, "at the end: " + end[0] + "+" + end[1]);
    int[] last = StripeGeometry.viewport(100, 1, 100, 100);
    assertTrue(last[0] + last[1] <= 100 && last[1] >= 5, "last line: " + last[0] + "+" + last[1]);
  }

  @Test
  void errorMarksScaleLikeBlocks() {
    assertArrayEquals(new int[] {0, 50}, StripeGeometry.errorMarks(new int[] {0, 50}, 100, 101));
  }
}
