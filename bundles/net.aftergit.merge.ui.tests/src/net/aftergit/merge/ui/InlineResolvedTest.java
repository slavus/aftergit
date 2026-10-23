package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A resolved block highlights in each side the words in which it differs from the result, and in the result the
 * words in which it differs from a side; a thin mark stands where a text lacks words of the other (inline-resolved
 * design).
 */
class InlineResolvedTest {
  static final String BASE = "a\nint total = price * qty;\nc\n";
  static final String OURS = "a\nint total = price * qty + tax;\nc\n";
  static final String THEIRS = "a\nlong total = price * qty;\nc\n";

  Shell shell;
  MergeViewer viewer;
  MergeModel model;

  @AfterEach
  void close() {
    if (shell != null) {
      shell.dispose();
    }
  }

  private void open(String base, String ours, String theirs, ResolverPipeline pipeline) {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 600);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        pipeline).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    pump();
  }

  private void open() {
    open(BASE, OURS, THEIRS, ResolverPipeline.none());
  }

  private static void pump() {
    UiDriver.flush();
  }

  private StyledText text(PaneKind pane) {
    return viewer.pane(pane).text();
  }

  private int offset(PaneKind pane, int line, String word) {
    StyledText t = text(pane);
    int column = t.getLine(line).indexOf(word);
    assertTrue(column >= 0, word + " on line " + line + " of " + pane);
    return t.getOffsetAtLine(line) + column;
  }

  private Color bg(PaneKind pane, int line, String word) {
    StyleRange r = text(pane).getStyleRangeAtOffset(offset(pane, line, word));
    return r == null ? null : r.background;
  }

  private List<PanePainter.Insertion> marks(PaneKind pane) {
    return viewer.pane(pane).painter().insertions();
  }

  private void waitFor(java.util.function.BooleanSupplier condition, String what) {
    ConflictRepos.waitUntil(condition, 10, what);
  }

  private void take(boolean left) {
    viewer.selectBlock(0);
    if (left) {
      viewer.takeLeft();
    } else {
      viewer.takeRight();
    }
    pump();
  }

  private static Color taken() {
    return Palette.strong(LineStyle.Kind.MODIFIED);
  }

  @Test
  void takeLeftLeavesOursPlainAndHighlightsTheirsAndTheResultAgainstEachOther() {
    open();
    take(true);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    waitFor(() -> Palette.word(LineStyle.Kind.DROPPED).equals(bg(PaneKind.RIGHT, 1, "long")), "theirs' long");
    waitFor(() -> taken().equals(bg(PaneKind.RESULT, 1, "int")), "the result's int");
    assertNull(bg(PaneKind.LEFT, 1, "int"), "ours is the result: nothing differs");
    assertNull(bg(PaneKind.LEFT, 1, "+ tax"));
    assertEquals(List.of(), marks(PaneKind.LEFT));
    assertNull(bg(PaneKind.RIGHT, 1, "total"), "the same words stay plain");
    StyleRange struck = text(PaneKind.RIGHT).getStyleRangeAtOffset(offset(PaneKind.RIGHT, 1, "long"));
    assertTrue(struck.strikeout, "the dropped side stays struck through");
    assertEquals(Palette.wordText(LineStyle.Kind.DROPPED), struck.foreground, "the differing word is darker");
    assertNotEquals(Palette.strong(LineStyle.Kind.DROPPED), struck.foreground);
    assertEquals(Palette.strong(LineStyle.Kind.DROPPED), struck.strikeoutColor, "the strike keeps its colour");
    StyleRange same = text(PaneKind.RIGHT).getStyleRangeAtOffset(offset(PaneKind.RIGHT, 1, "total"));
    assertEquals(Palette.strong(LineStyle.Kind.DROPPED), same.foreground, "the rest keeps the struck text colour");
    assertEquals(List.of(new PanePainter.Insertion(offset(PaneKind.RIGHT, 1, ";"),
        Palette.strong(LineStyle.Kind.DROPPED))), marks(PaneKind.RIGHT), "theirs lacks + tax: a grey mark");
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "int"));
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "+ tax"));
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "tax"));
    assertNull(bg(PaneKind.RESULT, 1, "total"));
    assertEquals(List.of(), marks(PaneKind.RESULT));
  }

  @Test
  void takeRightMarksTheResultWhereItLacksWhatOursHas() {
    open();
    take(false);
    waitFor(() -> Palette.word(LineStyle.Kind.DROPPED).equals(bg(PaneKind.LEFT, 1, "int")), "ours' int");
    assertEquals(Palette.word(LineStyle.Kind.DROPPED), bg(PaneKind.LEFT, 1, "+ tax"));
    assertEquals(List.of(), marks(PaneKind.LEFT));
    assertNull(bg(PaneKind.RIGHT, 1, "long"), "theirs is the result");
    assertEquals(List.of(), marks(PaneKind.RIGHT));
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "long"));
    assertNull(bg(PaneKind.RESULT, 1, "total"));
    assertEquals(List.of(new PanePainter.Insertion(offset(PaneKind.RESULT, 1, ";"), Palette.takenStrong())),
        marks(PaneKind.RESULT), "a yellow mark in the result");
  }

  @Test
  void takeBothComparesTheJoinedLinesWithEachSide() {
    open();
    viewer.selectBlock(0);
    viewer.takeLeftThenRight();
    pump();
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "int"), "ours' line differs from theirs");
    assertEquals(taken(), bg(PaneKind.RESULT, 2, "long"), "theirs' line differs from ours");
    for (PaneKind side : List.of(PaneKind.LEFT, PaneKind.RIGHT)) {
      assertFalse(marks(side).isEmpty(), side + " lacks the other side's line");
      StyledText t = text(side);
      for (PanePainter.Insertion i : marks(side)) {
        assertEquals(1, t.getLineAtOffset(i.offset()), side + ": the mark stays on the block's line");
        assertEquals(Palette.takenStrong(), i.color(), side + ": both sides are taken, yellow");
      }
    }
  }

  @Test
  void typingInTheResultRefreshesTheHighlightsOfAnEditedBlock() throws Exception {
    open();
    take(true);
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    int at = doc.getLineOffset(1);
    doc.replace(at, "int".length(), "double");
    waitFor(() -> model.blocks().get(0).state() == BlockState.EDITED, "the block is edited");
    waitFor(() -> taken().equals(bg(PaneKind.RESULT, 1, "double")), "the typed word");
    waitFor(() -> Palette.word(LineStyle.Kind.DROPPED).equals(bg(PaneKind.LEFT, 1, "int")),
        "ours is dropped now and differs in int");
    assertEquals(Palette.word(LineStyle.Kind.DROPPED), bg(PaneKind.RIGHT, 1, "long"));
    assertNull(bg(PaneKind.LEFT, 1, "+ tax"), "ours' + tax is still in the result");
    int before = viewer.resultComparisons();
    doc.replace(doc.getLineOffset(1) + doc.getLineInformation(1).getLength() - 1, 0, " * 2");
    waitFor(() -> taken().equals(bg(PaneKind.RESULT, 1, "* 2")), "the words typed next");
    assertTrue(viewer.resultComparisons() > before, "the result changed: compared again");
    int after = viewer.resultComparisons();
    for (int i = 0; i < 5; i++) {
      viewer.refresh();
    }
    assertEquals(after, viewer.resultComparisons(), "nothing changed: the comparison is cached");
  }

  @Test
  void typingFastComparesOnceAfterAPause() throws Exception {
    open();
    take(true);
    viewer.setCompareDelay(1000);
    int n = viewer.resultComparisons();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    int end = doc.getLineOffset(1) + doc.getLineInformation(1).getLength() - 1; // in front of ';'
    for (String typed : new String[] {" ", "*", " ", "3"}) {
      doc.replace(end, 0, typed);
      end += typed.length();
      pump();
    }
    assertEquals(n, viewer.resultComparisons(), "no comparison while typing fast");
    assertEquals(List.of(), marks(PaneKind.RESULT), "no mark came up while typing");
    waitFor(() -> taken().equals(bg(PaneKind.RESULT, 1, "* 3")), "the typed words after the pause");
    assertEquals(n + 1, viewer.resultComparisons(), "one comparison for all four keystrokes");
  }

  @Test
  void aMarkMovesWithTypingInFrontOfIt() throws Exception {
    open();
    take(false);
    waitFor(() -> !marks(PaneKind.RESULT).isEmpty(), "the result's mark");
    viewer.setCompareDelay(60_000); // no comparison until the test is over
    viewer.refresh(); // the take's comparison: the next keystroke is within the delay
    int mark = marks(PaneKind.RESULT).get(0).offset();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLineOffset(1), 0, "xy");
    assertEquals(mark + 2, marks(PaneKind.RESULT).get(0).offset(), "the mark moved with the edit at once");
    pump();
    assertEquals(mark + 2, marks(PaneKind.RESULT).get(0).offset(), "and stays there until compared again");
  }

  @Test
  void aBlockWithTooManyLinesIsNotCompared() {
    StringBuilder ours = new StringBuilder("a\n");
    StringBuilder theirs = new StringBuilder("a\n");
    for (int i = 0; i <= MergeViewer.MAX_COMPARED_LINES; i++) {
      ours.append("o ").append(i).append('\n');
      theirs.append("t ").append(i).append('\n');
    }
    open("a\nb\nc\n", ours + "c\n", theirs + "c\n", ResolverPipeline.none());
    int n = viewer.resultComparisons();
    take(true);
    viewer.refresh();
    assertEquals(n, viewer.resultComparisons(), "above the bound no word is split");
    assertNull(bg(PaneKind.RIGHT, 1, "t"));
    assertEquals(List.of(), marks(PaneKind.RIGHT));
  }

  @Test
  void aBlockWithTooManyCharactersIsNotCompared() {
    String word = "w".repeat(999) + "\n";
    int lines = MergeViewer.MAX_COMPARED_CHARS / 3 / word.length() + 1;
    String ours = "a\n" + ("o" + word).repeat(lines) + "c\n";
    String theirs = "a\n" + ("t" + word).repeat(lines) + "c\n";
    open("a\nb\nc\n", ours, theirs, ResolverPipeline.none());
    int n = viewer.resultComparisons();
    take(true);
    for (int i = 0; i < 3; i++) {
      viewer.refresh();
    }
    assertEquals(n, viewer.resultComparisons(), "above the bound no word is split, and it is remembered");
    assertEquals(List.of(), marks(PaneKind.RIGHT));
  }

  @Test
  void anEmptySideLineGetsOneMarkOnItsOwnLine() {
    open("a\nb\nc\n", "a\n\nc\n", "a\nx = 1;\nc\n", ResolverPipeline.none());
    take(false);
    waitFor(() -> !marks(PaneKind.LEFT).isEmpty(), "a mark on the empty line");
    assertEquals(List.of(new PanePainter.Insertion(text(PaneKind.LEFT).getOffsetAtLine(1),
        Palette.strong(LineStyle.Kind.DROPPED))), marks(PaneKind.LEFT));
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "x = 1;"));
    assertEquals(taken(), bg(PaneKind.RESULT, 1, ";"));
  }

  @Test
  void aMultiLineBlockHighlightsEachLine() {
    open("a\nx = 0;\ny = 0;\nc\n", "a\nx = 1;\ny = 5;\nc\n", "a\nx = 2;\ny = 6;\nc\n", ResolverPipeline.none());
    take(true);
    waitFor(() -> taken().equals(bg(PaneKind.RESULT, 2, "5")), "the second line");
    assertEquals(taken(), bg(PaneKind.RESULT, 1, "1"));
    assertNull(bg(PaneKind.RESULT, 1, "x"));
    assertEquals(Palette.word(LineStyle.Kind.DROPPED), bg(PaneKind.RIGHT, 1, "2"));
    assertEquals(Palette.word(LineStyle.Kind.DROPPED), bg(PaneKind.RIGHT, 2, "6"));
    assertNull(bg(PaneKind.LEFT, 2, "5"));
  }

  @Test
  void theInnerLinePreferenceTurnsTheHighlightsAndMarksOff() {
    open();
    take(false);
    waitFor(() -> !marks(PaneKind.RESULT).isEmpty(), "the result's mark");
    boolean old = MergePreferences.innerLine();
    try {
      MergePreferences.setInnerLine(false);
      waitFor(() -> !viewer.innerLineEnabled(), "the viewer follows the preference");
      waitFor(() -> bg(PaneKind.RESULT, 1, "long") == null, "the result's words are plain");
      assertNull(bg(PaneKind.RESULT, 1, "long"));
      assertNull(bg(PaneKind.LEFT, 1, "int"));
      for (PaneKind p : PaneKind.values()) {
        assertEquals(List.of(), marks(p), p + ": no marks");
      }
      assertTrue(text(PaneKind.LEFT).getStyleRangeAtOffset(offset(PaneKind.LEFT, 1, "int")).strikeout,
          "the dropped side is not a word difference: it stays struck");
      MergePreferences.setInnerLine(true);
      waitFor(() -> viewer.innerLineEnabled(), "back on");
      waitFor(() -> taken().equals(bg(PaneKind.RESULT, 1, "long")), "the highlights come back");
      assertFalse(marks(PaneKind.RESULT).isEmpty());
    } finally {
      MergePreferences.setInnerLine(old);
      pump();
    }
  }

  @Test
  void anAutoResolvedBlockComparesItsSidesWithTheMergedResult() {
    open(BASE, OURS, THEIRS, ResolverPipeline.defaults(false));
    viewer.rerunAutoResolve();
    pump();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertEquals("long total = price * qty + tax;\n", model.resultText(0));
    Color word = Palette.word(LineStyle.Kind.AUTO);
    assertTrue(Palette.contrast(word.getRGB(), Palette.light(LineStyle.Kind.AUTO).getRGB()) >= 2.0,
        "the words stand out from the auto fill");
    assertTrue(Palette.contrast(word.getRGB(), Palette.autoHatch().getRGB()) >= 1.5,
        "and from the review stripes");
    waitFor(() -> word.equals(bg(PaneKind.LEFT, 1, "int")), "ours' int");
    assertNull(bg(PaneKind.LEFT, 1, "+ tax"), "ours' + tax is in the result");
    assertNull(bg(PaneKind.RIGHT, 1, "long"), "theirs' long is in the result");
    assertEquals(List.of(new PanePainter.Insertion(offset(PaneKind.RIGHT, 1, ";"),
        Palette.strong(LineStyle.Kind.AUTO))), marks(PaneKind.RIGHT), "theirs lacks + tax");
    assertEquals(word, bg(PaneKind.RESULT, 1, "long"));
    assertEquals(word, bg(PaneKind.RESULT, 1, "+ tax"));
    assertNull(bg(PaneKind.RESULT, 1, "total"));
    assertEquals(List.of(), marks(PaneKind.RESULT));
  }

  @Test
  void anUnresolvedConflictKeepsItsWordsAgainstTheBase() {
    open();
    Color conflict = Palette.strong(LineStyle.Kind.CONFLICT);
    assertEquals(conflict, bg(PaneKind.LEFT, 1, "+ tax"));
    assertNull(bg(PaneKind.LEFT, 1, "total"));
    assertEquals(conflict, bg(PaneKind.RIGHT, 1, "long"));
    for (PaneKind p : PaneKind.values()) {
      assertEquals(List.of(), marks(p), p + ": no marks on an open conflict");
    }
  }
}
