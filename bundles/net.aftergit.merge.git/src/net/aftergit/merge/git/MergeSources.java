package net.aftergit.merge.git;

import java.nio.charset.Charset;

/**
 * Inputs of one conflicted file. {@code base} is "" when git has no common version (add/add) and null only when a
 * stage could not be read; the same holds for ours/theirs. {@code decodedAs}: the charset all texts were decoded
 * with, the file's own or {@link Decoding#FALLBACK} when some input is not valid in it; null when unknown.
 */
public record MergeSources(String base, String ours, String theirs, String workingTree, Labels labels,
    Charset decodedAs) {
  public MergeSources(String base, String ours, String theirs, String workingTree, Labels labels) {
    this(base, ours, theirs, workingTree, labels, null);
  }
}
