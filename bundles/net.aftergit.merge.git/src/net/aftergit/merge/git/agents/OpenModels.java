package net.aftergit.merge.git.agents;

import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.JavaChecks;
import org.eclipse.core.resources.IFile;

/** OSGi service of the resolver: its open files and the settings a model of a closed file is built with. */
public interface OpenModels {
  /** The file's model when it is open in AfterGit. */
  Optional<OpenModel> find(IFile file);

  /** The Java checks of the file (import sections, compile errors), or null (not Java, no JDT). */
  JavaChecks javaChecks(IFile file);

  /** The resolver's whitespace setting. */
  ComparisonPolicy policy();

  /** Whether the resolver's greedy pass is on. */
  boolean greedy();

  /**
   * The resolvers the resolver runs, with its switches and the contributed resolvers (resolvers 6c design), not yet
   * bound to a file; by default the built-in ones with each switch as by default and greedy as
   * {@link #greedy}.
   */
  default ResolverPipeline pipeline() {
    return ResolverPipeline.defaults(greedy());
  }

  /** The whitespace setting for {@code file}: its project's while the project has its own settings. */
  default ComparisonPolicy policy(IFile file) {
    return policy();
  }

  /** The resolvers for {@code file}, with the switches of its project; not yet bound to the file name. */
  default ResolverPipeline pipeline(IFile file) {
    return pipeline();
  }
}
