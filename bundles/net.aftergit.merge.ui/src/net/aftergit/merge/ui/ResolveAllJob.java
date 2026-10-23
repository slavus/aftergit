package net.aftergit.merge.ui;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.aftergit.merge.git.ConflictScope;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.ResolveAll;
import net.aftergit.merge.git.ResolveAllReport;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Resolve All as a cancellable job with progress (spec §5.5); {@code done} gets the report on the job's thread. The
 * job has no scheduling rule: {@link ResolveAll#run} takes each file's modify rule itself, and an unrelated rule held
 * by the job would make every file fail.
 */
final class ResolveAllJob extends Job {
  private final List<IResource> resources;
  private final boolean wholeRepository;
  private final Supplier<Set<IFile>> busy;
  private final Consumer<ResolveAllReport> done;

  /** {@code busy}: the files others hold, asked again for each file (from the job's thread). */
  ResolveAllJob(List<IResource> resources, boolean wholeRepository, Supplier<Set<IFile>> busy,
      Consumer<ResolveAllReport> done) {
    super(Messages.resolveAllJob);
    setUser(true);
    this.resources = List.copyOf(resources);
    this.wholeRepository = wholeRepository;
    this.busy = busy;
    this.done = done;
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    ConflictScope.Scope scope;
    try {
      scope = ConflictScope.collect(resources, wholeRepository);
    } catch (IOException e) {
      return new Status(IStatus.ERROR, "net.aftergit.merge.ui", Messages.resolveAllListFailed, e);
    }
    Map<IProject, ResolveAll.Options> byProject = new HashMap<>();
    ResolveAllReport report = ResolveAll.run(scope.files(),
        f -> byProject.computeIfAbsent(f.getProject(), p -> options(f)), ResolveAllJob::checks, busy, monitor)
        .withOutside(scope.outside());
    for (FileOutcome f : report.files()) {
      if (f.file() != null && f.result() != FileOutcome.Result.UNTOUCHED) {
        AttentionMarkers.resolvedAll(f.file(), f.conflicts() - f.resolved());
      }
    }
    done.accept(report);
    return report.cancelled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
  }

  /**
   * The compile check of {@code file}: the Java integration for Java files, else the file's language server (languages
   * design, Part 2), else none.
   */
  static JavaChecks checks(IFile file) {
    JavaChecks java = JavaBridge.javaChecks(file);
    return java != null ? java : LspBridge.checks(file);
  }

  /** The options of {@code file}: the settings of its project (preferences design §6). */
  static ResolveAll.Options options(IFile file) {
    IProject p = file.getProject();
    boolean greedy = MergePreferences.greedy(p);
    return new ResolveAll.Options(MergePreferences.policy(p), greedy, ResolverContributions.pipeline(p, greedy));
  }
}
