package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InnerLineHighlighterTest {
  Shell shell;
  SourceViewer viewer;
  InnerLineHighlighter highlighter;
  Color red;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent());
    viewer = new SourceViewer(shell, null, SWT.NONE);
    viewer.setDocument(new Document("abc def\nghi\n"));
    highlighter = new InnerLineHighlighter(viewer);
    red = Display.getCurrent().getSystemColor(SWT.COLOR_RED);
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  private Color bgAt(int offset) {
    var r = viewer.getTextWidget().getStyleRangeAtOffset(offset);
    return r == null ? null : r.background;
  }

  @Test
  void repaintsSameRangesAfterTheWidgetDroppedItsStyles() {
    highlighter.setRanges(List.of(new int[] {4, 3}), red);
    assertEquals(red, bgAt(5));
    viewer.getDocument().set(viewer.getDocument().get()); // a text set resets every StyleRange
    assertNull(bgAt(5));
    highlighter.setRanges(List.of(new int[] {4, 3}), red);
    assertEquals(red, bgAt(5));
  }

  @Test
  void newInputDocumentForgetsStoredRanges() {
    highlighter.setRanges(List.of(new int[] {4, 3}), red);
    viewer.setDocument(new Document("abc def\nghi\n"));
    TextPresentation p = new TextPresentation(new Region(0, 12), 4);
    highlighter.applyTextPresentation(p);
    assertTrue(p.isEmpty(), "nothing stale is painted on the new document");
  }

  @Test
  void spansKeepTheirOwnColorsAndOldColorsGoAway() {
    Color blue = Display.getCurrent().getSystemColor(SWT.COLOR_BLUE);
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(0, 3, red), new InnerLineHighlighter.Span(4, 3, blue)));
    assertEquals(red, bgAt(1));
    assertEquals(blue, bgAt(5));
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(4, 3, red)));
    assertNull(bgAt(1), "the old red range is gone");
    assertEquals(red, bgAt(5));
  }

  @Test
  void aSpanCanGreyAndStrikeTheTextWithoutABackground() {
    var grey = Display.getCurrent().getSystemColor(SWT.COLOR_GRAY);
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(0, 3, null, grey, true)));
    StyleRange r = viewer.getTextWidget().getStyleRangeAtOffset(1);
    assertNotNull(r);
    assertTrue(r.strikeout);
    assertEquals(grey, r.foreground);
    assertNull(r.background);
    highlighter.setSpans(List.of());
    StyleRange after = viewer.getTextWidget().getStyleRangeAtOffset(1);
    assertTrue(after == null || (!after.strikeout && after.foreground == null), "nothing struck is left");
  }

  @Test
  void theStrikeoutAlsoGoesIntoATextPresentation() {
    var grey = Display.getCurrent().getSystemColor(SWT.COLOR_GRAY);
    highlighter.setReconcilerInstalled(true);
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(0, 3, null, grey, true)));
    TextPresentation p = new TextPresentation(new Region(0, 12), 4);
    highlighter.applyTextPresentation(p);
    StyleRange r = p.getFirstStyleRange();
    assertNotNull(r);
    assertTrue(r.strikeout);
    assertEquals(grey, r.foreground);
    assertNull(r.background);
  }

  @Test
  void spansMoveWithTheEditsLikeTheWidgetsStyles() {
    var span = new InnerLineHighlighter.Span(10, 4, red);
    assertEquals(List.of(span), InnerLineHighlighter.shifted(List.of(span), 14, 0, 2), "typed behind: stays");
    assertEquals(List.of(new InnerLineHighlighter.Span(12, 4, red)),
        InnerLineHighlighter.shifted(List.of(span), 10, 0, 2), "typed in front: moves");
    assertEquals(List.of(new InnerLineHighlighter.Span(7, 4, red)),
        InnerLineHighlighter.shifted(List.of(span), 2, 3, 0), "deleted in front: moves back");
    assertEquals(List.of(new InnerLineHighlighter.Span(10, 6, red)),
        InnerLineHighlighter.shifted(List.of(span), 12, 0, 2), "typed inside: grows");
    assertEquals(List.of(new InnerLineHighlighter.Span(10, 1, red)),
        InnerLineHighlighter.shifted(List.of(span), 11, 5, 0), "its end deleted: shrinks");
    assertEquals(List.of(new InnerLineHighlighter.Span(10, 3, red)),
        InnerLineHighlighter.shifted(List.of(span), 8, 3, 2), "its start replaced: starts behind the new text");
    assertEquals(List.of(), InnerLineHighlighter.shifted(List.of(span), 9, 6, 1), "its text gone: dropped");
  }

  @Test
  void theChangedRegionCoversOnlyTheSpansThatDiffer() {
    var a = new InnerLineHighlighter.Span(0, 3, red);
    var b = new InnerLineHighlighter.Span(20, 4, red);
    var c = new InnerLineHighlighter.Span(40, 2, red);
    assertNull(InnerLineHighlighter.changedRegion(List.of(a, b, c), List.of(a, b, c), 100));
    assertArrayEquals(new int[] {20, 4}, InnerLineHighlighter.changedRegion(List.of(a, b, c), List.of(a, c), 100));
    var moved = new InnerLineHighlighter.Span(30, 4, red);
    assertArrayEquals(new int[] {20, 14}, InnerLineHighlighter.changedRegion(List.of(a, b), List.of(a, moved), 100),
        "old and new extent");
    assertArrayEquals(new int[] {40, 1}, InnerLineHighlighter.changedRegion(List.of(), List.of(c), 41),
        "clipped to the document");
  }

  @Test
  void withAReconcilerAKeystrokeInFrontOfASpanPresentsNothingAndAChangePresentsOnlyItsRegion() {
    viewer.setDocument(new Document("abc def\nghi jkl\nmno pqr\n"));
    highlighter.setReconcilerInstalled(true);
    List<int[]> invalidated = new ArrayList<>();
    ITextListener listener = e -> {
      if (e.getDocumentEvent() == null) {
        invalidated.add(new int[] {e.getOffset(), e.getLength()});
      }
    };
    viewer.addTextListener(listener);
    var ghi = new InnerLineHighlighter.Span(8, 3, red);
    var pqr = new InnerLineHighlighter.Span(20, 3, red);
    highlighter.setSpans(List.of(ghi, pqr));
    assertEquals(1, invalidated.size());
    assertArrayEquals(new int[] {8, 15}, invalidated.get(0), "the extent of the new spans, not the document");
    invalidated.clear();

    viewer.getDocument().set("x" + viewer.getDocument().get()); // replaces everything: the spans go with the text
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(9, 3, red), new InnerLineHighlighter.Span(21, 3, red)));
    invalidated.clear();
    try {
      viewer.getDocument().replace(0, 0, "y"); // a keystroke in front of both
    } catch (org.eclipse.jface.text.BadLocationException e) {
      throw new AssertionError(e);
    }
    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(10, 3, red), new InnerLineHighlighter.Span(22, 3, red)));
    assertEquals(List.of(), invalidated, "the spans moved with the keystroke: nothing to present again");

    highlighter.setSpans(List.of(new InnerLineHighlighter.Span(10, 3, red)));
    assertEquals(1, invalidated.size());
    assertArrayEquals(new int[] {22, 3}, invalidated.get(0), "only the span that went away");
    TextPresentation p = new TextPresentation(new Region(0, viewer.getDocument().getLength()), 4);
    highlighter.applyTextPresentation(p);
    StyleRange r = p.getFirstStyleRange();
    assertEquals(10, r.start, "the span is painted where it moved to");
    viewer.removeTextListener(listener);
  }
}
