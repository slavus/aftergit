package net.aftergit.merge.git;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;

/**
 * Finds the git repository of a workspace resource: EGit's mapping first, plain JGit discovery otherwise.
 * The returned repository is shared (EGit's or JGit's {@code RepositoryCache}); callers must NOT close it.
 */
public final class RepositoryLocator {
  private RepositoryLocator() {}

  /**
   * The file in its repository as EGit maps it (a project shared with EGit), without discovering a repository
   * otherwise; cheap, as it reads no file. EGit's own actions (such as its Merge Tool) work only on such files.
   */
  public static Optional<RepoFile> locateMapped(IResource resource) {
    IPath location = resource.getLocation();
    RepositoryMapping mapping = location == null ? null : RepositoryMapping.getMapping(resource);
    if (mapping == null || mapping.getRepository() == null) {
      return Optional.empty();
    }
    String path = mapping.getRepoRelativePath(resource);
    return path == null ? Optional.empty() : Optional.of(new RepoFile(mapping.getRepository(), path, location.toFile()));
  }

  public static Optional<RepoFile> locate(IResource resource) {
    IPath location = resource.getLocation();
    if (location == null) {
      return Optional.empty();
    }
    File file = location.toFile();
    RepositoryMapping mapping = RepositoryMapping.getMapping(resource);
    if (mapping != null && mapping.getRepository() != null) {
      String path = mapping.getRepoRelativePath(resource);
      if (path != null) {
        return Optional.of(new RepoFile(mapping.getRepository(), path, file));
      }
    }
    try {
      // a project or folder may be the top of the work tree itself: search from it, not from its parent
      boolean isFile = resource.getType() == IResource.FILE;
      File parent = isFile ? file.getParentFile() : file;
      if (parent == null) {
        return Optional.empty();
      }
      File gitDir = new FileRepositoryBuilder().findGitDir(parent).getGitDir();
      if (gitDir == null) {
        return Optional.empty();
      }
      Repository repo = RepositoryCache.open(RepositoryCache.FileKey.lenient(gitDir, FS.DETECTED), true);
      File top = repo.getWorkTree().getCanonicalFile();
      File canonical = isFile ? new File(parent.getCanonicalFile(), file.getName()) : file.getCanonicalFile();
      String path = top.toPath().relativize(canonical.toPath()).toString().replace('\\', '/');
      if (path.equals("..") || path.startsWith("../")) {
        return Optional.empty();
      }
      return Optional.of(new RepoFile(repo, path, file));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
