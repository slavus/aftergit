package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;

/**
 * Compile errors of ours and of theirs against the project's real classpath (spec §5.4): reconciled once, in the
 * background, each in a working copy of the file in an owner of its own.
 */
public final class SideAnalysis extends Job {
  /** Family of the analysis jobs, for {@code Job.getJobManager().join}. */
  public static final Object FAMILY = new Object();

  private final IFile file;
  private final String left;
  private final String right;
  private final Runnable done;
  private volatile List<LineProblem> leftErrors;
  private volatile List<LineProblem> rightErrors;

  private SideAnalysis(IFile file, String left, String right, Runnable done) {
    super("Compile errors of ours and theirs");
    setSystem(true);
    this.file = file;
    this.left = left;
    this.right = right;
    this.done = done;
  }

  /**
   * Starts the analysis; {@code done} runs on the job's thread once both sides are known, possibly even right
   * after {@link #cancel()}. A failed or cancelled analysis leaves both lists null and never runs {@code done}.
   */
  public static SideAnalysis start(IFile file, String left, String right, Runnable done) {
    SideAnalysis a = new SideAnalysis(file, left, right, done);
    a.schedule();
    return a;
  }

  /** Errors of ours by model line, or null while unknown (also for good after a failure). */
  public List<LineProblem> leftErrors() {
    return leftErrors;
  }

  /** Errors of theirs by model line, or null while unknown. */
  public List<LineProblem> rightErrors() {
    return rightErrors;
  }

  @Override
  public boolean belongsTo(Object family) {
    return family == FAMILY;
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    try {
      List<LineProblem> l = errors(file, left, monitor);
      List<LineProblem> r = l == null || monitor.isCanceled() ? null : errors(file, right, monitor);
      if (l == null || r == null || monitor.isCanceled()) {
        return Status.CANCEL_STATUS; // no Java model for the file, or the resolver closed: no marks
      }
      leftErrors = l;
      rightErrors = r;
    } catch (JavaModelException | RuntimeException e) {
      if (!monitor.isCanceled()) {
        ILog.of(SideAnalysis.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID, "Ours and theirs of "
            + file.getFullPath() + " could not be compiled; auto-resolved blocks are not checked", e));
      }
      return Status.OK_STATUS; // both stay null for good: no marks
    }
    try {
      done.run(); // outside the compile's try: a failing listener is not a failed compile
    } catch (RuntimeException e) {
      ILog.of(SideAnalysis.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "A listener of the analysis of ours and theirs failed", e));
    }
    return Status.OK_STATUS;
  }

  /** Errors of {@code text} as the content of {@code file}, by model line; null without a Java model for it (no Java
   * project, or the file is not on its classpath). */
  public static List<LineProblem> errors(IFile file, String text, IProgressMonitor monitor) throws JavaModelException {
    ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
    if (!hasJavaModel(unit)) {
      return null;
    }
    List<IProblem> found = new ArrayList<>();
    IProblemRequestor requestor = new IProblemRequestor() {
      @Override
      public void acceptProblem(IProblem problem) {
        if (problem.isError()) {
          found.add(problem);
        }
      }

      @Override
      public void beginReporting() {
        found.clear();
      }

      @Override
      public void endReporting() {
        // found holds the whole report
      }

      @Override
      public boolean isActive() {
        return true;
      }
    };
    WorkingCopyOwner owner = new WorkingCopyOwner() {
      @Override
      public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
        return requestor;
      }
    };
    ICompilationUnit copy = unit.getWorkingCopy(owner, monitor);
    try {
      copy.getBuffer().setContents(text);
      copy.reconcile(ICompilationUnit.NO_AST, true, owner, monitor);
    } finally {
      copy.discardWorkingCopy();
    }
    List<LineProblem> out = new ArrayList<>();
    for (IProblem p : found) {
      out.add(new LineProblem(p.getSourceLineNumber() - 1, p.getMessage()));
    }
    return List.copyOf(out);
  }

  /** Whether {@code unit} is in an existing Java project that has it on its classpath (else reconciling throws). */
  static boolean hasJavaModel(ICompilationUnit unit) {
    if (unit == null) {
      return false;
    }
    IJavaProject project = unit.getJavaProject();
    return project != null && project.exists() && project.isOnClasspath(unit);
  }
}
