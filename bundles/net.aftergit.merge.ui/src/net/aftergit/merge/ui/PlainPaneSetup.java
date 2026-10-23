package net.aftergit.merge.ui;

import java.util.function.Supplier;
import org.eclipse.jface.text.ConfigurableLineTracker;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.widgets.Composite;

/**
 * Plain text panes, also the base of the languages integration (LspPaneSetup): typed text stays verbatim, the result
 * has the resolver's undo only.
 */
class PlainPaneSetup implements PaneSetup {
  private final SharedResult shared;

  PlainPaneSetup() {
    this(null);
  }

  /**
   * Plain panes whose result is the file's shared buffer: the result of a file with a language server
   * (LspPaneSetup), and tests.
   */
  PlainPaneSetup(SharedResult shared) {
    this.shared = shared;
  }

  /**
   * A document whose only line delimiter is '\n', like the core model: a lone '\r' stays inside its line,
   * Eclipse line numbers equal model line numbers and Enter inserts "\n".
   */
  static IDocument lfDocument(String text) {
    Document d = new LfDocument();
    d.set(text);
    return d;
  }

  private static final class LfDocument extends Document {
    LfDocument() {
      // still empty here, so the new tracker starts consistent with the text
      setLineTracker(new ConfigurableLineTracker(new String[] {"\n"}));
    }
  }

  @Override
  public SourceViewer createViewer(PaneKind kind, Composite parent, Supplier<CompositeRuler> rulers, int styles,
      PaneServices services) {
    SourceViewer v = new SourceViewer(parent, rulers.get(), styles);
    // setTextHover alone does not install a hover manager on a viewer that was never configured
    v.configure(new SourceViewerConfiguration() {
      @Override
      public ITextHover getTextHover(ISourceViewer sv, String contentType) {
        return services.hover();
      }

      @Override
      public IAutoEditStrategy[] getAutoEditStrategies(ISourceViewer sv, String contentType) {
        return new IAutoEditStrategy[0];
      }

      @Override
      public IUndoManager getUndoManager(ISourceViewer sv) {
        return null; // undo/redo goes through UndoStack, which restores block states with the text
      }

      @Override
      public IPresentationReconciler getPresentationReconciler(ISourceViewer sv) {
        IPresentationReconciler r = presentationReconciler(kind);
        return r != null ? r : super.getPresentationReconciler(sv); // plain text: JFace's default, as before
      }
    });
    return v;
  }

  /**
   * The presentation reconciler of {@code kind}'s viewer, or null for none of a language (plain text). Called once per
   * pane while it is configured, before {@link #presentationReconciled} is asked.
   */
  IPresentationReconciler presentationReconciler(PaneKind kind) {
    return null;
  }

  @Override
  public IDocument sideDocument(String text) {
    return lfDocument(text);
  }

  @Override
  public IDocument resultDocument(String modelChunk) {
    return shared == null ? lfDocument(modelChunk) : shared.fill(modelChunk);
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
    viewer.setDocument(document);
  }

  @Override
  public boolean presentationReconciled(PaneKind kind) {
    return false;
  }

  @Override
  public void resultFocusChanged(boolean focused) {
    // nothing to activate
  }

  @Override
  public String resultContextId() {
    return null;
  }

  @Override
  public String notice() {
    return null;
  }

  @Override
  public ResultKind resultKind() {
    return ResultKind.PLAIN;
  }

  @Override
  public void dispose() {
    if (shared != null) {
      shared.close();
    }
  }
}
