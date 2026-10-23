package net.aftergit.merge.core.merge;

import java.util.Objects;

/** One 3-way change: line ranges, classification and the part of the file it lies in. */
public record MergeChange(MergeRange range, ConflictType type, Region region) {
  public MergeChange {
    Objects.requireNonNull(region, "region");
  }

  public MergeChange(MergeRange range, ConflictType type) {
    this(range, type, Region.BODY);
  }
}
