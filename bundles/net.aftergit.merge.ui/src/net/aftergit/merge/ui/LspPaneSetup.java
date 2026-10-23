package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.lsp.Problem;
import net.aftergit.merge.lsp.ResultChecks;
import net.aftergit.merge.lsp.ResultDiagnostics;
import net.aftergit.merge.lsp.SaveParticipants;
import net.aftergit.merge.lsp.Servers;
import net.aftergit.merge.lsp.SideChecks;
import net.aftergit.merge.lsp.TextMatePanes;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.AnnotationRulerColumn;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationAccess;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;

/**
 * Panes of a file that Eclipse has a TextMate grammar or a language server for (languages design, Parts 1 and 2):
 * plain panes whose viewers get tm4e's colours, and, with a server, a result in the file's shared buffer that shows
 * the server's errors, with ours and theirs checked for "new errors". The only class of this bundle that uses
 * net.aftergit.merge.lsp; loaded through LspBridge only.
 */
final class LspPaneSetup extends PlainPaneSetup {
  /** The TextMate colours, or null without a grammar. */
  private final TextMatePanes colours;
  /** The length of each pane's text at open: a pane above the limit stays plain (Part 1, Ruling 8). */
  private final Map<PaneKind, Integer> lengths;
  private final Set<PaneKind> reconciled = EnumSet.noneOf(PaneKind.class);
  private final IFile file;
  /** True when lsp4e has a server for the file. */
  private final boolean server;
  /** Why the result is a private document although the file has a server, or null. */
  private final String notSharedReason;
  /** A reconciler failed to build: this and every later pane is plain, logged once. */
  private boolean failed;
  private Runnable stopFollowing;
  private volatile boolean disposed;
  private final List<Runnable> problemListeners = new CopyOnWriteArrayList<>();
  /** The server's errors in the result; null without a shared result with a server. */
  private ResultDiagnostics diagnostics;
  /** The annotation model of the result that {@link #diagnostics} fills. */
  private AnnotationModel annotations;
  private Runnable undecorate;
  /** Ours and theirs checked by the server (null without a shared result with a server); the sides never change. */
  private final SideChecks sides;
  /** The result checked as the sides are, for "new errors"; null without {@link #sides} or before the text is in. */
  private ResultChecks results;

  private LspPaneSetup(TextMatePanes colours, Map<PaneKind, Integer> lengths, IFile file, boolean server,
      SharedResult shared, String notSharedReason, MergeSession session) {
    super(shared);
    this.colours = colours;
    this.lengths = lengths;
    this.file = file;
    this.server = server;
    this.notSharedReason = notSharedReason;
    if (server && shared != null) {
      // the resolved text reaches the file exactly: no edits of a server before the save (Part 2 ruling)
      IDocument d = shared.buffer().document();
      shared.buffer().holdDuringCommit(() -> holdSaveParticipants(d));
    }
    MergeModel m = session.model();
    this.sides = server && shared != null ? SideChecks.start(file, LineText.render(m.left().lines(), "\n", true),
        LineText.render(m.right().lines(), "\n", true), this::sidesChecked) : null;
  }

  private static Runnable holdSaveParticipants(IDocument document) {
    try {
      return SaveParticipants.hold(document);
    } catch (RuntimeException | LinkageError e) {
      ILog.of(LspPaneSetup.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "The save participants "
          + "of the language servers could not be held", JavaBridge.loggable(e)));
      return null;
    }
  }

  /**
   * The panes for {@code file}, or null when Eclipse has neither a TextMate grammar nor a language server for it (or
   * it is a Java file). With a server the result lives in the file's shared buffer, as for Java.
   */
  static PaneSetup create(IFile file, MergeSession session) {
    TextMatePanes c = TextMatePanes.forFile(file.getName(), ColorSchemes.darkTheme(), MergePreferences.syntaxColours());
    SharedResult.Opened o = null;
    try {
      boolean server = Servers.hasServer(file);
      if (c == null && !server) {
        return null;
      }
      MergeModel m = session.model();
      Map<PaneKind, Integer> lengths = new EnumMap<>(PaneKind.class);
      lengths.put(PaneKind.LEFT, LineText.render(m.left().lines(), "\n", true).length());
      lengths.put(PaneKind.RIGHT, LineText.render(m.right().lines(), "\n", true).length());
      lengths.put(PaneKind.RESULT, m.document().chunk(0, m.document().lineCount()).length());
      o = server ? SharedResult.open(file, session) : new SharedResult.Opened(null, null);
      return new LspPaneSetup(c, lengths, file, server, o.shared(), o.refusal(), session);
    } catch (RuntimeException | LinkageError e) {
      // nothing opened here is left behind: the shared buffer goes back, the TextMate colours go
      try {
        if (o != null && o.shared() != null) {
          o.shared().close();
        }
      } finally {
        if (c != null) {
          c.dispose();
        }
      }
      throw e;
    }
  }

  /** True when lsp4e has a language server for {@code file} (never for Java files). */
  static boolean hasServer(IFile file) {
    return Servers.hasServer(file);
  }

  /**
   * The server's errors of {@code text} as the content of {@code file}, by model line, for Resolve All; null when
   * unknown. Blocks while the servers start and answer, so never on the UI thread.
   */
  static List<LineProblem> errors(IFile file, String text, IProgressMonitor monitor) {
    return SideChecks.errors(file, text, monitor);
  }

  /**
   * The server's errors of the result, ours and theirs, in this order, for Resolve All (SideChecks.errors); null when
   * unknown. Blocks while the servers start and answer, so never on the UI thread.
   */
  static List<List<LineProblem>> errors(IFile file, String result, String left, String right,
      IProgressMonitor monitor) {
    return SideChecks.errors(file, result, left, right, monitor);
  }

  TextMatePanes colours() {
    return colours;
  }

  ResultDiagnostics diagnostics() {
    return diagnostics;
  }

  SideChecks sides() {
    return sides;
  }

  ResultChecks results() {
    return results;
  }

  /** True when the result shows the server's errors: a server and a shared result. */
  private boolean checksResult() {
    return server && sharedBuffer() != null;
  }

  @Override
  public SourceViewer createViewer(PaneKind kind, Composite parent, Supplier<CompositeRuler> rulers, int styles,
      PaneServices services) {
    boolean annotated = kind == PaneKind.RESULT && checksResult();
    SourceViewer v = super.createViewer(kind, parent, annotated ? () -> withAnnotationColumn(rulers.get()) : rulers,
        styles, services);
    if (annotated) {
      undecorate = decorate(v);
    }
    if (colours != null && stopFollowing == null) {
      // once, with the first pane: the switch applies to all of them on the UI thread
      Display display = parent.getDisplay();
      stopFollowing = MergePreferences.onChange(MergePreferences.SYNTAX_COLOURS, () -> {
        if (!display.isDisposed()) {
          display.asyncExec(() -> {
            if (!disposed) {
              colours.setEnabled(MergePreferences.syntaxColours());
            }
          });
        }
      });
      // a change between the creation of the setup and this listener would otherwise be missed
      colours.setEnabled(MergePreferences.syntaxColours());
    }
    return v;
  }

  /** The error, warning and info icons left of the line numbers, as in the Java result (spec §6.3). */
  private static CompositeRuler withAnnotationColumn(CompositeRuler ruler) {
    AnnotationRulerColumn c = new AnnotationRulerColumn(12, new DefaultMarkerAnnotationAccess());
    c.addAnnotationType(ResultDiagnostics.ERROR_TYPE);
    c.addAnnotationType(ResultDiagnostics.WARNING_TYPE);
    c.addAnnotationType(ResultDiagnostics.INFO_TYPE);
    ruler.addDecorator(0, c);
    return ruler;
  }

  /** Squiggles and the other text decorations of the annotations, as Preferences > Annotations sets them. */
  private static Runnable decorate(SourceViewer viewer) {
    IAnnotationAccess access = new DefaultMarkerAnnotationAccess();
    SourceViewerDecorationSupport s = new SourceViewerDecorationSupport(viewer, null, access,
        EditorsUI.getSharedTextColors());
    for (AnnotationPreference p : new MarkerAnnotationPreferences().getAnnotationPreferences()) {
      s.setAnnotationPreference(p);
    }
    s.install(EditorsUI.getPreferenceStore());
    return s::dispose;
  }

  @Override
  IPresentationReconciler presentationReconciler(PaneKind kind) {
    if (failed || colours == null) {
      return null;
    }
    IPresentationReconciler r;
    try {
      r = colours.reconciler(lengths.getOrDefault(kind, Integer.MAX_VALUE));
    } catch (RuntimeException | LinkageError e) {
      // a broken TextMate theme or tm4e while the panes are built: plain panes, never a resolver that does not open
      failed = true;
      ILog.of(LspPaneSetup.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "TextMate colours "
          + "unavailable for " + file.getFullPath() + "; the resolver shows plain panes", JavaBridge.loggable(e)));
      return null;
    }
    if (r != null) {
      reconciled.add(kind); // the changed words then go through the presentation (InnerLineHighlighter)
    }
    return r;
  }

  @Override
  public boolean presentationReconciled(PaneKind kind) {
    return reconciled.contains(kind);
  }

  @Override
  public IDocument resultDocument(String modelChunk) {
    if (!checksResult()) {
      return super.resultDocument(modelChunk);
    }
    if (diagnostics != null) {
      diagnostics.dispose(); // a new session of the same resolver
      diagnostics = null;
    }
    if (results != null) {
      results.dispose();
      results = null;
    }
    IDocument d = sharedBuffer().document();
    annotations = new AnnotationModel();
    Display display = Display.getCurrent() != null ? Display.getCurrent() : Display.getDefault();
    try {
      // before the result text goes in: the server's answer to it comes back any time after; the servers once it is in
      diagnostics = ResultDiagnostics.connect(file, d, annotations, display);
      diagnostics.addListener(this::problemsChanged);
    } catch (RuntimeException | LinkageError e) {
      // a broken lsp4e must not cost the resolver: the result without the server's errors
      diagnostics = null;
      ILog.of(LspPaneSetup.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "The errors of the "
          + "language server are unavailable for " + file.getFullPath(), JavaBridge.loggable(e)));
    }
    String before = d.get();
    IDocument filled = super.resultDocument(modelChunk);
    if (diagnostics != null) {
      diagnostics.connectServers(); // never while the text goes in: lsp4e would read it on another thread
      if (before.equals(filled.get())) {
        diagnostics.showMarkers(); // the text the file's markers were made for, if a server answered already
      }
    }
    if (sides != null) {
      try {
        // "new errors" compare the sides with the result checked the same way: AfterGit's instance, a virtual name
        results = ResultChecks.start(file, d, display, sides);
        results.addListener(this::problemsChanged);
      } catch (RuntimeException | LinkageError e) {
        results = null;
        ILog.of(LspPaneSetup.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "The merge result of "
            + file.getFullPath() + " cannot be compared with ours and theirs; its new errors are shown as errors",
            JavaBridge.loggable(e)));
      }
    }
    return filled;
  }

  @Override
  public void showResult(SourceViewer viewer, IDocument document) {
    if (!checksResult() || annotations == null) {
      super.showResult(viewer, document);
      return;
    }
    viewer.setDocument(document, annotations);
  }

  @Override
  public List<ResultProblem> problems() {
    if (diagnostics == null) {
      return List.of();
    }
    return diagnostics.problems().stream()
        .map(p -> new ResultProblem(p.offset(), p.length(), p.error(), p.message()))
        .toList();
  }

  @Override
  public List<LineProblem> sideErrors(ThreeSide side) {
    if (disposed || sides == null) {
      return null;
    }
    Set<String> rejected = rejectedServers();
    return switch (side) {
      case LEFT -> sides.leftErrors(rejected);
      case RIGHT -> sides.rightErrors(rejected);
      case BASE -> null;
    };
  }

  /**
   * The result's errors as AfterGit's own instances find them, as the sides are found (languages design, Part 2),
   * plus, for a server that checks no such document, its errors as lsp4e shows them, which are then all new; null
   * until the result was checked once. Without the side checks, or for a result above Part 1's size limit:
   * {@code shown}.
   */
  @Override
  public List<LineProblem> comparedErrors(List<LineProblem> shown) {
    if (disposed || sides == null || results == null || results.skipped()) {
      return shown; // a result above Part 1's size limit is not checked again: the errors it shows
    }
    Set<String> rejected = rejectedServers();
    List<LineProblem> own = results.errors(rejected);
    if (own == null || rejected.isEmpty() || diagnostics == null) {
      return own;
    }
    List<LineProblem> out = new ArrayList<>(own);
    IDocument d = sharedBuffer().document();
    for (Problem p : diagnostics.problems()) {
      if (p.error() && rejected.contains(p.serverId())) {
        try {
          out.add(new LineProblem(d.getLineOfOffset(Math.min(p.offset(), d.getLength())),
              p.message() == null ? "" : p.message()));
        } catch (BadLocationException e) {
          // a stale problem: skip
        }
      }
    }
    return out;
  }

  /** The servers that rejected ours and theirs or the result: their errors are compared as lsp4e shows them. */
  private Set<String> rejectedServers() {
    Set<String> out = new HashSet<>(sides.rejectedServers());
    if (results != null) {
      out.addAll(results.rejectedServers());
    }
    return out;
  }

  @Override
  public void addProblemListener(Runnable listener) {
    problemListeners.add(listener); // the result's errors and the checks of ours and theirs
  }

  /** On the UI thread: the server answered for the result. */
  private void problemsChanged() {
    if (!disposed) {
      problemListeners.forEach(Runnable::run);
    }
  }

  /** On the job's thread; the checks may still finish once after dispose cancelled them. */
  void sidesChecked() {
    if (!disposed) {
      problemListeners.forEach(Runnable::run);
    }
  }

  @Override
  public boolean diagnosticsPending() {
    if (disposed) {
      return false;
    }
    return diagnostics != null && !diagnostics.settled() || sides != null && !sides.finished()
        || results != null && !results.settled();
  }

  @Override
  public String notice() {
    return server && sharedBuffer() == null && notSharedReason != null
        ? NLS.bind(Messages.noticeNoLanguageErrors, notSharedReason) : null;
  }

  @Override
  public void dispose() {
    disposed = true;
    problemListeners.clear();
    try {
      if (stopFollowing != null) {
        stopFollowing.run();
      }
      if (sides != null) {
        sides.cancel();
      }
      if (results != null) {
        results.dispose();
      }
      if (diagnostics != null) {
        diagnostics.dispose();
      }
      if (undecorate != null) {
        undecorate.run();
      }
      if (colours != null) {
        colours.dispose();
      }
    } finally {
      super.dispose(); // then the shared buffer goes back to the file (Cancel) and our connection ends
    }
  }
}
