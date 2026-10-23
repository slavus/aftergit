package net.aftergit.merge.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

/** Index updates; notifies listeners (EGit staging view) through {@link IndexEvents}. */
public final class GitIndex {
  private GitIndex() {}

  public static void markResolved(Repository repo, String path) throws GitAPIException {
    Git.wrap(repo).add().addFilepattern(path).call();
    IndexEvents.indexChanged(repo, false);
  }
}
