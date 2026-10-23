/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.merge;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.Range;

/**
 * Combines two base-anchored diffs (base→left, base→right) into 3-way change ranges.
 * Every overlap of an unchanged base range of both diffs becomes an equal triple; everything
 * between equal triples is one change. Port of IntelliJ ComparisonMergeUtil.FairMergeBuilder
 * including IgnoringChangeBuilder.
 */
public final class FairMergeBuilder {

  /** True (DEFAULT policy) equality of single elements; used to keep whitespace-only changes. */
  @FunctionalInterface
  public interface SideEquality {
    boolean equal(int left, int base, int right);
  }

  private FairMergeBuilder() {}

  public static List<MergeRange> build(List<Range> baseToLeft, List<Range> baseToRight,
                                       int leftLength, int baseLength, int rightLength,
                                       SideEquality trueEquality) {
    ChangeBuilder builder = new ChangeBuilder(trueEquality);
    int i1 = 0;
    int i2 = 0;
    while (i1 < baseToLeft.size() && i2 < baseToRight.size()) {
      Range r1 = baseToLeft.get(i1);
      Range r2 = baseToRight.get(i2);
      if (r1.end1() <= r2.start1()) {
        i1++;
        continue;
      }
      if (r2.end1() <= r1.start1()) {
        i2++;
        continue;
      }
      int startBase = Math.max(r1.start1(), r2.start1());
      int endBase = Math.min(r1.end1(), r2.end1());
      int count = endBase - startBase;
      int startLeft = r1.start2() + (startBase - r1.start1());
      int startRight = r2.start2() + (startBase - r2.start1());
      builder.markEqual(startLeft, startBase, startRight, startLeft + count, endBase, startRight + count);
      if (r1.end1() <= r2.end1()) {
        i1++;
      } else {
        i2++;
      }
    }
    return builder.finish(leftLength, baseLength, rightLength);
  }

  private static final class ChangeBuilder {
    private final List<MergeRange> changes = new ArrayList<>();
    private final SideEquality trueEquality;
    private int index1;
    private int index2;
    private int index3;

    ChangeBuilder(SideEquality trueEquality) {
      this.trueEquality = trueEquality;
    }

    void markEqual(int start1, int start2, int start3, int end1, int end2, int end3) {
      processChange(index1, index2, index3, start1, start2, start3);
      index1 = end1;
      index2 = end2;
      index3 = end3;
    }

    List<MergeRange> finish(int length1, int length2, int length3) {
      processChange(index1, index2, index3, length1, length2, length3);
      return changes;
    }

    private void processChange(int start1, int start2, int start3, int end1, int end2, int end3) {
      if (trueEquality != null) {
        MergeRange last = changes.isEmpty() ? null : changes.get(changes.size() - 1);
        addIgnoredChanges(last == null ? 0 : last.end1(), last == null ? 0 : last.end2(),
            last == null ? 0 : last.end3(), start1, start2, start3);
      }
      addChange(start1, start2, start3, end1, end2, end3);
    }

    /** Emits runs of elements that are equal only under the normalizing policy. */
    private void addIgnoredChanges(int start1, int start2, int start3, int end1, int end2, int end3) {
      int count = end2 - start2;
      int firstIgnored = -1;
      for (int i = 0; i < count; i++) {
        boolean ignored = !trueEquality.equal(start1 + i, start2 + i, start3 + i);
        if (ignored && firstIgnored == -1) {
          firstIgnored = i;
        }
        if (!ignored && firstIgnored != -1) {
          addChange(start1 + firstIgnored, start2 + firstIgnored, start3 + firstIgnored,
              start1 + i, start2 + i, start3 + i);
          firstIgnored = -1;
        }
      }
      if (firstIgnored != -1) {
        addChange(start1 + firstIgnored, start2 + firstIgnored, start3 + firstIgnored,
            start1 + count, start2 + count, start3 + count);
      }
    }

    private void addChange(int start1, int start2, int start3, int end1, int end2, int end3) {
      if (start1 == end1 && start2 == end2 && start3 == end3) {
        return;
      }
      changes.add(new MergeRange(start1, end1, start2, end2, start3, end3));
    }
  }
}
