package net.aftergit.merge.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;

/**
 * The conflicted paths of each repository's index, for checks that run on the UI thread at every selection (the
 * handler of EGit's Merge Tool command). The index is read once and again only after it changed: its file's time,
 * size or identity differ, or JGit reported an index change of the repository (every write in this process does).
 * Only the stages are looked at, never the contents: whether a two-sided conflict is binary is told later, by
 * {@link ConflictKinds}.
 */
public final class ConflictCache {
  private static final AtomicLong READS = new AtomicLong();
  private static final Map<File, Snapshot> CACHE = new ConcurrentHashMap<>();

  static {
    Repository.getGlobalListenerList().addIndexChangedListener(event -> {
      Repository r = event.getRepository();
      if (r != null && r.getDirectory() != null) {
        CACHE.remove(r.getDirectory().getAbsoluteFile());
      }
    });
  }

  /** The index as last read: its file's stamp and, per conflicted path, whether ours and theirs are both files. */
  private record Snapshot(Object stamp, Map<String, Boolean> conflicts) {}

  private ConflictCache() {}

  /** How often an index was read so far; for tests. */
  public static long indexReads() {
    return READS.get();
  }

  /**
   * True when {@code path} has conflict stages of ours and theirs and both are regular files: a conflict that may be
   * text (modify/delete, submodules, symbolic links and renames are not).
   */
  public static boolean twoSidedFileConflict(Repository repo, String path) throws IOException {
    File dir = repo.getDirectory();
    if (dir == null) {
      return false;
    }
    File key = dir.getAbsoluteFile();
    Object stamp = stamp(repo.getIndexFile());
    Snapshot s = CACHE.get(key);
    if (s == null || !Objects.equals(s.stamp(), stamp)) {
      s = read(repo, stamp);
      CACHE.put(key, s);
    }
    return Boolean.TRUE.equals(s.conflicts().get(path));
  }

  private static Snapshot read(Repository repo, Object stamp) throws IOException {
    READS.incrementAndGet();
    DirCache cache = repo.readDirCache();
    Map<String, int[]> modes = new HashMap<>();
    for (int i = 0; i < cache.getEntryCount(); i++) {
      DirCacheEntry e = cache.getEntry(i);
      if (e.getStage() != DirCacheEntry.STAGE_0) {
        modes.computeIfAbsent(e.getPathString(), p -> new int[4])[e.getStage()] = e.getRawMode();
      }
    }
    Map<String, Boolean> conflicts = new HashMap<>();
    modes.forEach((p, m) -> conflicts.put(p, regular(m[DirCacheEntry.STAGE_2]) && regular(m[DirCacheEntry.STAGE_3])
        && (m[DirCacheEntry.STAGE_1] == 0 || regular(m[DirCacheEntry.STAGE_1]))));
    return new Snapshot(stamp, conflicts);
  }

  private static boolean regular(int mode) {
    return FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode);
  }

  /** Time, size and identity of the index file; null when there is none. */
  private static Object stamp(File index) throws IOException {
    try {
      BasicFileAttributes a = Files.readAttributes(index.toPath(), BasicFileAttributes.class);
      return java.util.List.of(a.lastModifiedTime(), a.size(), String.valueOf(a.fileKey()));
    } catch (NoSuchFileException e) {
      return null;
    }
  }
}
