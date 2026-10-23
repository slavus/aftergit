package net.aftergit.merge.core.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ThreeSideTest {
  @Test
  void selectPicksMatchingArgument() {
    assertEquals("l", ThreeSide.LEFT.select("l", "b", "r"));
    assertEquals("b", ThreeSide.BASE.select("l", "b", "r"));
    assertEquals("r", ThreeSide.RIGHT.select("l", "b", "r"));
  }

  @Test
  void ordinalsAreStable() {
    assertEquals(0, ThreeSide.LEFT.ordinal());
    assertEquals(1, ThreeSide.BASE.ordinal());
    assertEquals(2, ThreeSide.RIGHT.ordinal());
  }
}
