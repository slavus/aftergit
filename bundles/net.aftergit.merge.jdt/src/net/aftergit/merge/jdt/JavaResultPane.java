package net.aftergit.merge.jdt;

import java.util.List;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.ui.texteditor.ITextEditorExtension3;

/** The Java result pane: the full Java editor or its degraded form (spec §7.2). */
public interface JavaResultPane {
  SourceViewer viewer();

  /** Shows {@code document}; for the full editor it must be the editor's own (shared) document. */
  void attach(IDocument document);

  boolean isFullEditor();

  /** Errors and warnings of the result, sorted by offset. */
  List<Problem> problems();

  /** Called when {@link #problems()} may have changed; on any thread. */
  void addProblemListener(Runnable listener);

  /** The result pane gained or lost the focus: the editor's key bindings follow it. */
  void setFocused(boolean focused);

  /** The working copy that holds the result's text, or null (Plan 3b: import rewrite, block diagnostics). */
  ICompilationUnit workingCopy();

  /** Content assist proposals at {@code offset}; empty without the editor. */
  ICompletionProposal[] completionProposals(int offset);

  /** Quick fix and quick assist proposals at {@code offset}; empty without the editor. */
  ICompletionProposal[] quickAssistProposals(int offset);

  /** The editor's insert-mode extension (smart insert for JDT's auto edits), or null. */
  ITextEditorExtension3 textEditorExtension();

  void dispose();
}
