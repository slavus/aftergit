package net.aftergit.merge.git;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jgit.lib.Repository;

/**
 * The repository of a project EGit does not manage, found once per project for checks that run at every selection
 * (the handler of EGit's Merge Tool command). Discovery ({@link RepositoryLocator#locate}) walks and canonicalises
 * paths; here it runs again only after the project was opened, closed, deleted, moved or its description changed.
 * A project found outside any repository is looked at again at once when a {@code .git} appears in it (one check of
 * the file system per lookup) and otherwise every 30 seconds, as a repository made outside Eclipse changes nothing
 * in the workspace.
 * Files are placed by their path below the project's location (links inside a project are not followed).
 */
public final class ProjectRepositories {
  private static final AtomicLong DISCOVERIES = new AtomicLong();
  private static final Map<IProject, Optional<Place>> CACHE = new ConcurrentHashMap<>();
  /** When a project was found outside any repository (a {@link #CACHE} entry that is empty). */
  private static final Map<IProject, Long> NONE_SINCE = new ConcurrentHashMap<>();
  private static volatile boolean listening;

  /** The repository of a project and the project's directory in it ("" at the top, else "dir/"). */
  private record Place(Repository repository, IPath projectLocation, String prefix) {}

  private ProjectRepositories() {}

  /** How long a project found outside any repository stays so before it is looked at again. */
  private static final long RECHECK_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
  private static volatile java.util.function.LongSupplier clock = System::nanoTime;

  /** {@code c} null: the real clock again. */
  public static void setClockForTests(java.util.function.LongSupplier c) {
    clock = c == null ? System::nanoTime : c;
  }

  /** How often a project's repository was discovered so far; for tests. */
  public static long discoveries() {
    return DISCOVERIES.get();
  }

  public static Optional<RepoFile> locate(IFile file) {
    IProject project = file.getProject();
    IPath location = file.getLocation();
    if (project == null || location == null || !project.isOpen()) {
      return Optional.empty();
    }
    listen();
    Optional<Place> place = CACHE.computeIfAbsent(project, ProjectRepositories::discover);
    if (place.isEmpty() && lookAgain(project)) {
      // a git init or clone outside Eclipse changes nothing in the workspace: look again now and then
      CACHE.remove(project);
      place = CACHE.computeIfAbsent(project, ProjectRepositories::discover);
    }
    if (place.isEmpty() || !place.get().projectLocation().isPrefixOf(location)) {
      return Optional.empty();
    }
    String below = location.makeRelativeTo(place.get().projectLocation()).toString();
    return Optional.of(new RepoFile(place.get().repository(), place.get().prefix() + below, location.toFile()));
  }

  private static Optional<Place> discover(IProject project) {
    DISCOVERIES.incrementAndGet();
    IPath location = project.getLocation();
    Optional<Place> found = location == null ? Optional.empty()
        : RepositoryLocator.locate(project).map(rf -> new Place(rf.repository(), location,
            rf.path().isEmpty() ? "" : rf.path() + "/"));
    if (found.isEmpty()) {
      NONE_SINCE.put(project, clock.getAsLong());
    } else {
      NONE_SINCE.remove(project);
    }
    return found;
  }

  /** For a project found outside any repository: a {@code .git} appeared in it, or it was a while ago. */
  private static boolean lookAgain(IProject project) {
    Long since = NONE_SINCE.get(project);
    if (since == null || clock.getAsLong() - since >= RECHECK_NANOS) {
      return true;
    }
    IPath location = project.getLocation();
    return location != null && new java.io.File(location.toFile(), ".git").exists();
  }

  private static void listen() {
    if (listening) {
      return;
    }
    synchronized (ProjectRepositories.class) {
      if (listening) {
        return;
      }
      listening = true;
      ResourcesPlugin.getWorkspace().addResourceChangeListener(ProjectRepositories::changed,
          IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.PRE_DELETE | IResourceChangeEvent.POST_CHANGE);
    }
  }

  private static void changed(IResourceChangeEvent event) {
    if (event.getResource() instanceof IProject p) {
      CACHE.remove(p); // closing or deleting
      NONE_SINCE.remove(p);
    }
    IResourceDelta delta = event.getDelta();
    if (delta == null) {
      return;
    }
    int flags = IResourceDelta.OPEN | IResourceDelta.DESCRIPTION | IResourceDelta.MOVED_FROM | IResourceDelta.MOVED_TO
        | IResourceDelta.LOCAL_CHANGED;
    for (IResourceDelta d : delta.getAffectedChildren()) {
      if (d.getResource().getType() == IResource.PROJECT
          && (d.getKind() != IResourceDelta.CHANGED || (d.getFlags() & flags) != 0)) {
        CACHE.remove((IProject) d.getResource());
        NONE_SINCE.remove((IProject) d.getResource());
      }
    }
  }
}
