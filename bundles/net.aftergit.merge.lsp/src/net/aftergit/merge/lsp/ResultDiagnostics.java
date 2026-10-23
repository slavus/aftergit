package net.aftergit.merge.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/**
 * The language server's errors in the result (languages design, Part 2). The result is the file's shared buffer,
 * which lsp4e connects to the file's servers like any buffer of the file; their diagnostics come back as lsp4e's
 * markers on the file, and these become the annotations of the result viewer (squiggles, ruler, Next/Previous
 * Error). Created, used and disposed on the UI thread; listeners run there.
 */
@SuppressWarnings("restriction") // LanguageServers is exported to lsp4e's friends only
public final class ResultDiagnostics {
  /** The annotation types of the result: the platform's problem types, which every editor shows. */
  public static final String ERROR_TYPE = "org.eclipse.ui.workbench.texteditor.error";
  public static final String WARNING_TYPE = "org.eclipse.ui.workbench.texteditor.warning";
  public static final String INFO_TYPE = "org.eclipse.ui.workbench.texteditor.info";
  /*
   * The constants of lsp4e's LSPDiagnosticsToMarkers (checked with javap); its package is x-internal, so they are
   * copied: the marker attributes with the id of the server and its own diagnostic, and the type of its markers.
   */
  static final String SERVER_ID = "languageServerId";
  static final String DIAGNOSTIC = "lspDiagnostic";
  static final String MARKER_TYPE = "org.eclipse.lsp4e.diagnostic";
  /** The job lsp4e runs for each diagnostics report of a workspace file (LSPDiagnosticsToMarkers$2, javap). */
  static final String MARKER_JOB = "org.eclipse.lsp4e.operations.diagnostics.LSPDiagnosticsToMarkers";
  /** The field of that job with the file of the report (javap: {@code private final IResource val$resource}). */
  static final String MARKER_JOB_RESOURCE = "val$resource";
  private static final AtomicBoolean jobFileFailureLogged = new AtomicBoolean();
  private static volatile java.lang.reflect.Field jobFile;

  private final IFile file;
  private final IDocument document;
  private final IAnnotationModel annotations;
  private final Display display;
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  /** The annotations shown, each with whether it is an error and its server; replaced as a whole on the UI thread. */
  private volatile Map<Annotation, Kind> shown = Map.of();
  /** Changes of the document; one more at the start, which the first answer settles. */
  private final AtomicLong changes = new AtomicLong(1);
  /** The count of changes when a server last answered. */
  private final AtomicLong answered = new AtomicLong();
  /** The count of {@link #answered} that the annotations show (set by {@link #update()} on the UI thread). */
  private final AtomicLong answerShown = new AtomicLong();
  private final AtomicBoolean updatePosted = new AtomicBoolean();
  private volatile boolean disposed;

  private final IDocumentListener documentListener = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {}

    @Override
    public void documentChanged(DocumentEvent event) {
      changes.incrementAndGet();
    }
  };

  private final IResourceChangeListener markerListener = this::resourcesChanged;

  private final IJobChangeListener jobListener = new JobChangeAdapter() {
    @Override
    public void done(IJobChangeEvent event) {
      // lsp4e runs this job for every report, even one that changes no marker (the same errors after an edit);
      // only a report of this file is an answer
      Job job = event.getJob();
      if (job.getClass().getName().startsWith(MARKER_JOB) && reportsOn(job, file)) {
        answer();
      }
    }
  };

  private record Kind(boolean error, String serverId) {}

  /**
   * True when lsp4e's marker job {@code job} reports on {@code file}, read from its field; when the field cannot be
   * read (another lsp4e), every report counts, as if it were this file's (logged once).
   */
  static boolean reportsOn(Job job, IFile file) {
    try {
      java.lang.reflect.Field f = jobFile;
      if (f == null || f.getDeclaringClass() != job.getClass()) {
        f = job.getClass().getDeclaredField(MARKER_JOB_RESOURCE);
        f.setAccessible(true);
        jobFile = f;
      }
      Object resource = f.get(job);
      return resource == null || file.equals(resource);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      if (jobFileFailureLogged.compareAndSet(false, true)) {
        ILog.of(ResultDiagnostics.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The file of a "
            + "diagnostics report of lsp4e could not be read; a report of any file counts as the answer to the "
            + "merge result", Grammars.loggable(e)));
      }
      return true;
    }
  }

  private ResultDiagnostics(IFile file, IDocument document, IAnnotationModel annotations, Display display) {
    this.file = file;
    this.document = document;
    this.annotations = annotations;
    this.display = display;
  }

  /**
   * Shows the errors of the file's language servers for {@code document} (the file's shared buffer) in
   * {@code annotations}, which must be the result viewer's annotation model. Connect before the result text goes
   * into the document, so that no answer to it is missed, and call {@link #connectServers} once it is in. Until a
   * server answers, nothing is shown: the markers the file has now were made for the text before (an editor's),
   * unless {@link #showMarkers} says otherwise.
   */
  public static ResultDiagnostics connect(IFile file, IDocument document, IAnnotationModel annotations,
      Display display) {
    ResultDiagnostics r = new ResultDiagnostics(file, document, annotations, display);
    try {
      document.addDocumentListener(r.documentListener);
      ResourcesPlugin.getWorkspace().addResourceChangeListener(r.markerListener, IResourceChangeEvent.POST_CHANGE);
      Job.getJobManager().addJobChangeListener(r.jobListener);
    } catch (RuntimeException | Error e) {
      r.dispose(); // never a listener left behind that keeps the shared document reachable
      throw e;
    }
    return r;
  }

  /**
   * Shows lsp4e's markers of the file as they are, as the answer to the current text: for a document whose text the
   * result did not change. The shared buffer then holds the file's saved text, which those markers were made for, so
   * nothing is pending until the next change (UI thread).
   */
  public void showMarkers() {
    if (!disposed) {
      answered.accumulateAndGet(changes.get(), Math::max);
      update();
    }
  }

  /**
   * Connects the document to the file's language servers, after the result text went in (UI thread). lsp4e reads
   * the text and adds its listener on another thread: a change in between never reached a running server, which
   * then kept the text before the result, and its errors, until the next edit.
   */
  public void connectServers() {
    if (disposed) {
      return;
    }
    try {
      // lsp4e connects every new file buffer itself (its document setup participant); this also connects a buffer
      // that an editor had opened before the server was there, and starts nothing twice
      LanguageServers.forDocument(document).collectAll(server -> CompletableFuture.completedFuture(null));
    } catch (RuntimeException | LinkageError e) {
      ILog.of(ResultDiagnostics.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The merge result of "
          + file.getFullPath() + " could not be connected to its language server", Grammars.loggable(e)));
    }
  }

  /** On any thread: a change of lsp4e's markers of the file is an answer of a server; other markers are not. */
  private void resourcesChanged(IResourceChangeEvent event) {
    IResourceDelta root = event.getDelta();
    IResourceDelta d = root == null ? null : root.findMember(file.getFullPath());
    if (d == null || (d.getFlags() & IResourceDelta.MARKERS) == 0) {
      return;
    }
    for (IMarkerDelta m : d.getMarkerDeltas()) {
      if (MARKER_TYPE.equals(m.getType()) || m.getAttribute(SERVER_ID) != null) {
        answer();
        return;
      }
    }
  }

  /** On any thread: a server answered; the annotations follow on the UI thread. */
  private void answer() {
    answered.accumulateAndGet(changes.get(), Math::max);
    if (disposed || display.isDisposed() || !updatePosted.compareAndSet(false, true)) {
      return;
    }
    try {
      display.asyncExec(() -> {
        updatePosted.set(false);
        if (!disposed) {
          update();
        }
      });
    } catch (SWTException e) {
      updatePosted.set(false); // the display went away
    }
  }

  /** Annotations from lsp4e's markers of the file (UI thread). */
  private void update() {
    long answer = answered.get(); // read first: an answer that comes during the update is shown by the next one
    Map<Annotation, Position> add = new HashMap<>();
    Map<Annotation, Kind> kinds = new HashMap<>();
    int length = document.getLength();
    for (IMarker m : markers()) {
      int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
      int start = m.getAttribute(IMarker.CHAR_START, -1);
      int end = m.getAttribute(IMarker.CHAR_END, -1);
      if (start < 0) {
        int line = m.getAttribute(IMarker.LINE_NUMBER, 0) - 1;
        if (line < 0) {
          continue;
        }
        try {
          start = document.getLineOffset(Math.min(line, document.getNumberOfLines() - 1));
        } catch (BadLocationException e) {
          continue;
        }
        end = start;
      }
      start = Math.max(0, Math.min(start, length));
      end = Math.max(start, Math.min(end, length));
      String type = severity == IMarker.SEVERITY_ERROR ? ERROR_TYPE
          : severity == IMarker.SEVERITY_WARNING ? WARNING_TYPE : INFO_TYPE;
      Annotation a = new Annotation(type, false, message(m));
      add.put(a, new Position(start, end - start));
      kinds.put(a, new Kind(severity == IMarker.SEVERITY_ERROR, m.getAttribute(SERVER_ID, null)));
    }
    replace(add);
    shown = Map.copyOf(kinds);
    answerShown.accumulateAndGet(answer, Math::max);
    listeners.forEach(Runnable::run);
  }

  /** lsp4e's diagnostics of the file: markers of any type that carry the id of their server. */
  private IMarker[] markers() {
    if (!file.exists()) {
      return new IMarker[0];
    }
    try {
      List<IMarker> out = new ArrayList<>();
      for (IMarker m : file.findMarkers(null, true, IResource.DEPTH_ZERO)) {
        if (m.getAttribute(SERVER_ID, null) != null) {
          out.add(m);
        }
      }
      return out.toArray(IMarker[]::new);
    } catch (CoreException e) {
      return new IMarker[0];
    }
  }

  /**
   * The server's own text: the diagnostic lsp4e keeps on the marker, else the marker's message (a server's marker
   * attribute computer may reword that one, and ours and theirs are compared by the server's own words).
   */
  private static String message(IMarker m) {
    try {
      if (m.getAttribute(DIAGNOSTIC) instanceof Diagnostic d) {
        return message(d);
      }
    } catch (CoreException e) {
      // the marker went away meanwhile
    }
    return m.getAttribute(IMarker.MESSAGE, "");
  }

  /** The text of a diagnostic, plain or Markdown, as the server wrote it. */
  static String message(Diagnostic d) {
    Either<String, MarkupContent> m = d.getMessage();
    if (m == null) {
      return "";
    }
    if (m.isLeft()) {
      return m.getLeft() == null ? "" : m.getLeft();
    }
    return m.getRight() == null || m.getRight().getValue() == null ? "" : m.getRight().getValue();
  }

  private void replace(Map<Annotation, Position> add) {
    Annotation[] old = shown.keySet().toArray(Annotation[]::new);
    if (annotations instanceof IAnnotationModelExtension ext) {
      ext.replaceAnnotations(old, add);
    } else {
      for (Annotation a : old) {
        annotations.removeAnnotation(a);
      }
      add.forEach(annotations::addAnnotation);
    }
  }

  /** Errors and warnings at their current places, sorted by offset. */
  public List<Problem> problems() {
    List<Problem> out = new ArrayList<>();
    Map<Annotation, Kind> now = shown;
    Object lock = annotations instanceof ISynchronizable s && s.getLockObject() != null ? s.getLockObject() : annotations;
    synchronized (lock) {
      for (Map.Entry<Annotation, Kind> e : now.entrySet()) {
        Annotation a = e.getKey();
        Position p = annotations.getPosition(a);
        if (p != null && !p.isDeleted() && !INFO_TYPE.equals(a.getType())) {
          out.add(new Problem(p.getOffset(), p.getLength(), e.getValue().error(), a.getText(),
              e.getValue().serverId()));
        }
      }
    }
    out.sort(Comparator.comparingInt(Problem::offset));
    return List.copyOf(out);
  }

  /** Called on the UI thread when {@link #problems()} may have changed. */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  /**
   * True when a server answered after the last change of the document (languages design, Part 2 Ruling 3) and the
   * annotations show that answer: until the update for it has run on the UI thread, {@link #problems()} is still the
   * text before.
   */
  public boolean settled() {
    return answerShown.get() >= changes.get();
  }

  public boolean disposed() {
    return disposed;
  }

  /** Stops listening and removes the annotations shown (UI thread). */
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    listeners.clear();
    try {
      document.removeDocumentListener(documentListener);
      ResourcesPlugin.getWorkspace().removeResourceChangeListener(markerListener);
      Job.getJobManager().removeJobChangeListener(jobListener);
    } finally {
      try {
        replace(Map.of());
      } finally {
        shown = Map.of();
      }
    }
  }
}
