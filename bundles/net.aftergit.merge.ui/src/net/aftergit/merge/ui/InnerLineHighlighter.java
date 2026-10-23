package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;

/**
 * Paints word-level backgrounds on top of the syntax colors of a pane, every range in its own color, and the grey
 * struck-through text of a side not taken.
 */
final class InnerLineHighlighter implements ITextPresentationListener {
  /** A styled range of the document: a background, or a foreground with strikeout (a side not taken). */
  record Span(int offset, int length, Color color, Color foreground, boolean strikeout) {
    Span(int offset, int length, Color color) {
      this(offset, length, color, null, false);
    }
  }

  private final SourceViewer viewer;
  private List<Span> spans = List.of();
  private boolean reconcilerInstalled;
  private IDocument paintedDocument;
  /**
   * Moves the stored spans with every edit, as the widget moves its styles: a keystroke in front of a changed word
   * then leaves the spans equal to the ones the next refresh computes, and nothing has to be presented again.
   */
  private final IDocumentListener follower = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
      // the spans move once the change is made
    }

    @Override
    public void documentChanged(DocumentEvent event) {
      String text = event.getText();
      spans = shifted(spans, event.getOffset(), event.getLength(), text == null ? 0 : text.length());
    }
  };

  InnerLineHighlighter(SourceViewer viewer) {
    this.viewer = viewer;
    forget(viewer.getDocument());
    viewer.addTextPresentationListener(this);
    viewer.addTextInputListener(new ITextInputListener() {
      @Override
      public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
        forget(newInput);
      }

      @Override
      public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
        // forgotten before the switch
      }
    });
  }

  /** A new document starts without our styles: nothing stored may be painted on it. */
  private void forget(IDocument doc) {
    if (paintedDocument != null) {
      paintedDocument.removeDocumentListener(follower);
    }
    paintedDocument = doc;
    spans = List.of();
    if (doc != null) {
      doc.addDocumentListener(follower);
    }
  }

  /**
   * {@code spans} after {@code removed} characters at {@code offset} were replaced by {@code inserted} ones: a span
   * behind the change moves, a span the change falls inside grows or shrinks, a span whose text is gone is dropped.
   */
  static List<Span> shifted(List<Span> spans, int offset, int removed, int inserted) {
    if (spans.isEmpty()) {
      return spans;
    }
    int delta = inserted - removed;
    int changeEnd = offset + removed;
    List<Span> out = new ArrayList<>(spans.size());
    for (Span s : spans) {
      int start = s.offset();
      int end = start + s.length();
      int newStart = start >= changeEnd ? start + delta : start <= offset ? start : offset + inserted;
      int newEnd = end <= offset ? end : end >= changeEnd ? end + delta : offset;
      if (newEnd > newStart) {
        out.add(newStart == start && newEnd == end ? s
            : new Span(newStart, newEnd - newStart, s.color(), s.foreground(), s.strikeout()));
      }
    }
    return List.copyOf(out);
  }

  /**
   * The part of the document whose look differs between {@code before} and {@code after} ({offset, length}), clipped
   * to {@code documentLength}; null when there is none.
   */
  static int[] changedRegion(List<Span> before, List<Span> after, int documentLength) {
    Map<Span, Integer> counts = new HashMap<>();
    for (Span s : before) {
      counts.merge(s, 1, Integer::sum);
    }
    for (Span s : after) {
      counts.merge(s, -1, Integer::sum);
    }
    int from = Integer.MAX_VALUE;
    int to = Integer.MIN_VALUE;
    for (Map.Entry<Span, Integer> e : counts.entrySet()) {
      if (e.getValue() != 0) {
        from = Math.min(from, e.getKey().offset());
        to = Math.max(to, e.getKey().offset() + e.getKey().length());
      }
    }
    if (from > to && !before.equals(after)) {
      // the same spans in another order: where they overlap, the later one is on top
      for (Span s : after) {
        from = Math.min(from, s.offset());
        to = Math.max(to, s.offset() + s.length());
      }
    }
    from = Math.max(0, from);
    to = Math.min(documentLength, to);
    return to > from ? new int[] {from, to - from} : null;
  }

  /** Call once a presentation reconciler is installed on the viewer: the listener path then does the painting. */
  void setReconcilerInstalled(boolean installed) {
    this.reconcilerInstalled = installed;
  }

  /** All {@code documentRanges} ({offset, length}) in one color. */
  void setRanges(List<int[]> documentRanges, Color background) {
    List<Span> s = new ArrayList<>();
    for (int[] r : documentRanges) {
      s.add(new Span(r[0], r[1], background));
    }
    setSpans(s);
  }

  void setSpans(List<Span> newSpans) {
    IDocument doc = viewer.getDocument();
    if (doc != paintedDocument) {
      forget(doc);
    }
    if (spans.equals(newSpans) && stillPainted()) {
      return;
    }
    Set<Color> oldBackgrounds = new HashSet<>();
    Set<Color> oldForegrounds = new HashSet<>();
    for (Span s : spans) {
      if (s.color() != null) {
        oldBackgrounds.add(s.color());
      }
      if (s.foreground() != null) {
        oldForegrounds.add(s.foreground());
      }
    }
    List<Span> before = spans;
    this.spans = List.copyOf(newSpans);
    if (reconcilerInstalled) {
      // only what looks different: a presentation of the whole document costs the UI thread a lot on long lines
      int[] region = doc == null ? null : changedRegion(before, spans, doc.getLength());
      if (region != null) {
        ((ITextViewerExtension2) viewer).invalidateTextPresentation(region[0], region[1]);
      }
      return;
    }
    // Plain viewer: touch only what we set, so styles set by anyone else survive.
    StyledText text = viewer.getTextWidget();
    if (text == null || text.isDisposed()) {
      return;
    }
    text.setRedraw(false);
    try {
      if (!oldBackgrounds.isEmpty() || !oldForegrounds.isEmpty()) {
        // styles move and grow with edits, so find ours by colour, not by stored offsets
        for (StyleRange existing : text.getStyleRanges(0, text.getCharCount(), true)) {
          boolean background = existing.background != null && oldBackgrounds.contains(existing.background);
          boolean struck = existing.strikeout && existing.foreground != null
              && oldForegrounds.contains(existing.foreground);
          if (background || struck) {
            StyleRange copy = (StyleRange) existing.clone();
            if (background) {
              copy.background = null;
            }
            if (struck) {
              copy.foreground = null;
              copy.strikeout = false;
              copy.strikeoutColor = null;
            }
            text.setStyleRange(copy);
          }
        }
      }
      for (Span s : spans) {
        paint(text, s);
      }
    } finally {
      text.setRedraw(true);
    }
  }

  /**
   * The widget still shows what was painted last. Setting the whole text of a widget drops every
   * style range, so equal ranges alone do not mean there is nothing to do.
   */
  private boolean stillPainted() {
    if (reconcilerInstalled) {
      return true; // the presentation listener repaints with every presentation
    }
    StyledText text = viewer.getTextWidget();
    if (text == null || text.isDisposed()) {
      return true;
    }
    for (Span s : spans) {
      int start = Math.max(0, s.offset());
      if (s.length() > 0 && start < text.getCharCount()) {
        StyleRange at = text.getStyleRangeAtOffset(start);
        if (at == null || (s.color() != null && !s.color().equals(at.background))
            || (s.foreground() != null && !(s.foreground().equals(at.foreground) && (at.strikeout || !s.strikeout())))) {
          return false;
        }
      }
    }
    return true;
  }

  /** Puts {@code span}'s attributes on its range, keeping all others. */
  private static void paint(StyledText text, Span span) {
    int start = Math.max(0, span.offset());
    int end = Math.min(text.getCharCount(), span.offset() + span.length());
    if (end <= start) {
      return;
    }
    int pos = start;
    for (StyleRange existing : text.getStyleRanges(start, end - start, true)) {
      int s = Math.max(existing.start, start);
      int e = Math.min(existing.start + existing.length, end);
      if (s > pos) {
        text.setStyleRange(fresh(pos, s - pos, span));
      }
      StyleRange copy = (StyleRange) existing.clone();
      copy.start = s;
      copy.length = e - s;
      apply(copy, span);
      text.setStyleRange(copy);
      pos = e;
    }
    if (pos < end) {
      text.setStyleRange(fresh(pos, end - pos, span));
    }
  }

  private static StyleRange fresh(int start, int length, Span span) {
    StyleRange r = new StyleRange(start, length, null, null);
    apply(r, span);
    return r;
  }

  private static void apply(StyleRange r, Span span) {
    if (span.color() != null) {
      r.background = span.color();
    }
    if (span.foreground() != null) {
      r.foreground = span.foreground();
    }
    if (span.strikeout()) {
      r.strikeout = true;
      r.strikeoutColor = span.foreground();
    }
  }

  @Override
  public void applyTextPresentation(TextPresentation presentation) {
    IRegion extent = presentation.getExtent();
    int from = extent == null ? 0 : extent.getOffset();
    int to = extent == null ? Integer.MAX_VALUE : from + extent.getLength();
    for (Span span : spans) {
      int s = Math.max(span.offset(), from);
      int e = Math.min(span.offset() + span.length(), to);
      if (e > s) {
        presentation.mergeStyleRange(fresh(s, e - s, span)); // applyStyle carries strikeout and its colour too
      }
    }
  }
}
