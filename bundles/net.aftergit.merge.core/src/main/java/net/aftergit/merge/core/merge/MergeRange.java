package net.aftergit.merge.core.merge;

/** Half-open ranges in the three inputs: 1 = LEFT, 2 = BASE, 3 = RIGHT. */
public record MergeRange(int start1, int end1, int start2, int end2, int start3, int end3) {

  public boolean isEmpty() {
    return start1 == end1 && start2 == end2 && start3 == end3;
  }

  public int start(ThreeSide side) {
    return side.select(start1, start2, start3);
  }

  public int end(ThreeSide side) {
    return side.select(end1, end2, end3);
  }

  public boolean isEmpty(ThreeSide side) {
    return start(side) == end(side);
  }
}
