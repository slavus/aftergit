/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.merge;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Classification table of a 3-way change. Port of IntelliJ MergeRangeUtil.getMergeType. */
public final class MergeTypes {
  private MergeTypes() {}

  /**
   * @param empty     whether a side's range is empty
   * @param equal     equality under the active comparison policy
   * @param trueEqual equality under DEFAULT policy (distinguishes whitespace-only changes)
   */
  public static ConflictType classify(Predicate<ThreeSide> empty,
                                      BiPredicate<ThreeSide, ThreeSide> equal,
                                      BiPredicate<ThreeSide, ThreeSide> trueEqual) {
    boolean leftEmpty = empty.test(ThreeSide.LEFT);
    boolean baseEmpty = empty.test(ThreeSide.BASE);
    boolean rightEmpty = empty.test(ThreeSide.RIGHT);

    if (baseEmpty) {
      if (leftEmpty) {
        return new ConflictType(ConflictType.Kind.INSERTED, false, true);
      }
      if (rightEmpty) {
        return new ConflictType(ConflictType.Kind.INSERTED, true, false);
      }
      return equal.test(ThreeSide.LEFT, ThreeSide.RIGHT)
          ? new ConflictType(ConflictType.Kind.INSERTED, true, true)
          : new ConflictType(ConflictType.Kind.CONFLICT, true, true);
    }
    if (leftEmpty && rightEmpty) {
      return new ConflictType(ConflictType.Kind.DELETED, true, true);
    }
    boolean unchangedLeft = equal.test(ThreeSide.BASE, ThreeSide.LEFT);
    boolean unchangedRight = equal.test(ThreeSide.BASE, ThreeSide.RIGHT);
    if (unchangedLeft && unchangedRight) {
      boolean trueUnchangedLeft = trueEqual.test(ThreeSide.BASE, ThreeSide.LEFT);
      boolean trueUnchangedRight = trueEqual.test(ThreeSide.BASE, ThreeSide.RIGHT);
      return new ConflictType(ConflictType.Kind.MODIFIED, !trueUnchangedLeft, !trueUnchangedRight);
    }
    if (unchangedLeft) {
      return new ConflictType(rightEmpty ? ConflictType.Kind.DELETED : ConflictType.Kind.MODIFIED, false, true);
    }
    if (unchangedRight) {
      return new ConflictType(leftEmpty ? ConflictType.Kind.DELETED : ConflictType.Kind.MODIFIED, true, false);
    }
    if (equal.test(ThreeSide.LEFT, ThreeSide.RIGHT)) {
      return new ConflictType(ConflictType.Kind.MODIFIED, true, true);
    }
    return new ConflictType(ConflictType.Kind.CONFLICT, true, true);
  }
}
