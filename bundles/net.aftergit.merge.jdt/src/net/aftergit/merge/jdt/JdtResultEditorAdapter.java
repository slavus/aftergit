package net.aftergit.merge.jdt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor;
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor;
import org.eclipse.jdt.internal.ui.text.correction.AssistContext;
import org.eclipse.jdt.internal.ui.text.correction.JavaCorrectionAssistant;
import org.eclipse.jdt.internal.ui.text.correction.JavaCorrectionProcessor;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.java.IInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorActionBarContributor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.eclipse.ui.texteditor.ITextEditorExtension3;

/**
 * The full Java editor in the merge result (spec §7.2): a hidden CompilationUnitEditor drives a viewer that
 * sits in the result pane, the way Eclipse's Java compare viewer embeds one (reimplemented). Reconciler,
 * squiggles, hovers, quick fix, content assist and semantic colors come from the editor; undo stays with the
 * resolver; save, revert and undo actions are removed so the merge editor keeps them.
 *
 * <p>The only class that uses internal JDT API. {@link #missingInternals()} lists what it relies on; the guard
 * test fails when a JDT update changes any of it, and {@link JavaPanes#createResult} falls back to the
 * degraded pane when anything here fails at run time.
 */
@SuppressWarnings("restriction")
final class JdtResultEditorAdapter implements JavaResultPane {
  private final Editor editor;
  private final SourceViewer viewer;
  private final ProblemTracker problems;
  private boolean disposed;
  private boolean actionsActive;

  private interface Lookup {
    Object find() throws ReflectiveOperationException;
  }

  private static void check(List<String> missing, String what, Lookup lookup) {
    try {
      lookup.find();
    } catch (ReflectiveOperationException | LinkageError e) {
      missing.add(what);
    }
  }

  /** Internal members this class depends on that the running JDT does not have; empty when all is well. */
  static List<String> missingInternals() {
    List<String> missing = new ArrayList<>();
    check(missing, "AbstractTextEditor.fSourceViewer", () -> AbstractTextEditor.class.getDeclaredField("fSourceViewer"));
    check(missing, "AbstractTextEditor.setActionActivation(boolean)",
        () -> AbstractTextEditor.class.getDeclaredMethod("setActionActivation", boolean.class));
    check(missing, "JavaEditor.setActionsActivated(boolean)",
        () -> JavaEditor.class.getDeclaredMethod("setActionsActivated", boolean.class));
    check(missing, "JavaEditor.installSemanticHighlighting()",
        () -> JavaEditor.class.getDeclaredMethod("installSemanticHighlighting"));
    check(missing, "JavaEditor.setPreferenceStore(IPreferenceStore)",
        () -> JavaEditor.class.getDeclaredMethod("setPreferenceStore", IPreferenceStore.class));
    check(missing, "CompilationUnitEditor.createJavaSourceViewer(...)",
        () -> CompilationUnitEditor.class.getDeclaredMethod("createJavaSourceViewer", Composite.class,
            IVerticalRuler.class, IOverviewRuler.class, boolean.class, int.class, IPreferenceStore.class));
    check(missing, "CompilationUnitEditor.AdaptedSourceViewer(...)",
        Editor::adaptedViewerConstructor);
    check(missing, "JavaCorrectionAssistant.collectQuickFixableAnnotations(...)",
        () -> JavaCorrectionAssistant.class.getMethod("collectQuickFixableAnnotations", ITextEditor.class,
            int.class, boolean.class, ArrayList.class));
    check(missing, "JavaCorrectionProcessor.collectProposals(...)",
        () -> JavaCorrectionProcessor.class.getMethod("collectProposals", IInvocationContext.class,
            IAnnotationModel.class, Annotation[].class, boolean.class, boolean.class, Collection.class));
    check(missing, "AssistContext(ICompilationUnit, ISourceViewer, IEditorPart, int, int)",
        () -> AssistContext.class.getConstructor(ICompilationUnit.class, ISourceViewer.class, IEditorPart.class,
            int.class, int.class));
    check(missing, "CompilationUnitEditor.getCorrectionCommands()",
        () -> CompilationUnitEditor.class.getDeclaredMethod("getCorrectionCommands"));
    check(missing, "CompilationUnitEditor.getRefactorActionGroup()",
        () -> CompilationUnitEditor.class.getDeclaredMethod("getRefactorActionGroup"));
    check(missing, "CompilationUnitEditor.getGenerateActionGroup()",
        () -> CompilationUnitEditor.class.getDeclaredMethod("getGenerateActionGroup"));
    return missing;
  }

  static JdtResultEditorAdapter create(ResultRequest r) throws PartInitException, ReflectiveOperationException {
    List<String> missing = missingInternals();
    if (!missing.isEmpty()) {
      throw new IllegalStateException("JDT internals changed: " + missing);
    }
    Editor editor = new Editor(r.site(), r.mergeHover(), r.undoManager());
    SourceViewer viewer = null;
    try {
      CompositeRuler ruler = r.rulers().get();
      ruler.addDecorator(0, AnnotationColumns.create(new DefaultMarkerAnnotationAccess()));
      viewer = editor.createViewer(r.parent(), ruler, r.styles());
      RuntimeException injected = JavaPanes.injectedFailure();
      if (injected != null) {
        throw injected;
      }
      Field field = AbstractTextEditor.class.getDeclaredField("fSourceViewer");
      field.setAccessible(true);
      field.set(editor, viewer);
      editor.wireNavigation();
      editor.init(r.site(), new FileEditorInput(r.file()));
      editor.wireActions();
      editor.wireSemanticHighlighting();
      if (viewer instanceof ProjectionViewer pv && pv.isProjectionMode()) {
        pv.disableProjection(); // no folding in the merge result (spec §7.2)
      }
      RuntimeException late = JavaPanes.injectedLateFailure();
      if (late != null) {
        throw late;
      }
      return new JdtResultEditorAdapter(editor, viewer);
    } catch (RuntimeException | PartInitException | ReflectiveOperationException | LinkageError e) {
      if (viewer != null && !viewer.getControl().isDisposed()) {
        viewer.getControl().dispose(); // disposes the editor through handleDispose
      } else {
        editor.disposeQuietly();
      }
      throw e;
    }
  }

  private JdtResultEditorAdapter(Editor editor, SourceViewer viewer) {
    this.editor = editor;
    this.viewer = viewer;
    IAnnotationModel model = editor.getDocumentProvider().getAnnotationModel(editor.getEditorInput());
    if (model == null) {
      throw new IllegalStateException("The Java document provider gave no annotation model");
    }
    this.problems = new ProblemTracker(model);
    editor.onViewerDisposed = this::dispose;
  }

  @Override
  public SourceViewer viewer() {
    return viewer;
  }

  @Override
  public void attach(IDocument document) {
    IDocument own = editor.getDocumentProvider().getDocument(editor.getEditorInput());
    if (own != document) {
      throw new IllegalStateException("The merge result must be the Java editor's own document");
    }
  }

  @Override
  public boolean isFullEditor() {
    return true;
  }

  @Override
  public List<Problem> problems() {
    return problems.problems();
  }

  @Override
  public void addProblemListener(Runnable listener) {
    problems.addListener(listener);
  }

  @Override
  public void setFocused(boolean focused) {
    if (!disposed && focused != actionsActive) {
      editor.activateActions(focused);
      actionsActive = focused;
    }
  }

  @Override
  public ICompilationUnit workingCopy() {
    return JavaUI.getWorkingCopyManager().getWorkingCopy(editor.getEditorInput());
  }

  @Override
  public ICompletionProposal[] completionProposals(int offset) {
    IContentAssistant assistant = Editor.contentAssistant(viewer);
    if (assistant == null) {
      return new ICompletionProposal[0];
    }
    String type;
    try {
      type = TextUtilities.getContentType(viewer.getDocument(), IJavaPartitions.JAVA_PARTITIONING, offset, true);
    } catch (BadLocationException e) {
      return new ICompletionProposal[0];
    }
    IContentAssistProcessor p = assistant.getContentAssistProcessor(type);
    ICompletionProposal[] found = p == null ? null : p.computeCompletionProposals(viewer, offset);
    return found == null ? new ICompletionProposal[0] : found;
  }

  /**
   * What Ctrl+1 offers at {@code offset}. The editor's correction assistant collects the quick-fixable
   * annotations when its popup opens, so its processor alone sees none; this does both steps.
   */
  @Override
  public ICompletionProposal[] quickAssistProposals(int offset) {
    ICompilationUnit cu = workingCopy();
    IAnnotationModel model = editor.getDocumentProvider().getAnnotationModel(editor.getEditorInput());
    if (cu == null || model == null) {
      return new ICompletionProposal[0];
    }
    ArrayList<Annotation> fixable = new ArrayList<>();
    try {
      JavaCorrectionAssistant.collectQuickFixableAnnotations(editor, offset, false, fixable);
    } catch (BadLocationException e) {
      return new ICompletionProposal[0];
    }
    List<IJavaCompletionProposal> found = new ArrayList<>();
    JavaCorrectionProcessor.collectProposals(new AssistContext(cu, viewer, editor, offset, 0), model,
        fixable.toArray(Annotation[]::new), true, true, found); // quick fixes and quick assists, like Ctrl+1
    found.sort(Comparator.comparingInt(IJavaCompletionProposal::getRelevance).reversed());
    return found.toArray(ICompletionProposal[]::new);
  }

  @Override
  public ITextEditorExtension3 textEditorExtension() {
    return editor;
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    if (actionsActive) { // closed while the result had the focus: no handlers may outlive the editor
      actionsActive = false;
      editor.deactivateActionsQuietly();
    }
    problems.dispose();
    editor.disposeQuietly(); // AbstractTextEditor.dispose disconnects the document provider
  }

  /** The hidden editor; it lives exactly as long as the result viewer. */
  static final class Editor extends CompilationUnitEditor {
    private final IEditorSite site;
    private final ITextHover mergeHover;
    private final IUndoManager undo;
    private final boolean ready;
    Runnable onViewerDisposed = this::disposeQuietly;
    /** Registered by JDT's FoldingActionGroup. */
    private static final String[] FOLDING_ACTIONS = {"FoldingToggle", "FoldingExpandAll", "FoldingCollapseAll",
        "FoldingExpand", "FoldingCollapse", "FoldingRestore", "FoldingCollapseMembers", "FoldingCollapseComments"};

    Editor(IEditorSite site, ITextHover mergeHover, IUndoManager undo) {
      this.site = site;
      this.mergeHover = mergeHover;
      this.undo = undo;
      this.ready = true;
      setPreferenceStore(JavaPanes.preferenceStore(null)); // doSetInput replaces it with the project's chain
    }

    @Override
    protected void setPreferenceStore(IPreferenceStore store) {
      super.setPreferenceStore(store);
      if (ready) { // false while the superclass constructor runs
        setSourceViewerConfiguration(new ResultJavaConfiguration(store, this, mergeHover, undo));
      }
    }

    SourceViewer createViewer(Composite parent, CompositeRuler ruler, int styles) {
      return (SourceViewer) createJavaSourceViewer(parent, ruler, null, false, styles, getPreferenceStore());
    }

    @Override
    protected ISourceViewer createJavaSourceViewer(Composite parent, IVerticalRuler verticalRuler,
        IOverviewRuler overviewRuler, boolean isOverviewRulerVisible, int styles, IPreferenceStore store) {
      return new AdaptedSourceViewer(parent, verticalRuler, overviewRuler, isOverviewRulerVisible, styles, store) {
        @Override
        protected void handleDispose() {
          super.handleDispose();
          onViewerDisposed.run();
        }
      };
    }

    /** AdaptedSourceViewer is a protected member class: only a subclass may name it. */
    static Object adaptedViewerConstructor() throws NoSuchMethodException {
      return AdaptedSourceViewer.class.getConstructor(CompilationUnitEditor.class, Composite.class,
          IVerticalRuler.class, IOverviewRuler.class, boolean.class, int.class, IPreferenceStore.class);
    }

    static IContentAssistant contentAssistant(SourceViewer viewer) {
      return viewer instanceof AdaptedSourceViewer a ? a.getContentAssistant() : null;
    }

    void wireNavigation() {
      createNavigationActions();
      getSelectionProvider().addSelectionChangedListener(getSelectionChangedListener());
    }

    void wireActions() {
      createActions();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void createActions() {
      super.createActions();
      // quick fix shortcuts would be registered on the merge editor's site
      getCorrectionCommands().deregisterCommands();
      getRefactorActionGroup().dispose();
      getGenerateActionGroup().dispose();
      // the merge editor owns save (marker question) and undo (block states)
      for (String id : new String[] {ITextEditorActionConstants.SAVE, ITextEditorActionConstants.REVERT,
          ITextEditorActionConstants.REVERT_TO_SAVED, ITextEditorActionConstants.UNDO,
          ITextEditorActionConstants.REDO}) {
        setAction(id, null);
      }
      // no folding here; the toggle would also flip JDT's global folding preference
      for (String id : FOLDING_ACTIONS) {
        setAction(id, null);
      }
    }

    void wireSemanticHighlighting() {
      installSemanticHighlighting();
    }

    void activateActions(boolean on) {
      setActionsActivated(on);
      // AbstractTextEditor hands itself (on) or null (off) to the site's contributor. For the merge editor that
      // is CompareEditorContributor (shared by all compare editors), whose setActiveEditor clears the
      // next/previous difference handlers for any part that is not a CompareEditor; giving it back the active
      // editor it serves reinstalls them.
      IEditorActionBarContributor contributor = site.getActionBarContributor();
      IEditorPart active = site.getPage() == null ? null : site.getPage().getActiveEditor();
      if (contributor != null && active != null && active != this
          && active.getEditorSite().getActionBarContributor() == contributor) {
        contributor.setActiveEditor(active);
      }
    }

    void deactivateActionsQuietly() {
      try {
        activateActions(false);
      } catch (RuntimeException e) {
        ILog.of(JdtResultEditorAdapter.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
            "Deactivating the merge result's Java editor actions failed", e));
      }
    }

    void disposeQuietly() {
      try {
        dispose();
      } catch (RuntimeException e) {
        ILog.of(JdtResultEditorAdapter.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
            "Disposing the merge result's Java editor failed", e));
      }
    }

    @Override
    public IWorkbenchPartSite getSite() {
      return site;
    }

    @Override
    public boolean isEditable() {
      return true;
    }

    @Override
    public boolean isEditorInputModifiable() {
      return true;
    }

    @Override
    public boolean isEditorInputReadOnly() {
      return false;
    }

    @Override
    protected boolean isWordWrapSupported() {
      return false;
    }

    @Override
    public void close(boolean save) {
      // the merge editor closes the result
    }

    @Override
    protected void installCodeMiningProviders() {
      // no code minings in the merge result
    }
  }
}
