package net.aftergit.merge.corpus;

import java.util.Map;

/**
 * What the resolver makes of one replayed conflicted file. {@code conflicts}: conflict blocks git left open, as the
 * resolver sees them after the alignment; {@code deviations}: regions where the clean text differs from git's
 * result; {@code javaChecked}/{@code noNewErrors}: javac on a fully resolved Java file ({@code javaFailed}: the compiler
 * itself crashed or timed out); {@code committedKnown} /
 * {@code matchesCommit}: a fully resolved file compared with what the merge commit recorded (informational);
 * {@code byResolver}: conflict blocks each resolver resolved, by its id; {@code madeUpTokens}: auto-resolved blocks
 * whose text has a compound token (a version, a qualified name) that none of the block's sides has, the first one
 * described in {@code firstMadeUp}.
 */
record FileResult(String repo, String merge, String path, int conflicts, int autoResolved, int unresolved,
    boolean fallback, int deviations, String firstDeviation, boolean javaChecked, boolean noNewErrors,
    boolean committedKnown, boolean matchesCommit, String error, boolean javaFailed, Map<String, Integer> byResolver,
    int madeUpTokens, String firstMadeUp) {

  FileResult {
    byResolver = Map.copyOf(byResolver);
  }

  FileResult(String repo, String merge, String path, int conflicts, int autoResolved, int unresolved, boolean fallback,
      int deviations, String firstDeviation, boolean javaChecked, boolean noNewErrors, boolean committedKnown,
      boolean matchesCommit, String error, boolean javaFailed, Map<String, Integer> byResolver) {
    this(repo, merge, path, conflicts, autoResolved, unresolved, fallback, deviations, firstDeviation, javaChecked,
        noNewErrors, committedKnown, matchesCommit, error, javaFailed, byResolver, 0, null);
  }

  FileResult(String repo, String merge, String path, int conflicts, int autoResolved, int unresolved, boolean fallback,
      int deviations, String firstDeviation, boolean javaChecked, boolean noNewErrors, boolean committedKnown,
      boolean matchesCommit, String error, boolean javaFailed) {
    this(repo, merge, path, conflicts, autoResolved, unresolved, fallback, deviations, firstDeviation, javaChecked,
        noNewErrors, committedKnown, matchesCommit, error, javaFailed, Map.of());
  }

  FileResult(String repo, String merge, String path, int conflicts, int autoResolved, int unresolved, boolean fallback,
      int deviations, String firstDeviation, boolean javaChecked, boolean noNewErrors, boolean committedKnown,
      boolean matchesCommit, String error) {
    this(repo, merge, path, conflicts, autoResolved, unresolved, fallback, deviations, firstDeviation, javaChecked,
        noNewErrors, committedKnown, matchesCommit, error, false);
  }

  static FileResult failed(String repo, String merge, String path, String error) {
    return new FileResult(repo, merge, path, 0, 0, 0, false, 0, null, false, false, false, false, error, false);
  }

  boolean fullyResolved() {
    return error == null && autoResolved > 0 && unresolved == 0;
  }
}
