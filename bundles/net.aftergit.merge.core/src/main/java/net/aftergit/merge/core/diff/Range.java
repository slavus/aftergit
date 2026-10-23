package net.aftergit.merge.core.diff;

/** A pair of half-open index ranges: [start1, end1) in the first sequence, [start2, end2) in the second. */
public record Range(int start1, int end1, int start2, int end2) {}
