package net.aftergit.merge.git;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one Resolve All run; {@code requested}: files it was asked to resolve. */
public record ResolveAllReport(List<FileOutcome> files, boolean cancelled, int requested, ResolveAllUndo undo) {
  public ResolveAllReport {
    files = List.copyOf(files);
  }

  public int count(FileOutcome.Result result) {
    return (int) files.stream().filter(f -> f.result() == result).count();
  }

  /** Files without unresolved or not-compiling blocks: the only ones "Stage resolved files" adds. */
  public List<FileOutcome> fullyResolved() {
    return files.stream().filter(f -> f.result() == FileOutcome.Result.FULL).toList();
  }

  public List<FileOutcome> remaining() {
    return files.stream().filter(FileOutcome::remaining).toList();
  }

  /** The report with repository files outside the workspace listed as untouched. */
  public ResolveAllReport withOutside(List<String> paths) {
    List<FileOutcome> all = new ArrayList<>(files);
    for (String p : paths) {
      all.add(FileOutcome.outside(p));
    }
    return new ResolveAllReport(all, cancelled, requested + paths.size(), undo);
  }
}
