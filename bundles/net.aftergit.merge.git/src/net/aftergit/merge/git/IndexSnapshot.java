package net.aftergit.merge.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/** The index entries (all stages) of some paths, to put back later; other paths of the index are never touched. */
public final class IndexSnapshot {
  private final Repository repo;
  private final Map<String, List<DirCacheEntry>> entries;

  private IndexSnapshot(Repository repo, Map<String, List<DirCacheEntry>> entries) {
    this.repo = repo;
    this.entries = entries;
  }

  public static IndexSnapshot take(Repository repo, Collection<String> paths) throws IOException {
    DirCache cache = repo.readDirCache();
    Map<String, List<DirCacheEntry>> out = new LinkedHashMap<>();
    for (String path : paths) {
      List<DirCacheEntry> list = new ArrayList<>();
      int first = cache.findEntry(path);
      for (int i = Math.max(first, 0); first >= 0 && i < cache.getEntryCount(); i++) {
        DirCacheEntry e = cache.getEntry(i);
        if (!e.getPathString().equals(path)) {
          break;
        }
        list.add(new DirCacheEntry(e));
      }
      out.put(path, list);
    }
    return new IndexSnapshot(repo, out);
  }

  public Repository repository() {
    return repo;
  }

  public Set<String> paths() {
    return entries.keySet();
  }

  /** True when the index holds exactly the saved entries of the paths (stage, mode and object of each). */
  public boolean isCurrent() throws IOException {
    IndexSnapshot now = take(repo, entries.keySet());
    for (Map.Entry<String, List<DirCacheEntry>> e : entries.entrySet()) {
      List<DirCacheEntry> saved = e.getValue();
      List<DirCacheEntry> current = now.entries.get(e.getKey());
      if (saved.size() != current.size()) {
        return false;
      }
      for (int i = 0; i < saved.size(); i++) {
        DirCacheEntry a = saved.get(i);
        DirCacheEntry b = current.get(i);
        if (a.getStage() != b.getStage() || a.getRawMode() != b.getRawMode()
            || !a.getObjectId().equals(b.getObjectId())) {
          return false;
        }
      }
    }
    return true;
  }

  /** True when every path had conflict stages (1-3) when the snapshot was taken. */
  public boolean hasConflictStages() {
    for (List<DirCacheEntry> list : entries.values()) {
      if (list.stream().noneMatch(e -> e.getStage() != DirCacheEntry.STAGE_0)) {
        return false;
      }
    }
    return true;
  }

  /** Puts the saved entries of the paths back (a path that had none is removed); tells listeners such as EGit. */
  public void restore() throws IOException {
    DirCache cache = repo.lockDirCache();
    try {
      DirCacheBuilder b = cache.builder();
      for (int i = 0; i < cache.getEntryCount(); i++) {
        DirCacheEntry e = cache.getEntry(i);
        if (!entries.containsKey(e.getPathString())) {
          b.add(e);
        }
      }
      for (List<DirCacheEntry> list : entries.values()) {
        for (DirCacheEntry e : list) {
          b.add(new DirCacheEntry(e));
        }
      }
      b.finish(); // sorts the entries
      if (!b.commit()) {
        throw new IOException("Could not write the index of " + repo.getDirectory());
      }
    } finally {
      cache.unlock();
    }
    IndexEvents.indexChanged(repo, true);
  }
}
