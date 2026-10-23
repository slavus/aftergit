package net.aftergit.merge.ui;

import java.util.List;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.Position;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.ResultDocument;

/**
 * Presents an Eclipse IDocument as the core ResultDocument and forwards user edits to the model.
 *
 * <p>Model lines end at '\n' only. A private document has a '\n'-only line tracker, so its lines are model
 * lines. A shared buffer (the document of a Java or text editor of the file) also splits lines at '\r' and
 * holds the file's line delimiter: for it every line position is found by scanning for '\n', chunks are
 * converted between '\n' and the delimiter, and a '\r' that a user edit leaves outside "\r\n" is replaced by
 * the delimiter right after that edit, so the panes keep showing model lines. That repair belongs to the edit:
 * it is reported as {@link EditListener#repaired}, not as a new edit, so it never becomes an undo step of its own.
 *
 * <p>While a client has stopped the document's listener notification, the document still announces every change
 * but reports only the last one when notification resumes. The adapter notices a second announcement before a
 * report and then maps the whole text from before the first change to the text after the last one, so the model
 * never misses a change; the edit listener sees the first announcement and the last report. One case is not
 * covered: if the model applies an edit of its own, or an untracked or held replacement runs, between such stopped
 * changes, the user changes before it are not mapped (the document text stays right, block ranges may not).
 */
public final class ResultDocumentAdapter implements ResultDocument {
  /**
   * Largest replacement (old plus new lines, without the lines it left as they were at its start and end) that is
   * mapped line by line. The line diff runs on the UI thread and can be slow on huge, repetitive input; a larger
   * replacement counts as one plain edit of its changed span.
   */
  static final int MAX_DIFFED_LINES = 5000;

  private final IDocument document;
  private final String eol;
  private final boolean shared;
  private MergeModel model;
  private LineChange pending;
  private List<String> pendingOld;
  /** The text before the first of the announced changes, while several await one report; else null. */
  private String coalescedFrom;
  /** The text before the change announced last. */
  private String pendingText;

  /** Notified about user edits (not the model's own edits, not untracked replacements). */
  public interface EditListener {
    void aboutToEdit(int offset, int length, String text);

    void edited(int offset, int length, String text);

    /** A lone '\r' left by the previous user edit was replaced by the line delimiter; part of that edit. */
    default void repaired(int offset, int length, String text) {}
  }

  private EditListener editListener;
  private boolean untracked;
  private int holds;
  private boolean repairing;

  private final IDocumentListener listener = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent e) {
      if (model == null || model.isApplyingOwnEdit() || untracked || holds > 0) {
        pending = null;
        pendingOld = null;
        coalescedFrom = null;
        pendingText = null;
        return;
      }
      String old = document.get();
      if (coalescedFrom != null || pendingText != null) {
        // the previous change was announced but not reported: notification is stopped, one report will follow
        coalescedFrom = coalescedFrom != null ? coalescedFrom : pendingText;
        pendingText = old;
        return;
      }
      String text = e.getText() == null ? "" : e.getText();
      if (editListener != null && !repairing) {
        editListener.aboutToEdit(e.getOffset(), e.getLength(), text);
      }
      pendingText = old;
      pending = LineChange.of(old, e.getOffset(), e.getLength(), text);
      // an edit across line ends, or of one line: remember its old lines, so only the lines that really change count
      // (a line changed in whitespace only does not change its block's state)
      pendingOld = pending != null && (pending.spansLines() || pending.withinOneLine())
          ? LineChange.lines(old, pending.startLine(), pending.endLine(), shared) : null;
    }

    @Override
    public void documentChanged(DocumentEvent e) {
      LineChange c = pending;
      List<String> old = pendingOld;
      String from = coalescedFrom;
      pending = null;
      pendingOld = null;
      coalescedFrom = null;
      pendingText = null;
      if (from != null && model != null && !model.isApplyingOwnEdit()) {
        String text = e.getText() == null ? "" : e.getText();
        resync(from);
        if (editListener != null && !repairing) {
          editListener.edited(e.getOffset(), e.getLength(), text);
        }
        if (shared && !repairing) {
          scheduleRepair(0, document.getLength()); // the earlier changes may have left a lone '\r' too
        }
        return;
      }
      if (c != null && model != null && !model.isApplyingOwnEdit()) {
        String text = e.getText() == null ? "" : e.getText();
        if (old != null) {
          map(c.startLine(), old,
              LineChange.lines(document.get(), c.startLine(), c.startLine() + c.newLineCount(), shared));
        } else {
          model.onExternalEdit(c.startLine(), c.endLine(), c.newLineCount());
        }
        if (repairing) {
          if (editListener != null) {
            editListener.repaired(e.getOffset(), e.getLength(), text);
          }
          return;
        }
        if (editListener != null) {
          editListener.edited(e.getOffset(), e.getLength(), text);
        }
        if (shared) {
          scheduleRepair(e.getOffset(), text.length());
        }
      }
    }
  };

  /** Maps several changes reported as one: the whole text before them became the whole text now. */
  private void resync(String before) {
    map(0, LineChange.lines(before, 0, Integer.MAX_VALUE, shared),
        LineChange.lines(document.get(), 0, Integer.MAX_VALUE, shared));
  }

  /**
   * Tells the model that lines [start, start + was.size()) became {@code now}. Lines equal at the start and at the end
   * are left out first, so only the changed middle counts against {@link #MAX_DIFFED_LINES}; a middle over it is one
   * plain edit of the middle, and blocks outside it stay as they are.
   */
  private void map(int start, List<String> was, List<String> now) {
    int p = 0;
    int max = Math.min(was.size(), now.size());
    while (p < max && was.get(p).equals(now.get(p))) {
      p++;
    }
    int q = 0;
    while (q < max - p && was.get(was.size() - 1 - q).equals(now.get(now.size() - 1 - q))) {
      q++;
    }
    List<String> wasMid = was.subList(p, was.size() - q);
    List<String> nowMid = now.subList(p, now.size() - q);
    if ((long) wasMid.size() + nowMid.size() <= MAX_DIFFED_LINES) {
      model.onExternalReplace(start + p, wasMid, nowMid);
    } else {
      model.onExternalEdit(start + p, start + p + wasMid.size(), nowMid.size());
    }
  }

  /** Adapter for a private document whose only line delimiter is '\n'. */
  public ResultDocumentAdapter(IDocument document) {
    this(document, "\n", false);
  }

  private ResultDocumentAdapter(IDocument document, String eol, boolean shared) {
    this.document = document;
    this.eol = eol;
    this.shared = shared;
  }

  /** Adapter for a shared buffer whose lines end with {@code eol} ("\n" or "\r\n"). */
  public static ResultDocumentAdapter forSharedBuffer(IDocument document, String eol) {
    return new ResultDocumentAdapter(document, eol, true);
  }

  public String eol() {
    return eol;
  }

  public boolean isShared() {
    return shared;
  }

  public void setEditListener(EditListener listener) {
    this.editListener = listener;
  }

  /** Runs a document replacement that the model must not see as a user edit (undo/redo, save). */
  public void runUntracked(Runnable r) {
    untracked = true;
    try {
      r.run();
    } finally {
      untracked = false;
    }
  }

  /**
   * Stops forwarding document changes to the model until the returned action runs; whoever holds it puts the
   * text the model describes back before releasing.
   */
  public Runnable hold() {
    holds++;
    boolean[] released = {false};
    return () -> {
      if (!released[0]) {
        released[0] = true;
        holds--;
      }
    };
  }

  public IDocument document() {
    return document;
  }

  /** Binds the model to this document (texts must be equal) and starts forwarding user edits. */
  public void connect(MergeModel newModel) {
    newModel.rebindDocument(this);
    this.model = newModel;
    document.addDocumentListener(listener);
  }

  public void disconnect() {
    document.removeDocumentListener(listener);
    model = null;
  }

  @Override
  public int lineCount() {
    String text = document.get();
    if (text.isEmpty()) {
      return 0;
    }
    int n = count(text);
    return text.endsWith("\n") ? n : n + 1;
  }

  @Override
  public String chunk(int startLine, int endLine) {
    if (startLine >= endLine) {
      return "";
    }
    try {
      int from = lineOffset(startLine, false);
      int to = lineOffset(endLine, true);
      String s = document.get(from, to - from);
      if (shared) {
        s = s.replace("\r\n", "\n");
        if (s.endsWith("\r")) {
          s = s.substring(0, s.length() - 1); // a trailing lone '\r' ends the last line; the core never sees '\r'
        }
      }
      return s.endsWith("\n") ? s : s + "\n";
    } catch (BadLocationException e) {
      throw new IllegalArgumentException("Lines " + startLine + ".." + endLine + " out of range", e);
    }
  }

  @Override
  public void replaceLines(int startLine, int endLine, String chunk) {
    try {
      int from = lineOffset(startLine, true);
      int to = lineOffset(endLine, true);
      String insert = shared ? chunk.replace("\n", eol) : chunk;
      String text = document.get();
      // replacing up to an unterminated last line with a '\n'-terminated chunk terminates it; that is intended
      if (from == text.length() && from > 0 && !chunk.isEmpty() && !text.endsWith("\n")) {
        if (shared && text.endsWith("\r")) {
          from--; // the trailing lone '\r' becomes the delimiter instead of staying in front of it
        }
        insert = eol + insert;
      }
      document.replace(from, to - from, insert);
    } catch (BadLocationException e) {
      throw new IllegalArgumentException("Lines " + startLine + ".." + endLine + " out of range", e);
    }
  }

  /**
   * Offset where model line {@code line} starts. With {@code lenient} a line at or after the end gives the
   * document length.
   */
  private int lineOffset(int line, boolean lenient) throws BadLocationException {
    if (!shared) {
      return lenient && line >= document.getNumberOfLines() ? document.getLength() : document.getLineOffset(line);
    }
    String text = document.get();
    int at = 0;
    for (int l = 0; l < line; l++) {
      int nl = text.indexOf('\n', at);
      if (nl < 0) {
        if (lenient) {
          return text.length();
        }
        throw new BadLocationException("Line " + line);
      }
      at = nl + 1;
    }
    return at;
  }

  /**
   * After all listeners ran, replaces the lone '\r's that the edit at [offset, offset+insertedLength) left (a
   * deleted '\n' can orphan the '\r' before it) by {@link #eol}, in one replacement. A position keeps the span
   * valid across other post-notification replacements, and each '\r' is checked again when the repair runs.
   */
  private void scheduleRepair(int offset, int insertedLength) {
    String text = document.get();
    int first = -1;
    int last = -1;
    for (int i = Math.max(0, offset - 1); i < Math.min(text.length(), offset + insertedLength + 1); i++) {
      if (isLone(text, i)) {
        first = first < 0 ? i : first;
        last = i;
      }
    }
    if (first < 0 || !(document instanceof IDocumentExtension ext)) {
      return;
    }
    Position span = new Position(first, last + 1 - first);
    try {
      document.addPosition(span);
    } catch (BadLocationException e) {
      return; // offsets come from the document's own text
    }
    ext.registerPostNotificationReplace(listener, (doc, owner) -> {
      doc.removePosition(span);
      if (span.isDeleted() || model == null) {
        return;
      }
      repair(doc, span.getOffset(), span.getOffset() + span.getLength());
    });
  }

  private void repair(IDocument doc, int from, int to) {
    String text = doc.get();
    to = Math.min(to, text.length());
    int first = -1;
    int last = -1;
    for (int i = from; i < to; i++) {
      if (isLone(text, i)) {
        first = first < 0 ? i : first;
        last = i;
      }
    }
    if (first < 0) {
      return;
    }
    StringBuilder fixed = new StringBuilder();
    for (int i = first; i <= last; i++) {
      fixed.append(isLone(text, i) ? eol : String.valueOf(text.charAt(i)));
    }
    repairing = true;
    try {
      doc.replace(first, last + 1 - first, fixed.toString());
    } catch (BadLocationException e) {
      // cannot happen: the span was just read from the text
    } finally {
      repairing = false;
    }
  }

  private static boolean isLone(String text, int i) {
    return text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n');
  }

  private static int count(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }
}
