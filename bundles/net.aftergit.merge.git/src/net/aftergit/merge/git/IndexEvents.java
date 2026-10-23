package net.aftergit.merge.git;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.lib.Repository;

/**
 * Tells everyone who watches a repository that AfterGit changed its index (docs/INSTALL.md, "Git Staging refreshes at once"), so Git
 * Staging lists the file as staged at once rather than after EGit's next rescan.
 *
 * <p>The event goes to {@code repo} itself and to each of EGit's own instances of the same {@code .git} directory
 * (also under another path, such as through a symbolic link). EGit's index state, which Git Staging lists, listens
 * on EGit's instance only: JGit's global listeners and EGit's relay of them do not reach it, so without this a
 * stage in a project EGit does not manage shows only after EGit's next rescan. EGit's classes are used only here.
 */
public final class IndexEvents {
  private IndexEvents() {}

  /** {@code internal}: as JGit's {@link IndexChangedEvent} (true when the index was written by this process). */
  public static void indexChanged(Repository repo, boolean internal) {
    repo.fireEvent(new IndexChangedEvent(internal));
    for (Repository egit : egitInstances(repo)) {
      egit.fireEvent(new IndexChangedEvent(internal));
    }
  }

  /** EGit's instances of the repository of {@code repo} other than {@code repo} itself. */
  static List<Repository> egitInstances(Repository repo) {
    List<Repository> out = new ArrayList<>();
    File dir = repo.getDirectory();
    if (dir == null) {
      return out;
    }
    Repository[] all;
    try {
      all = org.eclipse.egit.core.RepositoryCache.INSTANCE.getAllRepositories();
    } catch (RuntimeException | LinkageError e) {
      return out; // EGit is not there, or not started: nobody to tell
    }
    File canonical = canonical(dir);
    for (Repository r : all) {
      File other = r.getDirectory();
      if (r == repo || other == null) {
        continue;
      }
      if (canonical(other).equals(canonical)) {
        out.add(r);
      }
    }
    return out;
  }

  private static File canonical(File f) {
    try {
      return f.getCanonicalFile();
    } catch (IOException e) {
      return f.getAbsoluteFile();
    }
  }
}
