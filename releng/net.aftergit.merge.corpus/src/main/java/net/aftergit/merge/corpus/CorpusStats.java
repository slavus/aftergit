package net.aftergit.merge.corpus;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Counters of one repository or of the whole corpus (spec §10.3 metrics). */
final class CorpusStats {
  int merges;
  int conflictedMerges;
  int failedMerges;
  int files;
  int skippedFiles;
  int errors;
  int conflicts;
  int autoResolved;
  int fullyResolved;
  int javaChecked;
  int javaNoNewErrors;
  int javaFailures;
  int deviations;
  int deviatingFiles;
  int fallbacks;
  int committedKnown;
  int matchesCommit;
  /** Auto-resolved blocks with a compound token that none of their sides has. */
  int madeUpTokens;
  /** Conflict blocks each resolver resolved, by id. */
  final Map<String, Integer> resolvedBy = new TreeMap<>();
  /** Fully resolved Java files javac finds a new error in, counted for each resolver that resolved a block there. */
  final Map<String, Integer> notCompilingBy = new TreeMap<>();

  void add(FileResult r) {
    files++;
    if (r.error() != null) {
      errors++;
      return;
    }
    conflicts += r.conflicts();
    autoResolved += r.autoResolved();
    r.byResolver().forEach((id, n) -> resolvedBy.merge(id, n, Integer::sum));
    if (r.javaChecked() && !r.noNewErrors()) {
      r.byResolver().keySet().forEach(id -> notCompilingBy.merge(id, 1, Integer::sum));
    }
    if (r.fullyResolved()) {
      fullyResolved++;
    }
    if (r.javaFailed()) {
      javaFailures++;
    }
    if (r.javaChecked()) {
      javaChecked++;
      if (r.noNewErrors()) {
        javaNoNewErrors++;
      }
    }
    deviations += r.deviations();
    madeUpTokens += r.madeUpTokens();
    if (r.deviations() > 0 || r.madeUpTokens() > 0) {
      deviatingFiles++;
    }
    if (r.fallback()) {
      fallbacks++;
    }
    if (r.committedKnown()) {
      committedKnown++;
      if (r.matchesCommit()) {
        matchesCommit++;
      }
    }
  }

  void addAll(CorpusStats o) {
    merges += o.merges;
    conflictedMerges += o.conflictedMerges;
    failedMerges += o.failedMerges;
    files += o.files;
    skippedFiles += o.skippedFiles;
    errors += o.errors;
    conflicts += o.conflicts;
    autoResolved += o.autoResolved;
    fullyResolved += o.fullyResolved;
    javaChecked += o.javaChecked;
    javaNoNewErrors += o.javaNoNewErrors;
    javaFailures += o.javaFailures;
    deviations += o.deviations;
    madeUpTokens += o.madeUpTokens;
    deviatingFiles += o.deviatingFiles;
    fallbacks += o.fallbacks;
    committedKnown += o.committedKnown;
    matchesCommit += o.matchesCommit;
    o.resolvedBy.forEach((id, n) -> resolvedBy.merge(id, n, Integer::sum));
    o.notCompilingBy.forEach((id, n) -> notCompilingBy.merge(id, n, Integer::sum));
  }

  static String percent(int part, int whole) {
    return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f %%", 100.0 * part / whole);
  }
}
