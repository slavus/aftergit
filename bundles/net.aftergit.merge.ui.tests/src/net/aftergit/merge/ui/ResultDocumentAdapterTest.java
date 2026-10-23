package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.Test;

class ResultDocumentAdapterTest {
  static MergeModel model() {
    return HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\n", "a\nL\nc\nd\n", "a\nR\nc\nR3\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
  }

  static ResultDocumentAdapter connect(MergeModel m) {
    Document d = new Document(m.document().chunk(0, m.document().lineCount()));
    ResultDocumentAdapter a = new ResultDocumentAdapter(d);
    a.connect(m);
    return a;
  }

  @Test
  void chunksAndLineCountMatchTheModelContract() {
    Document d = new Document("a\nb\n");
    ResultDocumentAdapter a = new ResultDocumentAdapter(d);
    assertEquals(2, a.lineCount());
    assertEquals("b\n", a.chunk(1, 2));
    a.replaceLines(0, 1, "x\ny\n");
    assertEquals("x\ny\nb\n", d.get());
    Document u = new Document("a\nb");
    new ResultDocumentAdapter(u).replaceLines(2, 2, "x\n");
    assertEquals("a\nb\nx\n", u.get());
  }

  @Test
  void modelOperationsGoThroughTheDocument() {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\nL\nc\nR3\n", a.document().get());
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
  }

  @Test
  void typingInsideABlockMarksItEdited() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.document().replace(2, 0, "X"); // inside "b" of the conflict block
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
  }

  @Test
  void whitespaceTypedInsideABlockLeavesItsState() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.document().replace(2, 0, "  "); // indents "b" of the conflict block
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals("  b\n", m.resultText(0));
    a.document().replace(4, 0, "X");
    assertEquals(BlockState.EDITED, m.blocks().get(0).state(), "a real change edits the block");
  }

  @Test
  void whitespaceTypedInsideAResolvedBlockEditsIt() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    m.take(0, ThreeSide.LEFT);
    a.document().replace(2, 0, "  "); // indents "L": in Python that changes what the line means
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
  }

  @Test
  void insertingLinesAboveShiftsBlocks() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.document().replace(0, 0, "new\n");
    assertEquals(2, m.blocks().get(0).resultStart());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
  }

  @Test
  void deletingFinalNewlineKeepsChunksTerminated() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    String t = a.document().get();
    a.document().replace(t.length() - 1, 1, "");
    assertTrue(a.chunk(0, a.lineCount()).endsWith("\n"));
    assertEquals(4, a.lineCount());
    assertEquals(3, m.blocks().get(1).resultStart());
    assertEquals(4, m.blocks().get(1).resultEnd());
  }

  @Test
  void disconnectStopsForwarding() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.disconnect();
    a.document().replace(2, 0, "X");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
  }

  static ResultDocumentAdapter connectCrlf(MergeModel m) {
    Document d = new Document(m.document().chunk(0, m.document().lineCount()).replace("\n", "\r\n"));
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\r\n");
    a.connect(m); // rebind compares '\n' chunks: must not throw
    return a;
  }

  @Test
  void sharedCrlfBufferKeepsModelLines() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    assertEquals(4, a.lineCount());
    assertEquals("b\n", a.chunk(1, 2));
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\r\nL\r\nc\r\nR3\r\n", a.document().get());
    a.document().replace(0, 0, "z\r\n"); // a new first line typed by the user
    assertEquals(2, m.blocks().get(0).resultStart());
    assertEquals(5, a.lineCount());
  }

  @Test
  void loneCarriageReturnFromAnEditIsRepaired() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    a.document().replace(1, 0, "x\ry"); // inside line 0 ("a")
    assertEquals("ax\r\ny\r\nb\r\nc\r\nR3\r\n", a.document().get());
    assertEquals(5, a.lineCount());
    assertEquals(a.document().getNumberOfLines() - 1, a.lineCount(), "pane lines are model lines again");
    assertEquals(2, m.blocks().get(0).resultStart());
  }

  @Test
  void sharedLfBufferNormalizesInsertedCrlf() throws Exception {
    MergeModel m = model();
    Document d = new Document(m.document().chunk(0, m.document().lineCount()));
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\n");
    a.connect(m);
    d.replace(0, 0, "z\r\n"); // pasted with another delimiter
    assertEquals("z\na\n", a.chunk(0, 2));
    assertEquals(2, m.blocks().get(0).resultStart());
  }

  static final String CRLF = "a\r\nb\r\nc\r\nR3\r\n";

  static void assertModelLines(ResultDocumentAdapter a) {
    assertEquals(a.document().getNumberOfLines() - 1, a.lineCount(), "pane lines are model lines");
  }

  @Test
  void twoLoneCarriageReturnsBecomeTwoLines() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    a.document().replace(1, 0, "\r\r");
    assertEquals("a\r\n\r\n\r\nb\r\nc\r\nR3\r\n", a.document().get());
    assertEquals(6, a.lineCount());
    assertModelLines(a);
    assertEquals(3, m.blocks().get(0).resultStart());
  }

  @Test
  void loneCarriageReturnsAroundTextAreRepaired() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    a.document().replace(1, 0, "x\r\ry");
    assertEquals("ax\r\n\r\ny\r\nb\r\nc\r\nR3\r\n", a.document().get());
    assertEquals(6, a.lineCount());
    assertModelLines(a);
    assertEquals(3, m.blocks().get(0).resultStart());
  }

  @Test
  void loneCarriageReturnAtTheEndIsRepaired() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    a.document().replace(CRLF.length(), 0, "\r");
    assertEquals(CRLF + "\r\n", a.document().get());
    assertEquals(5, a.lineCount());
    assertModelLines(a);
  }

  @Test
  void loneCarriageReturnInAnLfBufferBecomesANewline() throws Exception {
    MergeModel m = model();
    Document d = new Document(m.document().chunk(0, m.document().lineCount()));
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\n");
    a.connect(m);
    d.replace(1, 0, "x\ry");
    assertEquals("ax\ny\nb\nc\nR3\n", d.get());
    assertModelLines(a);
    assertEquals(2, m.blocks().get(0).resultStart());
  }

  @Test
  void repairFollowsTextMovedByAnEarlierPostNotificationReplace() throws Exception {
    MergeModel m = model();
    Document d = new Document(CRLF);
    boolean[] done = {false};
    // registered before the adapter, so its replace runs first and moves the text the repair looks at
    d.addDocumentListener(new org.eclipse.jface.text.IDocumentListener() {
      @Override
      public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent e) {}

      @Override
      public void documentChanged(org.eclipse.jface.text.DocumentEvent e) {
        if (!done[0]) {
          done[0] = true;
          d.registerPostNotificationReplace(this, (doc, owner) -> {
            try {
              doc.replace(0, 0, "q\r\n");
            } catch (org.eclipse.jface.text.BadLocationException ex) {
              throw new IllegalStateException(ex);
            }
          });
        }
      }
    });
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\r\n");
    a.connect(m);
    d.replace(1, 0, "x\ry");
    assertEquals("q\r\nax\r\ny\r\nb\r\nc\r\nR3\r\n", d.get());
    assertModelLines(a);
    assertEquals(3, m.blocks().get(0).resultStart());
  }

  @Test
  void trailingLoneCarriageReturnEndsTheLastChunk() {
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(new Document("a\r\nb\r"), "\r\n");
    assertEquals(2, a.lineCount());
    assertEquals("b\n", a.chunk(1, 2));
    assertEquals("a\nb\n", a.chunk(0, 2));
  }

  @Test
  void appendingAfterATrailingLoneCarriageReturnLeavesNoLoneCarriageReturn() {
    Document d = new Document("a\r\nb\r");
    ResultDocumentAdapter.forSharedBuffer(d, "\r\n").replaceLines(2, 2, "x\n");
    assertEquals("a\r\nb\r\nx\r\n", d.get());
  }

  @Test
  void aReplacementAcrossLinesKeepsBlocksWhoseLinesDidNotChange() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.document().replace(0, 5, "A\nb\nc"); // "a\nb\nc" rewritten in one event: only line 0 differs
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(2, m.blocks().get(0).resultEnd());
    assertEquals("R3\n", m.resultText(1));
  }

  @Test
  void aCrlfSharedBufferMapsAReformatLineByLine() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connectCrlf(m);
    // "a\r\nb\r\nc" becomes "a\r\n b\r\n c" in one event, as a formatter's indent edit over line ends would
    a.document().replace(0, a.document().get().indexOf("c") + 1, "a\r\n b\r\n c");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state(), "reindenting resolves nothing");
    assertEquals(" b\n", m.resultText(0));
    assertEquals("R3\n", m.resultText(1));
    m.take(0, ThreeSide.LEFT);
    assertEquals("a\r\nL\r\n c\r\nR3\r\n", a.document().get(), "Take replaces the block, nothing is duplicated");
  }

  @Test
  void enterAtTheEndOfABlockLineStillGrowsTheBlock() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    a.document().replace(3, 0, "\n"); // after "b": the new line is typed into the block
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
    assertEquals(1, m.blocks().get(0).resultStart());
    assertEquals(3, m.blocks().get(0).resultEnd());
  }

  /**
   * Replaces the whole text "a b c R3" by "A b x... R4" with {@code k} lines x: first and last lines differ, so
   * nothing is left out, and old plus new lines are 7 + k. Only the line diff keeps block 0 ("b") as it is.
   */
  static MergeModel rewrittenWith(int k) throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    String text = a.document().get();
    String now = "A\nb\n" + "x\n".repeat(k) + "R4\n";
    a.document().replace(0, text.length(), now);
    assertEquals(now, m.document().chunk(0, m.document().lineCount()));
    return m;
  }

  @Test
  void aReplacementOfExactlyTheCapIsStillDiffed() throws Exception {
    MergeModel m = rewrittenWith(ResultDocumentAdapter.MAX_DIFFED_LINES - 7);
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state(), "its line did not change");
  }

  @Test
  void aReplacementOneLinePastTheCapIsOneEdit() throws Exception {
    MergeModel m = rewrittenWith(ResultDocumentAdapter.MAX_DIFFED_LINES - 6);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state());
  }

  @Test
  void aLargeAppendBehindTheBlocksLeavesThemAlone() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    String t = a.document().get();
    int c = t.indexOf("c");
    // across line ends and past the cap, but the lines it starts and ends with stay: only the appended lines count
    a.document().replace(c, t.length() - c, "c\nR3\n" + "x\n".repeat(ResultDocumentAdapter.MAX_DIFFED_LINES + 10));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals("R3\n", m.resultText(1));
  }

  @Test
  void aLargeChangeReportedAsOneLeavesUntouchedConflictsOpen() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    Document d = (Document) a.document();
    String lots = "x\n".repeat(ResultDocumentAdapter.MAX_DIFFED_LINES);
    d.stopListenerNotification();
    d.replace(d.getLength(), 0, lots);
    d.replace(d.getLength(), 0, lots);
    d.resumeListenerNotification();
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state(), "the conflict was not touched");
    assertEquals(1, m.unresolvedConflicts());
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
  }

  @Test
  void aReplacementThatOnlyDropsTheFinalNewlineNotifiesTheModel() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    int[] fired = {0};
    m.addChangeListener(() -> fired[0]++);
    String t = a.document().get();
    int c = t.indexOf("c");
    a.document().replace(c, t.length() - c, "c\nR3"); // spans two lines, no model line changes
    assertEquals(1, fired[0]);
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals("R3\n", m.resultText(1));
  }

  @Test
  void normalizingATrailingLoneCarriageReturnChangesNoBlock() throws Exception {
    MergeModel m = model();
    Document d = new Document("a\r\nb\r\nc\r\nR3\r");
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\r\n");
    a.connect(m);
    int c = d.get().indexOf("c");
    d.replace(c, d.getLength() - c, "c\r\nR3"); // the lone '\r' ended the last line; its text is the same
    assertEquals(BlockState.RESOLVED_BY_GIT, m.blocks().get(1).state());
    assertEquals("R3\n", m.resultText(1));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
  }

  @Test
  void changesReportedAsOneWhileNotificationIsStoppedAllReachTheModel() throws Exception {
    MergeModel m = model();
    ResultDocumentAdapter a = connect(m);
    Document d = (Document) a.document();
    d.stopListenerNotification();
    d.replace(0, 0, "new\n");
    d.replace(0, 0, "z\n"); // only this change is reported, when notification resumes
    d.resumeListenerNotification();
    assertEquals(3, m.blocks().get(0).resultStart());
    assertEquals("b\n", m.resultText(0));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals("R3\n", m.resultText(1));
    assertEquals(6, m.document().lineCount());
  }

  @Test
  void aVeryLargeReplacementIsNotDiffedLineByLine() throws Exception {
    // the line diff runs on the UI thread: past the cap the changed span counts as one plain replacement
    MergeModel m = rewrittenWith(ResultDocumentAdapter.MAX_DIFFED_LINES);
    assertEquals(BlockState.EDITED, m.blocks().get(0).state(), "the whole replaced span is one edit");
  }
}
