package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.resolve.ConflictResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * The resolvers contributed through net.aftergit.merge.resolvers (resolvers 6c design), read once, and the pipeline
 * the editor and Resolve All build from the defaults, the contributions and the switches. Without
 * net.aftergit.merge.jdt there is no Java member resolver: nothing contributes it. The registry is read once: a bundle
 * installed or started later, without a restart, is not seen.
 */
final class ResolverContributions {
  static final String POINT = "net.aftergit.merge.resolvers";

  /**
   * A contributed resolver and the label of its switch. Its one instance serves the editor and the Resolve All jobs,
   * so a contributed resolver must be stateless and thread-safe.
   */
  record Contribution(String id, String name, ConflictResolver resolver) {}

  private static volatile List<Contribution> cached;
  private static volatile List<Contribution> forTests;

  private ResolverContributions() {}

  /** Replaces the contributions (null: the registry's). */
  static void setForTests(List<Contribution> contributions) {
    forTests = contributions == null ? null : List.copyOf(contributions);
  }

  static List<Contribution> all() {
    List<Contribution> t = forTests;
    if (t != null) {
      return t;
    }
    List<Contribution> c = cached;
    if (c == null) {
      synchronized (ResolverContributions.class) { // the editor and a Resolve All job may ask first at once
        c = cached;
        if (c == null) {
          c = read(Platform.getExtensionRegistry().getConfigurationElementsFor(POINT));
          cached = c;
        }
      }
    }
    return c;
  }

  /**
   * The contributions of {@code elements}, in order. One whose class cannot be created, is no ConflictResolver, has an
   * id other than its element's, or an id that is built in or already taken is logged and left out.
   */
  static List<Contribution> read(IConfigurationElement[] elements) {
    List<Contribution> out = new ArrayList<>();
    Set<String> ids = new HashSet<>(ResolverPipeline.BUILT_IN_IDS);
    for (IConfigurationElement e : elements) {
      if (!"resolver".equals(e.getName())) {
        continue;
      }
      String id = e.getAttribute("id");
      ConflictResolver r;
      String ownId;
      try {
        Object created = e.createExecutableExtension("class");
        r = created instanceof ConflictResolver cr ? cr : null;
        ownId = r == null ? null : r.id();
      } catch (CoreException | RuntimeException | LinkageError | StackOverflowError ex) {
        // the registry wraps only exceptions: an Error of the constructor, or of id(), arrives here as it is
        log("The resolver " + id + " of " + e.getNamespaceIdentifier() + " could not be created; it is left out", ex);
        continue;
      }
      if (r == null || id == null || !id.equals(ownId) || !ids.add(id)) {
        log("The resolver " + id + " of " + e.getNamespaceIdentifier()
            + " is no ConflictResolver with that id, or the id is taken; it is left out", null);
        continue;
      }
      String name = e.getAttribute("name");
      out.add(new Contribution(id, name == null || name.isBlank() ? id : name, r));
    }
    return List.copyOf(out);
  }

  /** The pipeline of {@code project}'s files with its greedy setting (null: the workspace's). */
  static ResolverPipeline pipeline(IProject project) {
    return pipeline(project, MergePreferences.greedy(project));
  }

  /**
   * The defaults and the contributions, each behind its switch for {@code project} (null: the workspace's); greedy as
   * asked. A contribution's switch is found by the id read once by {@link #read}, so a resolver whose id() fails
   * later cannot break the editor or Resolve All.
   */
  static ResolverPipeline pipeline(IProject project, boolean greedy) {
    return ResolverPipeline.defaults(greedy, id -> MergePreferences.resolverEnabled(project, id),
        all().stream().filter(c -> MergePreferences.resolverEnabled(project, c.id())).map(Contribution::resolver)
            .toList());
  }

  private static void log(String message, Throwable e) {
    ILog.of(ResolverContributions.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", message,
        e == null ? null : JavaBridge.loggable(e)));
  }
}
