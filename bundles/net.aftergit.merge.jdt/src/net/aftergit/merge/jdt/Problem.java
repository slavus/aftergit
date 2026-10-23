package net.aftergit.merge.jdt;

/** An error or warning in the result document, in document offsets. */
public record Problem(int offset, int length, boolean error, String message) {}
