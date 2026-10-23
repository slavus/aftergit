package net.aftergit.merge.git;

/** What kind of conflict a path has (spec §8.4); decides how the user resolves it. */
public enum ConflictKind {
  /** No conflict. */
  NONE,
  /** Content of a text file, including add/add: the three-pane resolver. */
  TEXT,
  /** Ours changed the file, theirs deleted it. */
  MODIFY_DELETE,
  /** Ours deleted the file, theirs changed it. */
  DELETE_MODIFY,
  /** Content of a binary file. */
  BINARY,
  /** A submodule (gitlink) entry. */
  SUBMODULE,
  /** A symbolic link. */
  SYMLINK,
  /** Part of a rename conflict (rename/rename, rename/delete): the path has a single stage. */
  RENAME;

  public boolean opensEditor() {
    return this == TEXT;
  }

  /** Resolved by keeping one side or deleting the file. */
  public boolean offersSides() {
    return this == MODIFY_DELETE || this == DELETE_MODIFY || this == BINARY;
  }

  /** Beyond this resolver: EGit's Merge Tool is offered. */
  public boolean needsMergeTool() {
    return this == SUBMODULE || this == SYMLINK || this == RENAME;
  }
}
