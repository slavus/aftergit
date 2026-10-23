package net.aftergit.merge.git;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Repository;

/** The conflicted files of a selection, a project or a whole repository (spec §8.1). */
public final class ConflictScope {
  /** {@code outside}: conflicted paths of the repositories that no workspace project contains ("repo/path"). */
  public record Scope(List<IFile> files, List<String> outside) {}

  private ConflictScope() {}

  /** The workspace file at {@code location}: in a project, else through a linked folder; null when outside. */
  static IFile fileAt(IWorkspaceRoot root, File location) {
    IFile f = root.getFileForLocation(Path.fromOSString(location.getAbsolutePath()));
    if (f != null) {
      return f;
    }
    for (IFile linked : root.findFilesForLocationURI(location.toURI())) {
      if (linked.getProject().isOpen()) {
        return linked;
      }
    }
    return null;
  }

  /**
   * The conflicted files under {@code resources}; with {@code wholeRepository} all conflicted files of their
   * repositories. Each file once, in repository path order.
   */
  public static Scope collect(Collection<? extends IResource> resources, boolean wholeRepository) throws IOException {
    Set<IFile> files = new LinkedHashSet<>();
    Set<String> outside = new LinkedHashSet<>();
    Set<File> repositories = new HashSet<>();
    IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    for (IResource r : resources) {
      Optional<RepoFile> located = RepositoryLocator.locate(r);
      if (located.isEmpty()) {
        continue;
      }
      Repository repo = located.get().repository();
      if (wholeRepository && !repositories.add(repo.getDirectory())) {
        continue;
      }
      String prefix = wholeRepository ? "" : located.get().path();
      File top = repo.getWorkTree();
      for (String p : ConflictPaths.list(repo)) {
        if (!prefix.isEmpty() && !p.equals(prefix) && !p.startsWith(prefix + "/")) {
          continue;
        }
        IFile f = fileAt(root, new File(top, p));
        if (f == null) {
          outside.add(top.getName() + "/" + p);
        } else {
          files.add(f);
        }
      }
    }
    return new Scope(List.copyOf(files), List.copyOf(outside));
  }
}
