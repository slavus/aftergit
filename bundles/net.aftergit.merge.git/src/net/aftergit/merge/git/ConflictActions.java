package net.aftergit.merge.git;

import java.io.IOException;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/** Resolutions of conflicts the text resolver does not show (spec §8.4). */
public final class ConflictActions {
  public enum Side { OURS, THEIRS }

  private ConflictActions() {}

  /**
   * Keeps one side: {@code checkout --ours/--theirs} and {@code add}. A side without a stage deleted the file, so
   * keeping it deletes the file ({@code git rm}). Only for a conflicted path whose kind offers sides.
   */
  public static void keep(Repository repo, String path, Side side) throws IOException, GitAPIException {
    if (!ConflictKinds.classify(repo, path).offersSides()) {
      throw new IllegalStateException("No keep-a-side conflict at " + path);
    }
    int stage = side == Side.OURS ? DirCacheEntry.STAGE_2 : DirCacheEntry.STAGE_3;
    if (!hasStage(repo, path, stage)) {
      delete(repo, path);
      return;
    }
    Git git = Git.wrap(repo);
    git.checkout().setStage(side == Side.OURS ? CheckoutCommand.Stage.OURS : CheckoutCommand.Stage.THEIRS)
        .addPath(path).call();
    git.add().addFilepattern(path).call();
    IndexEvents.indexChanged(repo, false);
  }

  /** {@code git rm}: the file and all its stages go. */
  public static void delete(Repository repo, String path) throws IOException, GitAPIException {
    if (ConflictKinds.classify(repo, path) == ConflictKind.NONE) {
      throw new IllegalStateException("No conflict at " + path);
    }
    Git.wrap(repo).rm().addFilepattern(path).call();
    IndexEvents.indexChanged(repo, false);
  }

  private static boolean hasStage(Repository repo, String path, int stage) throws IOException {
    DirCache cache = repo.readDirCache();
    int first = cache.findEntry(path);
    if (first < 0) {
      return false;
    }
    for (int i = first; i < cache.getEntryCount() && cache.getEntry(i).getPathString().equals(path); i++) {
      if (cache.getEntry(i).getStage() == stage) {
        return true;
      }
    }
    return false;
  }
}
