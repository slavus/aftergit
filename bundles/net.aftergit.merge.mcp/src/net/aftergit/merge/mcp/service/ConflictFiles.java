package net.aftergit.merge.mcp.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictKinds;
import net.aftergit.merge.git.ConflictScope;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;

/**
 * The files agents may see (agents design §2): the conflicted files of the repositories that have open projects in
 * the workspace, as far as they lie in an open project. Every other path is "not a conflicted file".
 */
public final class ConflictFiles {
  public static final String NOT_CONFLICTED =
      "\"%s\" is not a conflicted file of the workspace; list_conflicts shows the files there are";

  /** A visible conflicted file. */
  public record ConflictFile(IFile file, RepoFile repoFile, ConflictKind kind) {
    public String absolutePath() {
      return repoFile.file().getAbsolutePath();
    }

    public String repositoryName() {
      return repoFile.repository().getWorkTree().getName();
    }

    public String repositoryPath() {
      return repoFile.repository().getWorkTree().getAbsolutePath();
    }

    /** {@code repository} is this file's repository by name or by work-tree path. */
    public boolean inRepository(String repository) {
      if (repository.equals(repositoryName())) {
        return true;
      }
      try {
        Path p = Path.of(repository);
        return p.isAbsolute() && canonical(p).equals(canonical(Path.of(repositoryPath())));
      } catch (InvalidPathException e) {
        return false;
      }
    }
  }

  private ConflictFiles() {}

  /**
   * The index of each repository, read once per tool call: classifying and fingerprinting many files must not
   * parse the whole index again for every one of them.
   */
  static final class Indexes {
    private final Map<File, DirCache> caches = new HashMap<>();

    DirCache of(Repository repo) throws IOException {
      DirCache c = caches.get(repo.getDirectory());
      if (c == null) {
        c = repo.readDirCache();
        caches.put(repo.getDirectory(), c);
      }
      return c;
    }
  }

  /** A conflicted path of a workspace repository that lies in an open project; not classified yet. */
  private record Candidate(IFile file, RepoFile repoFile) {}

  public static List<ConflictFile> all() throws AgentException {
    return all(new Indexes());
  }

  static List<ConflictFile> all(Indexes indexes) throws AgentException {
    List<ConflictFile> out = new ArrayList<>();
    for (Candidate c : candidates()) {
      ConflictFile f = classified(c, indexes);
      if (f != null) {
        out.add(f);
      }
    }
    return out;
  }

  /** The visible file at {@code path}: absolute, or relative to the top of its repository. */
  public static ConflictFile resolve(String path) throws AgentException {
    if (path == null || path.isBlank()) {
      throw new AgentException(String.format(NOT_CONFLICTED, path));
    }
    Path asPath;
    try {
      asPath = Path.of(path);
    } catch (InvalidPathException e) {
      throw new AgentException(String.format(NOT_CONFLICTED, path));
    }
    List<Candidate> hits = new ArrayList<>();
    if (asPath.isAbsolute()) {
      Path wanted = canonical(asPath);
      Path name = wanted.getFileName();
      for (Candidate c : candidates()) {
        File file = c.repoFile().file();
        // the name first: only a likely match costs a look at the file system
        if (name != null && file.getName().equals(name.toString()) && canonical(file.toPath()).equals(wanted)) {
          hits.add(c);
          break;
        }
      }
    } else {
      String rel = path.replace('\\', '/');
      rel = rel.startsWith("./") ? rel.substring(2) : rel;
      for (Candidate c : candidates()) {
        if (c.repoFile().path().equals(rel)) {
          hits.add(c); // one per repository at most; the others are still looked for to refuse an ambiguous path
        }
      }
    }
    Indexes indexes = new Indexes();
    List<ConflictFile> files = new ArrayList<>();
    for (Candidate c : hits) {
      ConflictFile f = classified(c, indexes);
      if (f != null) {
        files.add(f);
      }
    }
    if (files.isEmpty()) {
      throw new AgentException(String.format(NOT_CONFLICTED, path));
    }
    Set<String> repositories = new LinkedHashSet<>();
    for (ConflictFile f : files) {
      repositories.add(f.repositoryPath());
    }
    if (repositories.size() > 1) {
      throw new AgentException("\"" + path + "\" is conflicted in several repositories " + repositories
          + "; use the absolute path from list_conflicts");
    }
    return files.get(0);
  }

  /** The conflicted paths of the repositories of open projects, as far as they lie in an open project. */
  private static List<Candidate> candidates() throws AgentException {
    List<IProject> open = new ArrayList<>();
    for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
      if (p.isOpen()) {
        open.add(p);
      }
    }
    List<Candidate> out = new ArrayList<>();
    try {
      for (IFile f : ConflictScope.collect(open, true).files()) {
        // a path of the repository may lie in a closed project of it: that one is not in the workspace
        if (!f.getProject().isOpen()) {
          continue;
        }
        RepoFile rf = RepositoryLocator.locate(f).orElse(null);
        if (rf != null) {
          out.add(new Candidate(f, rf));
        }
      }
    } catch (IOException e) {
      throw new AgentException("The conflicts of the workspace could not be listed: " + e.getMessage());
    }
    return out;
  }

  private static ConflictFile classified(Candidate c, Indexes indexes) throws AgentException {
    try {
      Repository repo = c.repoFile().repository();
      ConflictKind kind = ConflictKinds.classify(repo, indexes.of(repo), c.repoFile().path());
      return kind == ConflictKind.NONE ? null : new ConflictFile(c.file(), c.repoFile(), kind);
    } catch (IOException e) {
      throw new AgentException("The conflicts of the workspace could not be listed: " + e.getMessage());
    }
  }

  static Path canonical(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException | SecurityException e) {
      return p.toAbsolutePath().normalize();
    }
  }
}
