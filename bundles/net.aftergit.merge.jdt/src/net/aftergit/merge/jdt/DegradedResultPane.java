package net.aftergit.merge.jdt;

import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationAccess;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.ITextEditorExtension3;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;

/**
 * The result without the Java editor (spec §7.2, degraded mode): JDT colors, annotation ruler and squiggles;
 * no quick fix, no content assist. Public JDT API only.
 */
final class DegradedResultPane implements JavaResultPane {
  private final IFile file;
  private final SourceViewer viewer;
  private final AnnotationModel annotations = new AnnotationModel();
  private final ProblemTracker problems = new ProblemTracker(annotations);
  private DiagnosticsJob diagnostics;
  private final SourceViewerDecorationSupport decoration;
  private final Runnable unfollow;
  private boolean disposed;

  DegradedResultPane(ResultRequest r) {
    this.file = r.file();
    IAnnotationAccess access = new DefaultMarkerAnnotationAccess();
    CompositeRuler ruler = r.rulers().get();
    ruler.addDecorator(0, AnnotationColumns.create(access));
    viewer = new SourceViewer(r.parent(), ruler, null, false, r.styles());
    IPreferenceStore store = JavaPanes.preferenceStore(file.getProject());
    ResultJavaConfiguration config = new ResultJavaConfiguration(store, null, r.mergeHover(), r.undoManager());
    viewer.configure(config);
    unfollow = JavaPanes.followPreferences(viewer, config, store);
    decoration = new SourceViewerDecorationSupport(viewer, null, access, EditorsUI.getSharedTextColors());
    for (AnnotationPreference p : new MarkerAnnotationPreferences().getAnnotationPreferences()) {
      decoration.setAnnotationPreference(p);
    }
    decoration.install(EditorsUI.getPreferenceStore());
    viewer.getControl().addDisposeListener(e -> dispose());
  }

  @Override
  public SourceViewer viewer() {
    return viewer;
  }

  @Override
  public void attach(IDocument document) {
    JavaPanes.setupDocument(document);
    viewer.setDocument(document, annotations);
    if (diagnostics != null) {
      diagnostics.dispose();
    }
    try {
      diagnostics = DiagnosticsJob.create(file, document, annotations, viewer.getTextWidget().getDisplay());
      if (diagnostics == null) {
        ILog.of(DegradedResultPane.class).log(new Status(IStatus.INFO, JavaPanes.PLUGIN_ID,
            "No Java model for the merge result of " + file.getFullPath() + "; no errors are shown"));
      } else {
        diagnostics.start();
      }
    } catch (JavaModelException e) {
      diagnostics = null;
      ILog.of(DegradedResultPane.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "No errors for the merge result of " + file.getFullPath(), e));
    }
  }

  @Override
  public boolean isFullEditor() {
    return false;
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
    // no editor actions to activate
  }

  @Override
  public ICompilationUnit workingCopy() {
    return diagnostics == null ? null : diagnostics.workingCopy();
  }

  @Override
  public ICompletionProposal[] completionProposals(int offset) {
    return new ICompletionProposal[0];
  }

  @Override
  public ICompletionProposal[] quickAssistProposals(int offset) {
    return new ICompletionProposal[0];
  }

  @Override
  public ITextEditorExtension3 textEditorExtension() {
    return null;
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    if (diagnostics != null) {
      diagnostics.dispose();
    }
    problems.dispose();
    StyledText t = viewer.getTextWidget();
    if (t != null && !t.isDisposed()) {
      viewer.setDocument(null); // before the shared buffer is reverted: no recolouring of a closing pane
    }
    unfollow.run();
    decoration.dispose();
  }
}
