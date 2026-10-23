package net.aftergit.merge.git;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/** The conflicted paths of a repository. */
public final class ConflictPaths {
  private ConflictPaths() {}

  /** Repository-relative paths with a stage above 0, sorted, each once. */
  public static List<String> list(Repository repo) throws IOException {
    DirCache cache = repo.readDirCache();
    TreeSet<String> out = new TreeSet<>();
    for (int i = 0; i < cache.getEntryCount(); i++) {
      DirCacheEntry e = cache.getEntry(i);
      if (e.getStage() != DirCacheEntry.STAGE_0) {
        out.add(e.getPathString());
      }
    }
    return List.copyOf(out);
  }
}
