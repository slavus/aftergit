package net.aftergit.merge.lsp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.core.check.LineProblem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/**
 * The result's errors as AfterGit's own server instances find them (languages design, Part 2), for "new errors" in
 * the editor: the result is checked as ours and theirs are, as a virtual document next to the file, so that the
 * three are compared alike, as Resolve All compares them. A server that reads the file's name or that lsp4e
 * configures for the user would otherwise report errors in the result (lsp4e's instance, the real name) that the
 * sides (AfterGit's instance, another name) never get. lsp4e's markers stay what the result shows (squiggles, ruler,
 * Problems view, the count of errors). The result is checked again {@value #DEBOUNCE_MILLIS} ms after the last
 * change. Created, used and disposed on the UI thread; the checks run in a background job.
 */
@SuppressWarnings("restriction") // LanguageServerDefinition: org.eclipse.lsp4e names its friends only
public final class ResultChecks {
  /** The wait after a change before the result is checked again. */
  public static final long DEBOUNCE_MILLIS = 300;

  private final IFile file;
  private final IDocument document;
  private final Display display;
  private final SideChecks sides;
  /** The errors found, at places that follow the edits since; connected to the document. */
  private final AnnotationModel found = new AnnotationModel();
  /** The server of each annotation of {@link #found}; replaced as a whole on the UI thread. */
  private Map<Annotation, String> servers = Map.of();
  /** Servers that did not check the result: their errors in the result are lsp4e's. */
  private final Set<String> rejected = ConcurrentHashMap.newKeySet();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  /** Changes of the document; one more at the start, which the first check settles. */
  private final AtomicLong changes = new AtomicLong(1);
  /** The count of changes of the text the shown errors were found in; 0 before the first check. */
  private long shown;
  /** True while the result is longer than Part 1's limit: then it is not checked, and nothing is known. */
  private boolean tooLarge;
  private boolean disposed;
  private final Runnable capture = this::capture;
  /** The text the next run of {@link #job} checks, with its count of changes; null when there is none. */
  private final AtomicReference<Snapshot> next = new AtomicReference<>();
  /**
   * The one job of this resolver's checks: a change cancels a run that goes on and schedules it again, which the job
   * manager runs once that run is over, so checks never pile up.
   */
  private final Job job = new Job("Errors of the merge result") {
    @Override
    protected IStatus run(IProgressMonitor monitor) {
      Snapshot s = next.getAndSet(null);
      return s == null || disposed ? Status.OK_STATUS : check(s.at(), s.text(), monitor);
    }

    @Override
    public boolean belongsTo(Object family) {
      return family == SideChecks.FAMILY; // the checks of the editor, ours, theirs and the result
    }
  };

  private record Snapshot(long at, String text) {}

  private final IDocumentListener documentListener = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {}

    @Override
    public void documentChanged(DocumentEvent event) {
      changes.incrementAndGet();
      if (Display.getCurrent() == display) {
        display.timerExec((int) DEBOUNCE_MILLIS, capture); // again for each change: the timer starts over
      } else if (!display.isDisposed()) {
        try {
          display.asyncExec(() -> {
            if (!disposed) {
              display.timerExec((int) DEBOUNCE_MILLIS, capture);
            }
          });
        } catch (SWTException e) {
          // the display went away
        }
      }
    }
  };

  private ResultChecks(IFile file, IDocument document, Display display, SideChecks sides) {
    this.file = file;
    this.document = document;
    this.display = display;
    this.sides = sides;
    job.setSystem(true);
  }

  /**
   * Checks {@code document} (the result, the file's shared buffer) now and after each change, until disposed; UI
   * thread, once the result text is in. {@code sides} names the servers that rejected ours and theirs: those are
   * not asked.
   */
  public static ResultChecks start(IFile file, IDocument document, Display display, SideChecks sides) {
    ResultChecks r = new ResultChecks(file, document, display, sides);
    r.found.connect(document);
    document.addDocumentListener(r.documentListener);
    r.capture();
    return r;
  }

  /**
   * UI thread: the text as it is now goes to the next check; one that still runs is cancelled. A result above Part
   * 1's size limit is not checked: "new errors" then compare the errors the result shows ({@link #skipped}).
   */
  private void capture() {
    if (disposed) {
      return;
    }
    long at = changes.get();
    boolean large = document.getLength() > TextMatePanes.maxChars();
    if (large) {
      next.set(null);
      job.cancel();
      if (!tooLarge) {
        tooLarge = true;
        found.replaceAnnotations(servers.keySet().toArray(Annotation[]::new), Map.of());
        servers = Map.of();
        listeners.forEach(Runnable::run);
      }
      return;
    }
    boolean wasLarge = tooLarge;
    tooLarge = false;
    if (wasLarge) {
      shown = 0; // nothing is known of the text until it is checked
      listeners.forEach(Runnable::run);
    }
    next.set(new Snapshot(at, document.get()));
    job.cancel(); // a run that goes on ends soon (its waits look at the monitor), then the job runs once more
    job.schedule();
  }

  /** True while the result is too large to be checked here: "new errors" use the errors the result shows. */
  public boolean skipped() {
    return tooLarge;
  }

  /** On the job's thread: {@code text} checked by each server of the file that has not rejected it. */
  private IStatus check(long at, String text, IProgressMonitor monitor) {
    Map<String, List<LineProblem>> byServer = new LinkedHashMap<>();
    List<LanguageServerDefinition> asked = new ArrayList<>();
    for (LanguageServerDefinition d : Servers.definitionsFor(file)) {
      if (!rejected.contains(d.id) && (sides == null || !sides.rejectedServers().contains(d.id))) {
        asked.add(d);
      }
    }
    List<SideChecks.Outcome> outcomes;
    try {
      outcomes = SideChecks.checkAll(file, asked, List.of(text), List.of("check"), false, monitor);
    } catch (OperationCanceledException e) {
      return Status.CANCEL_STATUS;
    }
    if (monitor.isCanceled()) {
      return Status.CANCEL_STATUS;
    }
    for (SideChecks.Outcome o : outcomes) {
      if (o.failure() != null) {
        ILog.of(ResultChecks.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The merge result of "
            + file.getFullPath() + " could not be checked by " + o.definition().label + "; its new errors are "
            + "shown as errors", Grammars.loggable(o.failure())));
      }
      if (o.found() == null) {
        rejected.add(o.definition().id);
      } else {
        byServer.put(o.definition().id, o.found().get(0));
      }
    }
    if (display.isDisposed()) {
      return Status.OK_STATUS;
    }
    try {
      display.asyncExec(() -> show(at, byServer));
    } catch (SWTException e) {
      // the display went away
    }
    return Status.OK_STATUS;
  }

  /** UI thread: the errors found in the text of change {@code at}, placed in the document as it is now. */
  private void show(long at, Map<String, List<LineProblem>> byServer) {
    if (disposed || tooLarge || at < shown) {
      return;
    }
    Map<Annotation, Position> add = new HashMap<>();
    Map<Annotation, String> ids = new HashMap<>();
    int lines = document.getNumberOfLines();
    byServer.forEach((id, errors) -> {
      for (LineProblem p : errors) {
        if (p.line() < 0 || p.line() >= lines) {
          continue; // a line the result no longer has: a newer check follows
        }
        try {
          IRegion r = document.getLineInformation(p.line());
          Annotation a = new Annotation(ResultDiagnostics.ERROR_TYPE, false, p.message());
          add.put(a, new Position(r.getOffset(), r.getLength()));
          ids.put(a, id);
        } catch (BadLocationException e) {
          // the same: a newer check follows
        }
      }
    });
    found.replaceAnnotations(servers.keySet().toArray(Annotation[]::new), add);
    servers = Map.copyOf(ids);
    shown = at;
    listeners.forEach(Runnable::run);
  }

  /**
   * The result's errors by model line of the servers not in {@code excluded}, at their places now; null until the
   * first check is over (UI thread).
   */
  public List<LineProblem> errors(Collection<String> excluded) {
    if (shown == 0 || tooLarge) {
      return null;
    }
    List<LineProblem> out = new ArrayList<>();
    servers.forEach((a, id) -> {
      Position p = found.getPosition(a);
      if (excluded.contains(id) || p == null || p.isDeleted()) {
        return;
      }
      try {
        out.add(new LineProblem(document.getLineOfOffset(Math.min(p.getOffset(), document.getLength())),
            a.getText()));
      } catch (BadLocationException e) {
        // gone with an edit
      }
    });
    out.sort((x, y) -> Integer.compare(x.line(), y.line()));
    return out;
  }

  /** The lsp4e ids of the servers that did not check the result. */
  public Set<String> rejectedServers() {
    return Set.copyOf(rejected);
  }

  /** True when the errors shown were found in the current text (UI thread). */
  public boolean settled() {
    return disposed || tooLarge || shown >= changes.get();
  }

  /** Called on the UI thread when {@link #errors} may have changed. */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  /** Stops checking (UI thread); a check that runs is cancelled. */
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    listeners.clear();
    try {
      document.removeDocumentListener(documentListener);
      if (!display.isDisposed()) {
        display.timerExec(-1, capture);
      }
      next.set(null);
      job.cancel();
    } finally {
      found.disconnect(document);
    }
  }
}
