package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConnectorGeometryTest {
  @Test
  void symmetricCurveThroughButtonColumns() {
    var s = ConnectorGeometry.shape(new ConnectorGeometry.Band(0, 20, 38, 50, 104), 70);
    assertEquals(10f, s.top().x0());
    assertEquals(20f, s.top().y0());
    assertEquals(34.5f, s.top().c1x());
    assertEquals(20f, s.top().c1y());
    assertEquals(34.5f, s.top().c2x());
    assertEquals(50f, s.top().c2y());
    assertEquals(59f, s.top().x1());
    assertEquals(50f, s.top().y1());
    assertEquals(59f, s.bottom().x0());
    assertEquals(104f, s.bottom().y0());
    assertEquals(10f, s.bottom().x1());
    assertEquals(38f, s.bottom().y1());
  }

  @Test
  void emptySideBandHasZeroHeight() {
    var s = ConnectorGeometry.shape(new ConnectorGeometry.Band(0, 20, 20, 40, 58), 70);
    assertEquals(s.top().y0(), s.bottom().y1());
  }

  @Test
  void visibility() {
    assertTrue(ConnectorGeometry.visible(new ConnectorGeometry.Band(0, -10, 5, 30, 40), 100));
    assertFalse(ConnectorGeometry.visible(new ConnectorGeometry.Band(0, -30, -20, -15, -5), 100));
    assertFalse(ConnectorGeometry.visible(new ConnectorGeometry.Band(0, 120, 130, 140, 150), 100));
  }

  @Test
  void hitColumns() {
    var b = new ConnectorGeometry.Band(0, 20, 38, 50, 104);
    assertEquals(ConnectorGeometry.Hit.TAKE, ConnectorGeometry.hit(b, 3, 25, 70, true));
    assertEquals(ConnectorGeometry.Hit.IGNORE, ConnectorGeometry.hit(b, 66, 60, 70, true));
    assertEquals(ConnectorGeometry.Hit.NONE, ConnectorGeometry.hit(b, 35, 60, 70, true));
    assertEquals(ConnectorGeometry.Hit.NONE, ConnectorGeometry.hit(b, 3, 60, 70, true)); // below the left range
    // right connector: side pane on the right edge
    assertEquals(ConnectorGeometry.Hit.TAKE, ConnectorGeometry.hit(b, 66, 60, 70, false));
    assertEquals(ConnectorGeometry.Hit.IGNORE, ConnectorGeometry.hit(b, 3, 25, 70, false));
  }

  @Test
  void emptyRangeStillHasAButton() {
    var b = new ConnectorGeometry.Band(0, 20, 20, 50, 70);
    assertEquals(ConnectorGeometry.Hit.TAKE, ConnectorGeometry.hit(b, 3, 22, 70, true));
  }

  @Test
  void theCenterIsTheMiddleOfTheBandAndNearItIsAHit() {
    ConnectorGeometry.Band b = new ConnectorGeometry.Band(0, 10, 30, 50, 70);
    assertArrayEquals(new int[] {34, 40}, ConnectorGeometry.center(b, 70));
    assertTrue(ConnectorGeometry.nearCenter(b, 34, 40, 70));
    assertTrue(ConnectorGeometry.nearCenter(b, 40, 46, 70));
    assertFalse(ConnectorGeometry.nearCenter(b, 34, 50, 70));
  }

  @Test
  void theSparklesHitAreaReachesItsRing() {
    ConnectorGeometry.Band b = new ConnectorGeometry.Band(0, 10, 30, 50, 70);
    assertTrue(ConnectorGeometry.nearCenter(b, 34 + 8, 40, 70), "a click on the ring (radius 8)");
    assertFalse(ConnectorGeometry.nearCenter(b, 34 + 10, 40, 70));
  }
}
