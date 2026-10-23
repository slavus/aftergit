package net.aftergit.merge.git;

import java.io.IOException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/** Index-based conflict detection. */
public final class ConflictState {
  private ConflictState() {}

  public static boolean isConflicted(Repository repo, String path) throws IOException {
    DirCache cache = repo.readDirCache();
    int first = cache.findEntry(path);
    if (first < 0) {
      return false;
    }
    for (int i = first; i < cache.getEntryCount(); i++) {
      DirCacheEntry e = cache.getEntry(i);
      if (!e.getPathString().equals(path)) {
        break;
      }
      if (e.getStage() != DirCacheEntry.STAGE_0) {
        return true;
      }
    }
    return false;
  }
}
