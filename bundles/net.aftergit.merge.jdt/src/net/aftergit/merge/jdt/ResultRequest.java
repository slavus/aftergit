package net.aftergit.merge.jdt;

import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorSite;

/**
 * What the merge UI gives the result pane. {@code rulers} returns a new ruler with line numbers on each call;
 * {@code site} is the merge editor's site, or null when the result is private (no full Java editor then).
 */
public record ResultRequest(Composite parent, Supplier<CompositeRuler> rulers, int styles, IFile file,
    IEditorSite site, ITextHover mergeHover, IUndoManager undoManager) {}
