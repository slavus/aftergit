package net.aftergit.merge.jdt;

import java.util.Arrays;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * The result's Java configuration: everything from JDT (with {@code editor}: reconciler, quick fix, content
 * assist, auto edits), except undo, which stays with the resolver, and the hover, where the base text of a
 * conflict comes first.
 */
final class ResultJavaConfiguration extends JavaSourceViewerConfiguration {
  private static final int DEFAULT = ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK;

  private final ITextHover mergeHover;
  private final IUndoManager undo;

  /** {@code editor} is null in the degraded mode. */
  ResultJavaConfiguration(IPreferenceStore store, ITextEditor editor, ITextHover mergeHover, IUndoManager undo) {
    super(JavaUI.getColorManager(), store, editor, IJavaPartitions.JAVA_PARTITIONING);
    this.mergeHover = mergeHover;
    this.undo = undo;
  }

  @Override
  public IUndoManager getUndoManager(ISourceViewer sv) {
    return undo;
  }

  @Override
  public int[] getConfiguredTextHoverStateMasks(ISourceViewer sv, String contentType) {
    int[] masks = super.getConfiguredTextHoverStateMasks(sv, contentType);
    if (masks == null || Arrays.stream(masks).anyMatch(m -> m == DEFAULT)) {
      return masks; // null: SourceViewer asks getTextHover(sv, contentType) for the default mask
    }
    int[] more = Arrays.copyOf(masks, masks.length + 1);
    more[masks.length] = DEFAULT;
    return more;
  }

  @Override
  public ITextHover getTextHover(ISourceViewer sv, String contentType, int stateMask) {
    ITextHover java = super.getTextHover(sv, contentType, stateMask);
    // JDT's combined hover sits under SWT.NONE, which a plain mouse hover asks for before the default mask
    return stateMask == DEFAULT || stateMask == SWT.NONE ? new MergeTextHover(mergeHover, java) : java;
  }

  @Override
  public ITextHover getTextHover(ISourceViewer sv, String contentType) {
    return new MergeTextHover(mergeHover, super.getTextHover(sv, contentType));
  }
}
