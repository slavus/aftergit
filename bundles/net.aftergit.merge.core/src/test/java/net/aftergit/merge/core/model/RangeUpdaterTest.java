/*
 * Behaviour mirrors IntelliJ Community DiffUtil.updateRangeOnModification (non-greedy),
 * Copyright 2000-2026 JetBrains s.r.o. Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RangeUpdaterTest {
  @Test
  void changeBeforeRangeShifts() {
    assertEquals(new RangeUpdater.Updated(7, 9, false), RangeUpdater.update(5, 7, 1, 2, 2));
  }

  @Test
  void changeAfterRangeKeeps() {
    assertEquals(new RangeUpdater.Updated(5, 7, false), RangeUpdater.update(5, 7, 7, 8, 3));
  }

  @Test
  void insertionAtRangeStartShifts() {
    assertEquals(new RangeUpdater.Updated(6, 8, false), RangeUpdater.update(5, 7, 5, 5, 1));
  }

  @Test
  void changeInsideGrows() {
    assertEquals(new RangeUpdater.Updated(5, 9, false), RangeUpdater.update(5, 7, 6, 7, 2));
  }

  @Test
  void overlappingChangeDamages() {
    assertEquals(new RangeUpdater.Updated(5, 6, true), RangeUpdater.update(5, 7, 6, 9, 0));
    assertEquals(new RangeUpdater.Updated(6, 7, true), RangeUpdater.update(5, 7, 3, 6, 0));
  }
}
