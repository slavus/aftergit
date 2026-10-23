package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.jdt.ImportFixer;
import net.aftergit.merge.jdt.JavaImportLocator;
import net.aftergit.merge.jdt.JavaPanes;
import net.aftergit.merge.jdt.JavaResultPane;
import net.aftergit.merge.jdt.JavaSideConfiguration;
import net.aftergit.merge.jdt.JavaStructureImages;
import net.aftergit.merge.jdt.JavaStructureLocator;
import net.aftergit.merge.jdt.ResultRequest;
import net.aftergit.merge.jdt.SideAnalysis;
import net.aftergit.merge.jdt.SideSemanticColors;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.texteditor.ITextEditorExtension3;

/**
 * Java panes (spec §6.3, §7): JDT colors on the sides; the result is the Java editor (or its degraded form)
 * over the file's shared buffer, or a private document when the buffer cannot hold it. The only class of
 * this bundle that uses net.aftergit.merge.jdt; loaded through JavaBridge only.
 */
final class JavaPaneSetup implements PaneSetup {
  private final IFile file;
  private final IEditorSite site;
  private final SharedResult shared;
  private final String notSharedReason;
  private final IPreferenceStore store;
  private final List<Runnable> disposers = new ArrayList<>();
  private JavaResultPane resultPane;
  /** Compile errors of ours and theirs of the session this setup was made for (the sides never change). */
  private final SideAnalysis sides;
  private final List<Runnable> sideListeners = new CopyOnWriteArrayList<>();
  private volatile boolean disposed;
  /** The import sections of the session (null when the file was merged as plain text). */
  private final ImportSections imports;
  private final AtomicBoolean fixFailureLogged = new AtomicBoolean();
  /** The semantic colours of each side pane; touched on the UI thread only. */
  private final List<SideSemanticColors> semantics = new ArrayList<>();

  private JavaPaneSetup(IFile file, IEditorSite site, MergeSession session, SharedResult shared,
      String notSharedReason) {
    this.file = file;
    this.site = site;
    this.shared = shared;
    this.notSharedReason = notSharedReason;
    this.store = JavaPanes.preferenceStore(file.getProject());
    this.imports = session.imports();
    MergeModel m = session.model();
    this.sides = SideAnalysis.start(file, LineText.render(m.left().lines(), "\n", true),
        LineText.render(m.right().lines(), "\n", true), this::sidesAnalyzed);
  }

  /** On the analysis thread; the analysis may still finish once after dispose cancelled it. */
  void sidesAnalyzed() {
    if (!disposed) {
      sideListeners.forEach(Runnable::run);
    }
  }

  private static volatile Runnable createHookForTests;

  /** Runs {@code hook} in {@link #create} once the buffer is connected (a throwing hook breaks the setup). */
  static void setCreateHookForTests(Runnable hook) {
    createHookForTests = hook;
  }

  /** Java panes for a Java file, else null. */
  static PaneSetup create(IFile file, IEditorSite site, MergeSession session) {
    if (!JavaPanes.isJavaFile(file)) {
      return null;
    }
    SharedResult.Opened o = SharedResult.open(file, session);
    try {
      Runnable hook = createHookForTests;
      if (hook != null) {
        hook.run();
      }
      return new JavaPaneSetup(file, site, session, o.shared(), o.refusal());
    } catch (RuntimeException | LinkageError e) {
      if (o.shared() != null) {
        o.shared().close();
      }
      throw e;
    }
  }

  /** Compile errors of {@code text} as the content of {@code file}, by model line (Resolve All, spec §5.5). */
  static List<LineProblem> errors(IFile file, String text, IProgressMonitor monitor) throws CoreException {
    return SideAnalysis.errors(file, text, monitor);
  }

  /** Saves a Java file through the Java Save Actions (preferences design §4); false for any other file. */
  static boolean saveWithActions(IFile file, IProgressMonitor monitor) throws CoreException {
    if (!JavaPanes.isJavaFile(file)) {
      return false;
    }
    JavaPanes.saveWithActions(file, monitor);
    return true;
  }

  /** The Java import locator for a Java file (pass 1, spec §5.1), else null. */
  static ImportLocator importLocator(IFile file) {
    return JavaPanes.isJavaFile(file) ? JavaImportLocator.INSTANCE : null;
  }

  /** The Java structure locator (structure-compare design §1). */
  static StructureLocator structureLocator() {
    return JavaStructureLocator.INSTANCE;
  }

  /** JDT's icons for Java nodes (structure-compare design §2). */
  static StructureImages structureImages() {
    return JavaStructureImages::descriptor;
  }

  /**
   * About the longest the import fix after a body change may take on the UI thread; a slower fix is cancelled and the
   * action stays without it. Approximate: JDT takes the cancellation only where it checks the monitor (the working
   * copy, name resolution, the import rewrite), so the plain parse of the text before the change and applying the
   * edit still run to their end.
   */
  static final long FIX_BUDGET_MILLIS = 2000;

  /** Results longer than this (before the change) get no import fix after a body change: parsing alone could stall. */
  static final int MAX_FIXED_CHARS = 1_000_000;

  private static volatile long fixBudgetMillis = FIX_BUDGET_MILLIS;
  private static volatile int maxFixedChars = MAX_FIXED_CHARS;

  /** Replaces the time budget and the size limit of the fix after a body change. */
  static void setLimitsForTests(long budgetMillis, int maxChars) {
    fixBudgetMillis = budgetMillis;
    maxFixedChars = maxChars;
  }

  private static volatile Runnable fixHookForTests;

  /** Runs {@code hook} before each import fix (after a body change and at open); a throwing hook is a failing fix. */
  static void setFixHookForTests(Runnable hook) {
    fixHookForTests = hook;
  }

  private static void runFixHook() {
    Runnable hook = fixHookForTests;
    if (hook != null) {
      hook.run();
    }
  }

  /** Set after a VirtualMachineError of the fixer: a parser that overflowed once would overflow on every action. */
  private volatile boolean fixerDisabled;
  private final AtomicBoolean tooLargeNoted = new AtomicBoolean();
  private final AtomicBoolean tooSlowNoted = new AtomicBoolean();

  @Override
  public void afterBodyChange(MergeModel model, String before) {
    if (imports == null || disposed || fixerDisabled
        || !MergePreferences.resolverEnabled(file.getProject(), ImportsResolver.ID)) {
      // read at each change: switched off, even in this editor's toolbar, no import is fixed (preferences design §4)
      return;
    }
    if (before.length() > maxFixedChars) {
      note(tooLargeNoted, "the result is too large");
      return;
    }
    Deadline deadline = new Deadline(fixBudgetMillis);
    try {
      runFixHook();
      ImportFixer.fixAfterChange(file, model, before, imports, deadline, this::fixFailed);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      if (e instanceof VirtualMachineError) {
        fixerDisabled = true;
      }
      fixFailed(e);
    }
    if (deadline.expired()) {
      note(tooSlowNoted, "the fix took longer than " + fixBudgetMillis + " ms");
    }
  }

  /** A broken or overwhelmed fixer must not cost the action (spec §9): the change stays, its imports as they are. */
  private void fixFailed(Throwable e) {
    if (fixFailureLogged.compareAndSet(false, true)) {
      ILog.of(JavaPaneSetup.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The imports of " + file.getFullPath() + " are not adjusted after changes of the merge result",
          JavaBridge.loggable(e)));
    }
  }

  /** Tells once per session why the imports were left as they are after a change. */
  private void note(AtomicBoolean noted, String why) {
    if (noted.compareAndSet(false, true)) {
      ILog.of(JavaPaneSetup.class).log(new Status(IStatus.INFO, "net.aftergit.merge.ui",
          "The imports of " + file.getFullPath() + " were not adjusted after a change: " + why));
    }
  }

  /** Pass 1 at open ends with the imports the auto-resolved body needs (spec §5.3). */
  static void fixImportsAtOpen(IFile file, MergeModel model, ImportSections imports, IProgressMonitor monitor) {
    runFixHook();
    ImportFixer.fixAtOpen(file, model, imports, monitor);
  }

  /** A monitor that reports itself cancelled once its time is up. */
  private static final class Deadline extends NullProgressMonitor {
    private final long end;

    Deadline(long millis) {
      end = System.nanoTime() + millis * 1_000_000;
    }

    private volatile boolean expired;

    @Override
    public boolean isCanceled() {
      if (!expired && System.nanoTime() - end > 0) {
        expired = true;
      }
      return super.isCanceled() || expired;
    }

    /** True once a check found the time up: the fix was (or will be) cancelled. */
    boolean expired() {
      return expired;
    }
  }

  JavaResultPane resultPane() {
    return resultPane;
  }

  @Override
  public SourceViewer createViewer(PaneKind kind, Composite parent, Supplier<CompositeRuler> rulers, int styles,
      PaneServices services) {
    if (kind != PaneKind.RESULT) {
      SourceViewer v = new SourceViewer(parent, rulers.get(), styles);
      JavaSideConfiguration c = new JavaSideConfiguration(store, services.hover());
      v.configure(c);
      disposers.add(c.followPreferences(v, store));
      followSemanticSwitch(v);
      semantics.add(SideSemanticColors.install(v, file, store, MergePreferences.semanticSides()));
      return v;
    }
    // the full editor works on the file buffer, so it is offered only when the result is shared
    resultPane = JavaPanes.createResult(new ResultRequest(parent, rulers, styles, file, shared != null ? site : null,
        services.hover(), services.undo()));
    return resultPane.viewer();
  }

  /** Listens to the AfterGit switch once, with the first side pane; it applies to every side on the UI thread. */
  private void followSemanticSwitch(SourceViewer first) {
    if (!semantics.isEmpty()) {
      return;
    }
    Display display = first.getTextWidget().getDisplay();
    disposers.add(MergePreferences.onChange(MergePreferences.SEMANTIC_SIDES, () -> {
      if (!display.isDisposed()) {
        display.asyncExec(() -> {
          if (!disposed) {
            boolean on = MergePreferences.semanticSides();
            semantics.forEach(s -> s.setEnabled(on));
          }
        });
      }
    }));
  }

  @Override
  public IDocument sideDocument(String text) {
    IDocument d = PlainPaneSetup.lfDocument(text);
    JavaPanes.setupDocument(d);
    return d;
  }

  @Override
  public IDocument resultDocument(String modelChunk) {
    if (shared != null) {
      return shared.fill(modelChunk);
    }
    IDocument d = PlainPaneSetup.lfDocument(modelChunk);
    JavaPanes.setupDocument(d);
    return d;
  }

  @Override
  public String resultDelimiter() {
    return shared == null ? "\n" : shared.eol();
  }

  @Override
  public SharedBuffer sharedBuffer() {
    return shared == null ? null : shared.buffer();
  }

  @Override
  public void showResult(SourceViewer viewer, IDocument document) {
    resultPane.attach(document);
  }

  @Override
  public boolean presentationReconciled(PaneKind kind) {
    return true;
  }

  @Override
  public void resultFocusChanged(boolean focused) {
    resultPane.setFocused(focused);
  }

  static final String JAVA_EDITOR_SCOPE = "org.eclipse.jdt.ui.javaEditorScope";

  @Override
  public String resultContextId() {
    return resultPane != null && resultPane.isFullEditor() ? JAVA_EDITOR_SCOPE : null;
  }

  @Override
  public <T> T getAdapter(Class<T> type) {
    if (type == ITextEditorExtension3.class && resultPane != null && resultPane.textEditorExtension() != null) {
      return type.cast(resultPane.textEditorExtension());
    }
    return null;
  }

  @Override
  public List<ResultProblem> problems() {
    if (resultPane == null) {
      return List.of();
    }
    return resultPane.problems().stream()
        .map(p -> new ResultProblem(p.offset(), p.length(), p.error(), p.message()))
        .toList();
  }

  @Override
  public List<LineProblem> sideErrors(ThreeSide side) {
    if (disposed) {
      return null;
    }
    return switch (side) {
      case LEFT -> sides.leftErrors();
      case RIGHT -> sides.rightErrors();
      case BASE -> null;
    };
  }

  @Override
  public void addProblemListener(Runnable listener) {
    sideListeners.add(listener); // the analysis of ours and theirs changes what the result's errors mean
    if (resultPane != null) {
      resultPane.addProblemListener(listener);
    }
  }

  @Override
  public String notice() {
    if (shared == null) {
      return NLS.bind(Messages.noticeNotShared, notSharedReason);
    }
    if (site != null && resultPane != null && !resultPane.isFullEditor()) {
      return Messages.noticeJavaDegraded; // the full editor was possible but failed (details in the log)
    }
    return null;
  }

  @Override
  public ResultKind resultKind() {
    return resultPane != null && resultPane.isFullEditor() ? ResultKind.JAVA_EDITOR : ResultKind.JAVA_DEGRADED;
  }

  @Override
  public void dispose() {
    disposed = true;
    sides.cancel();
    sideListeners.clear();
    disposers.forEach(Runnable::run);
    disposers.clear();
    semantics.forEach(SideSemanticColors::dispose); // cancels the scans still running
    semantics.clear();
    if (resultPane != null) {
      resultPane.dispose(); // the editor releases its provider connection first
    }
    if (shared != null) {
      shared.close(); // then the buffer goes back to the file (Cancel) and our connection ends
    }
  }
}
