package net.aftergit.merge.jdt;

import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.quickassist.IQuickAssistAssistant;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;

/** A read-only Java side pane (spec §7.3): lexical JDT colors and the resolver's hover, nothing editor-like. */
public final class JavaSideConfiguration extends JavaSourceViewerConfiguration {
  private final ITextHover hover;

  public JavaSideConfiguration(IPreferenceStore store, ITextHover hover) {
    super(JavaUI.getColorManager(), store, null, IJavaPartitions.JAVA_PARTITIONING);
    this.hover = hover;
  }

  /**
   * {@link JavaPanes#followPreferences} for this configuration. Callers outside this bundle use this method: they
   * do not see JDT, and passing this configuration as a JDT type would fail their class at load time.
   */
  public Runnable followPreferences(SourceViewer viewer, IPreferenceStore store) {
    return JavaPanes.followPreferences(viewer, this, store);
  }

  @Override
  public int[] getConfiguredTextHoverStateMasks(ISourceViewer sv, String contentType) {
    return new int[] {ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK};
  }

  @Override
  public ITextHover getTextHover(ISourceViewer sv, String contentType, int stateMask) {
    return hover;
  }

  @Override
  public ITextHover getTextHover(ISourceViewer sv, String contentType) {
    return hover;
  }

  @Override
  public IInformationControlCreator getInformationControlCreator(ISourceViewer sv) {
    return MergeTextHover.PLAIN; // JDT's HTML presenter would garble the base text
  }

  @Override
  public IAutoEditStrategy[] getAutoEditStrategies(ISourceViewer sv, String contentType) {
    return new IAutoEditStrategy[0];
  }

  @Override
  public IUndoManager getUndoManager(ISourceViewer sv) {
    return null;
  }

  @Override
  public IReconciler getReconciler(ISourceViewer sv) {
    return null; // the side texts are not workspace files: nothing to reconcile or spell-check
  }

  @Override
  public IContentAssistant getContentAssistant(ISourceViewer sv) {
    return null;
  }

  @Override
  public IQuickAssistAssistant getQuickAssistAssistant(ISourceViewer sv) {
    return null;
  }

  @Override
  public IHyperlinkDetector[] getHyperlinkDetectors(ISourceViewer sv) {
    return null;
  }
}
