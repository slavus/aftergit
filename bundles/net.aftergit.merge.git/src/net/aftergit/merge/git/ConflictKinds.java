package net.aftergit.merge.git;

import java.io.IOException;
import java.io.InputStream;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;

/** Classifies the conflict of a path from its index stages (spec §8.4). */
public final class ConflictKinds {
  private ConflictKinds() {}

  public static ConflictKind classify(Repository repo, String path) throws IOException {
    return classify(repo, repo.readDirCache(), path);
  }

  /** Like {@link #classify(Repository, String)} on an index already read, for many paths in a row. */
  public static ConflictKind classify(Repository repo, DirCache cache, String path) throws IOException {
    DirCacheEntry[] stage = new DirCacheEntry[4];
    boolean conflicted = false;
    int first = cache.findEntry(path);
    if (first < 0) {
      return ConflictKind.NONE;
    }
    for (int i = first; i < cache.getEntryCount(); i++) {
      DirCacheEntry e = cache.getEntry(i);
      if (!e.getPathString().equals(path)) {
        break;
      }
      stage[e.getStage()] = e;
      conflicted |= e.getStage() != DirCacheEntry.STAGE_0;
    }
    if (!conflicted) {
      return ConflictKind.NONE;
    }
    if (anyMode(stage, FileMode.GITLINK)) {
      return ConflictKind.SUBMODULE;
    }
    if (anyMode(stage, FileMode.SYMLINK)) {
      return ConflictKind.SYMLINK;
    }
    boolean base = stage[1] != null;
    boolean ours = stage[2] != null;
    boolean theirs = stage[3] != null;
    if (ours && theirs) {
      return binary(repo, stage) ? ConflictKind.BINARY : ConflictKind.TEXT;
    }
    if (ours && base) {
      return ConflictKind.MODIFY_DELETE;
    }
    if (theirs && base) {
      return ConflictKind.DELETE_MODIFY;
    }
    return ConflictKind.RENAME;
  }

  private static boolean anyMode(DirCacheEntry[] stage, FileMode mode) {
    for (int s = 1; s <= 3; s++) {
      if (stage[s] != null && mode.equals(stage[s].getRawMode())) {
        return true;
      }
    }
    return false;
  }

  private static boolean binary(Repository repo, DirCacheEntry[] stage) throws IOException {
    for (int s = 1; s <= 3; s++) {
      if (stage[s] == null) {
        continue;
      }
      try (InputStream in = repo.open(stage[s].getObjectId(), Constants.OBJ_BLOB).openStream()) {
        // git's own test: a NUL in the first 8000 bytes (JGit's RawText.isBinary also counts a lone CR, which
        // is text here: the resolver shows it and keeps the CR)
        byte[] head = in.readNBytes(8000);
        for (byte b : head) {
          if (b == 0) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
