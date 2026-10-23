package net.aftergit.merge.git;

import org.eclipse.core.resources.IFile;

/**
 * What Resolve All did with one file. {@code path}: the file's workspace path, or the repository-relative path of a
 * file outside the workspace ({@code file} null). {@code conflicts}: conflicts the file had for the user (git's own
 * resolutions excluded); {@code resolved}: those auto-resolved now; {@code notCompiling}: auto-resolved blocks with a
 * compile error that neither ours nor theirs has there (spec §5.4).
 */
public record FileOutcome(IFile file, String path, Result result, ConflictKind kind, int resolved, int conflicts,
    int notCompiling, Reason reason) {
  public enum Result { FULL, PARTIAL, UNTOUCHED }

  /** Why a file was left untouched ({@link #NONE} for written files). */
  public enum Reason {
    NONE, NOTHING_RESOLVED, OTHER_KIND, NOT_CONFLICTED, NOT_IN_REPOSITORY, NOT_IN_WORKSPACE, BUSY,
    MIXED_LINE_ENDINGS, NO_WORKING_TREE_FILE, UNREADABLE, FAILED,
    /** Saved or rewritten by someone else while the run resolved it: the resolution was not written. */
    CHANGED_DURING_RUN
  }

  static FileOutcome untouched(IFile file, Reason reason) {
    return new FileOutcome(file, file.getFullPath().toString(), Result.UNTOUCHED, ConflictKind.TEXT, 0, 0, 0, reason);
  }

  static FileOutcome otherKind(IFile file, ConflictKind kind) {
    return new FileOutcome(file, file.getFullPath().toString(), Result.UNTOUCHED, kind, 0, 0, 0, Reason.OTHER_KIND);
  }

  static FileOutcome outside(String path) {
    return new FileOutcome(null, path, Result.UNTOUCHED, ConflictKind.TEXT, 0, 0, 0, Reason.NOT_IN_WORKSPACE);
  }

  /** A conflicted workspace file the user still has to resolve ("open remaining"). */
  public boolean remaining() {
    return file != null && result != Result.FULL && reason != Reason.NOT_CONFLICTED
        && reason != Reason.NOT_IN_REPOSITORY;
  }
}
