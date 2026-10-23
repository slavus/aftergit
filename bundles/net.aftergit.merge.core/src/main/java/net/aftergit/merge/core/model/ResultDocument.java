package net.aftergit.merge.core.model;

/**
 * Line-oriented view of the merge result, adapted by the UI from its text widget's document.
 *
 * <p><b>Line model.</b> The document is a sequence of lines. Every chunk passed to or returned by this
 * interface is a run of whole lines, each terminated by exactly one {@code '\n'}; an empty chunk
 * means no lines. Lines never contain {@code '\n'}. The owner of the document (the UI adapter)
 * translates its own line delimiters (for example {@code "\r\n"}) to and from {@code '\n'} and
 * handles a possibly unterminated last line itself (it is presented here as a terminated line).
 *
 * <p><b>External edits.</b> When the user changes the text, the owner reports the change to
 * {@link MergeModel#onExternalEdit(int, int, int)} as whole lines, see there.
 *
 * <p><b>Own edits.</b> The model changes the text only through {@link #replaceLines}; the owner must
 * ignore the change notification that results while {@link MergeModel#isApplyingOwnEdit()} is true
 * (the model also ignores such a callback itself).
 */
public interface ResultDocument {
  /** Number of lines. */
  int lineCount();

  /** Lines [startLine, endLine), each terminated by {@code '\n'}. */
  String chunk(int startLine, int endLine);

  /** Replaces lines [startLine, endLine) by the lines of {@code chunk} (whole lines, '\n'-terminated). */
  void replaceLines(int startLine, int endLine, String chunk);
}
