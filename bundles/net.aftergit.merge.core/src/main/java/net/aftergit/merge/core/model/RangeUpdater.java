/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.model;

/** Updates a line range after a document modification. Port of DiffUtil.updateRangeOnModification. */
public final class RangeUpdater {

  public record Updated(int start, int end, boolean damaged) {}

  private RangeUpdater() {}

  /**
   * @param changeStart first replaced line
   * @param changeEnd   end (exclusive) of replaced lines before the change
   * @param shift       new line count minus replaced line count
   */
  public static Updated update(int start, int end, int changeStart, int changeEnd, int shift) {
    if (end <= changeStart) {
      return new Updated(start, end, false);
    }
    if (start >= changeEnd) {
      return new Updated(start + shift, end + shift, false);
    }
    if (start <= changeStart && end >= changeEnd) {
      return new Updated(start, end + shift, false);
    }
    int newChangeEnd = changeEnd + shift;
    if (start >= changeStart && end <= changeEnd) {
      return new Updated(newChangeEnd, newChangeEnd, true);
    }
    if (start < changeStart) {
      return new Updated(start, changeStart, true);
    }
    return new Updated(newChangeEnd, end + shift, true);
  }
}
