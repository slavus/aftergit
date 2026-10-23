package net.aftergit.merge.ui;

import java.util.function.Supplier;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Composite;

/** How the panes of one resolver are built: plain text (default) or a language integration such as Java. */
interface PaneSetup {
  /**
   * Creates and configures the text viewer of a pane in {@code parent}. {@code rulers} returns a new vertical
   * ruler with line numbers on each call, so a setup that gives up on one viewer can build another.
   */
  SourceViewer createViewer(PaneKind kind, Composite parent, Supplier<CompositeRuler> rulers, int styles,
      PaneServices services);

  /** Document of a side pane: '\n' is its only line delimiter. */
  IDocument sideDocument(String text);

  /** Document of the result, holding {@code modelChunk} (model lines) in {@link #resultDelimiter()} form. */
  IDocument resultDocument(String modelChunk);

  /** Line delimiter of the result document: "\n" for a private document, the file's for a shared buffer. */
  String resultDelimiter();

  /** Shows the result document in the result viewer. */
  void showResult(SourceViewer viewer, IDocument document);

  /** True when the pane's viewer has a presentation reconciler (word highlights then go through it). */
  boolean presentationReconciled(PaneKind kind);

  /** The result pane gained or lost the keyboard focus. */
  void resultFocusChanged(boolean focused);

  /** Context to activate while the result has the focus, or null. */
  String resultContextId();

  /** Text for the status line when the panes work with less than they could, or null. */
  String notice();

  ResultKind resultKind();

  /** Errors and warnings of the result, sorted by offset. */
  default java.util.List<ResultProblem> problems() {
    return java.util.List.of();
  }

  /**
   * Compile errors of ours or theirs by model line (spec §5.4), or null while unknown (none for plain text). Stays
   * null when they cannot be found out: no block is then marked as not compiling.
   */
  default java.util.List<LineProblem> sideErrors(ThreeSide side) {
    return null;
  }

  /**
   * The result's errors that "new errors" compares with {@link #sideErrors} (spec §5.4), by model line, or null while
   * unknown; {@code shown} are those of {@link #problems()}. A language server checks the result as it checks ours
   * and theirs, so that the three are compared alike (languages design, Part 2).
   */
  default java.util.List<LineProblem> comparedErrors(java.util.List<LineProblem> shown) {
    return shown;
  }

  /**
   * True while a language server has not answered for the result's current text or for ours and theirs (languages
   * design, Part 2); Save &amp; Mark Resolved waits up to 2 s for it. Java's errors are never pending here.
   */
  default boolean diagnosticsPending() {
    return false;
  }

  /** Called on any thread when {@link #problems()} or {@link #sideErrors} may have changed. */
  default void addProblemListener(Runnable listener) {
    // plain text has no problems
  }

  /**
   * After a change of the result's body (Take, revert, auto-resolution), inside the undo step of that change: lets
   * the language integration adjust the result (Java: its imports, spec §5.3). {@code before} is the result text
   * (model lines) before the change; edits go through the model. Runs on the UI thread.
   */
  default void afterBodyChange(MergeModel model, String before) {
    // plain text has nothing to adjust
  }

  /** The shared buffer that holds the result, or null when the result is a private document. */
  default SharedBuffer sharedBuffer() {
    return null;
  }

  /** Adapters of the result (e.g. the Java editor's insert mode for JDT's smart auto edits), or null. */
  default <T> T getAdapter(Class<T> type) {
    return null;
  }

  /** Releases everything the setup holds; called once, when the resolver closes. */
  void dispose();
}
