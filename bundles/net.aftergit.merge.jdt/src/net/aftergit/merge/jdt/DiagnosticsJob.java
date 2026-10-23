package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/**
 * Errors of the degraded result (spec §7.2): {@value #DELAY} ms after the last change the document is
 * reconciled in a working copy of an own owner, and its problems replace the annotations shown. UI updates go
 * through asyncExec; a result for an older text is dropped (spec §7.4). Created and disposed in the UI thread.
 */
final class DiagnosticsJob extends Job {
  static final long DELAY = 500;
  static final String ERROR_TYPE = "org.eclipse.jdt.ui.error";
  static final String WARNING_TYPE = "org.eclipse.jdt.ui.warning";

  private final IDocument document;
  private final IAnnotationModel annotations;
  private final Display display;
  private final List<IProblem> collected = new ArrayList<>();
  private final IProblemRequestor requestor = new IProblemRequestor() {
    @Override
    public void acceptProblem(IProblem problem) {
      if (problem.isError() || problem.isWarning()) {
        collected.add(problem);
      }
    }

    @Override
    public void beginReporting() {
      collected.clear();
    }

    @Override
    public void endReporting() {
      // collected holds the whole report
    }

    @Override
    public boolean isActive() {
      return true;
    }
  };
  private final WorkingCopyOwner owner = new WorkingCopyOwner() {
    @Override
    public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
      return requestor;
    }
  };
  private final ICompilationUnit workingCopy;
  private final IDocumentListener listener = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {}

    @Override
    public void documentChanged(DocumentEvent event) {
      // a waiting job ignores schedule(): cancel first so the delay counts from the last change
      cancel();
      schedule(DELAY);
    }
  };
  private Annotation[] shown = new Annotation[0];
  private volatile boolean disposed;
  private boolean discarded;

  private DiagnosticsJob(ICompilationUnit unit, IDocument document, IAnnotationModel annotations, Display display)
      throws JavaModelException {
    super("Java errors in the merge result");
    setSystem(true);
    this.document = document;
    this.annotations = annotations;
    this.display = display;
    this.workingCopy = unit.getWorkingCopy(owner, null);
  }

  /** Diagnostics for {@code file}, or null when the Java model has no compilation unit for it
   * (no Java project, or the file is not on its classpath). */
  static DiagnosticsJob create(IFile file, IDocument document, IAnnotationModel annotations, Display display)
      throws JavaModelException {
    ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
    return !SideAnalysis.hasJavaModel(unit) ? null : new DiagnosticsJob(unit, document, annotations, display);
  }

  void start() {
    document.addDocumentListener(listener);
    schedule(0);
  }

  ICompilationUnit workingCopy() {
    return workingCopy;
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    if (disposed || display.isDisposed()) {
      return Status.CANCEL_STATUS;
    }
    String[] text = new String[1];
    long[] stamp = new long[1];
    try {
      display.syncExec(() -> {
        if (!disposed) {
          text[0] = document.get();
          stamp[0] = stamp(document);
        }
      });
    } catch (SWTException e) {
      return Status.CANCEL_STATUS; // the display went away
    }
    if (text[0] == null || stamp[0] == IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP || monitor.isCanceled()) {
      return Status.CANCEL_STATUS; // without a stamp a result could not be told from a stale one
    }
    try {
      workingCopy.getBuffer().setContents(text[0]);
      workingCopy.reconcile(ICompilationUnit.NO_AST, true, owner, monitor);
    } catch (JavaModelException | RuntimeException e) {
      if (disposed || monitor.isCanceled()) {
        return Status.CANCEL_STATUS;
      }
      ILog.of(DiagnosticsJob.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "Reconciling the merge result failed", e));
      return Status.OK_STATUS; // logged once here; the job's status would only be logged again
    }
    if (disposed || monitor.isCanceled()) {
      return Status.CANCEL_STATUS;
    }
    List<IProblem> found = List.copyOf(collected);
    try {
      display.asyncExec(() -> {
        if (!disposed && stamp(document) == stamp[0]) {
          show(found);
        }
      });
    } catch (SWTException e) {
      return Status.CANCEL_STATUS;
    }
    return Status.OK_STATUS;
  }

  private void show(List<IProblem> problems) {
    Map<Annotation, Position> add = new HashMap<>();
    int length = document.getLength();
    for (IProblem p : problems) {
      int start = Math.max(0, Math.min(p.getSourceStart(), length));
      int end = Math.max(start, Math.min(p.getSourceEnd() + 1, length));
      add.put(new Annotation(p.isError() ? ERROR_TYPE : WARNING_TYPE, false, p.getMessage()),
          new Position(start, end - start));
    }
    Annotation[] old = shown;
    shown = add.keySet().toArray(Annotation[]::new);
    if (annotations instanceof IAnnotationModelExtension ext) {
      ext.replaceAnnotations(old, add);
    } else {
      for (Annotation a : old) {
        annotations.removeAnnotation(a);
      }
      add.forEach(annotations::addAnnotation);
    }
  }

  /** Stops, removes the annotations shown (UI thread) and discards the working copy once no run is active. */
  void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    document.removeDocumentListener(listener);
    show(List.of());
    addJobChangeListener(new JobChangeAdapter() {
      @Override
      public void done(IJobChangeEvent event) {
        discard();
      }
    });
    if (cancel() || getState() == NONE) {
      discard(); // not running: no done event will come for a run
    }
  }

  private synchronized void discard() {
    if (discarded) {
      return;
    }
    discarded = true;
    try {
      workingCopy.discardWorkingCopy();
    } catch (JavaModelException e) {
      ILog.of(DiagnosticsJob.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "Could not discard the merge result's working copy", e));
    }
  }

  private static long stamp(IDocument d) {
    return d instanceof IDocumentExtension4 e ? e.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
  }
}
