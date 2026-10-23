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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UndoStackTest {
  MergeModel model;
  Document doc;
  UndoStack undo;

  @BeforeEach
  void setUp() {
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n", "a\nR1\nc\nR3\ne\n",
        null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    doc = new Document(model.document().chunk(0, model.document().lineCount()));
    ResultDocumentAdapter adapter = new ResultDocumentAdapter(doc);
    adapter.connect(model);
    undo = new UndoStack(adapter, model);
  }

  @Test
  void takeThenUndoRedo() {
    undo.runAction(() -> model.take(0, ThreeSide.LEFT));
    assertEquals("a\nL1\nc\nd\ne\n", doc.get());
    undo.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(2, model.unresolvedConflicts());
    assertTrue(undo.canRedo());
    undo.redo();
    assertEquals("a\nL1\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
  }

  @Test
  void typingIsGroupedUntilAJump() throws Exception {
    int o = doc.getLineOffset(1);
    doc.replace(o, 0, "x");
    doc.replace(o + 1, 0, "y");
    doc.replace(o + 2, 0, "z");
    int far = doc.getLineOffset(4);
    doc.replace(far, 0, "!");
    undo.undo();
    assertEquals("a\nxyzb\nc\nd\ne\n", doc.get());
    undo.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
  }

  @Test
  void undoRestoresTypingThenTakeExactly() throws Exception {
    doc.replace(doc.getLineOffset(1), 0, "Q");
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    undo.runAction(() -> model.take(1, ThreeSide.RIGHT));
    undo.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(1).state());
    assertEquals("a\nQb\nc\nd\ne\n", doc.get());
    undo.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(2, model.unresolvedConflicts());
    assertFalse(undo.canUndo());
  }

  @Test
  void newEditClearsRedo() {
    undo.runAction(() -> model.take(0, ThreeSide.LEFT));
    undo.undo();
    undo.runAction(() -> model.ignore(1));
    assertFalse(undo.canRedo());
  }

  @Test
  void limitDropsOldestSteps() {
    for (int i = 0; i < UndoStack.LIMIT + 50; i++) {
      // every step changes the state: no-op actions are not recorded
      if (i % 2 == 0) {
        undo.runAction(() -> model.ignore(0));
      } else {
        undo.runAction(() -> model.revert(0));
      }
    }
    int steps = 0;
    while (undo.canUndo()) {
      undo.undo();
      steps++;
    }
    assertEquals(UndoStack.LIMIT, steps);
  }

  @Test
  void typingThatOnlyChangesStatesIsUndone() throws Exception {
    int o = doc.getLineOffset(1);
    doc.replace(o, 0, "x");
    doc.replace(o, 1, "");
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    assertTrue(undo.canUndo());
    undo.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(2, model.unresolvedConflicts());
    assertFalse(undo.canUndo());
  }

  @Test
  void stateOnlyTypingDoesNotUndoAnEarlierAction() throws Exception {
    undo.runAction(() -> model.take(1, ThreeSide.RIGHT));
    int o = doc.getLineOffset(1);
    doc.replace(o, 0, "x");
    doc.replace(o, 1, "");
    undo.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
    assertEquals("a\nb\nc\nR3\ne\n", doc.get());
    undo.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(1).state());
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void noOpActionIsNotAStep() {
    undo.runAction(() -> {});
    assertFalse(undo.canUndo());
  }

  @Test
  void consecutiveStepsShareTheUnchangedState() {
    undo.runAction(() -> model.take(0, ThreeSide.LEFT));
    UndoStack.State first = undo.topStates()[1];
    undo.runAction(() -> model.take(1, ThreeSide.RIGHT));
    assertSame(first, undo.topStates()[0]);
    undo.undo();
    undo.runAction(() -> model.take(1, ThreeSide.LEFT));
    assertSame(first, undo.topStates()[0]);
  }

  @Test
  void undoReplacesOnlyTheChangedSpanAndReportsTheBlock() {
    undo.runAction(() -> model.take(1, ThreeSide.LEFT));
    java.util.List<org.eclipse.jface.text.DocumentEvent> events = new java.util.ArrayList<>();
    doc.addDocumentListener(new org.eclipse.jface.text.IDocumentListener() {
      @Override
      public void documentAboutToBeChanged(org.eclipse.jface.text.DocumentEvent e) {}

      @Override
      public void documentChanged(org.eclipse.jface.text.DocumentEvent e) {
        events.add(e);
      }
    });
    UndoStack.Restored r = undo.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(1, r.block());
    assertEquals(1, events.size());
    assertEquals(6, events.get(0).getOffset(), "only line 3 is replaced");
    assertEquals("d", events.get(0).getText());
    events.clear();
    undo.redo();
    assertEquals("a\nb\nc\nL3\ne\n", doc.get());
    undo.runAction(() -> model.ignore(0));
    events.clear();
    r = undo.undo();
    assertTrue(events.isEmpty(), "equal text is not replaced");
    assertEquals(0, r.block());
    assertEquals(-1, r.textOffset());
  }

  @Test
  void compoundEditsAreOneUndoStep() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");                 // line 0
    doc.replace(doc.getLength(), 0, "y\n"); // the end: not contiguous with the first edit
    undo.endCompound();
    undo.undo();
    assertEquals(before, doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void closeGroupWaitsForTheEndOfACompound() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");
    undo.closeGroup(); // e.g. the caret moved while a completion was applied
    doc.replace(doc.getLength(), 0, "y\n");
    undo.endCompound();
    undo.undo();
    assertEquals(before, doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void nestedCompoundsCloseWithTheOutermost() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");
    undo.beginCompound();
    doc.replace(doc.getLength(), 0, "y\n");
    undo.endCompound();
    doc.replace(0, 0, "z");
    undo.endCompound();
    undo.undo();
    assertEquals(before, doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void unmatchedEndCompoundIsIgnored() throws Exception {
    undo.endCompound();
    doc.replace(0, 0, "x");
    doc.replace(doc.getLength(), 0, "y\n");
    undo.undo(); // not grouped: the second edit is separate
    assertTrue(doc.get().startsWith("x"));
    assertTrue(undo.canUndo());
  }

  @Test
  void typingBeforeACompoundIsASeparateStep() throws Exception {
    doc.replace(0, 0, "x");
    undo.beginCompound();
    doc.replace(doc.getLength(), 0, "y\n");
    undo.endCompound();
    undo.undo();
    assertTrue(doc.get().startsWith("x"));
    assertFalse(doc.get().endsWith("y\n"));
    undo.undo();
    assertFalse(doc.get().startsWith("x"));
  }

  @Test
  void compoundCanBeRedoneAndANewEditClearsRedo() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");
    doc.replace(doc.getLength(), 0, "y\n");
    undo.endCompound();
    String after = doc.get();
    undo.undo();
    assertTrue(undo.canRedo());
    undo.redo();
    assertEquals(after, doc.get());
    undo.undo();
    assertEquals(before, doc.get());
    doc.replace(0, 0, "q");
    assertFalse(undo.canRedo());
  }

  @Test
  void undoInsideAnOpenCompoundClosesItAndKeepsHistoryConsistent() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");
    undo.undo();
    assertEquals(before, doc.get());
    assertTrue(undo.canRedo());
    undo.redo();
    assertTrue(doc.get().startsWith("x"));
    undo.endCompound(); // stray end is harmless
    undo.undo();
    assertEquals(before, doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void runActionInsideAnOpenCompoundIsItsOwnStep() throws Exception {
    String before = doc.get();
    undo.beginCompound();
    doc.replace(0, 0, "x");
    undo.runAction(() -> model.take(0, ThreeSide.LEFT));
    undo.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(doc.get().startsWith("x"));
    undo.undo();
    assertEquals(before, doc.get());
    assertFalse(undo.canUndo());
  }

  @Test
  void forceCloseEndsALeakedCompound() throws Exception {
    undo.beginCompound();
    doc.replace(0, 0, "x");
    undo.forceClose();
    doc.replace(doc.getLength(), 0, "y\n");
    undo.undo();
    assertTrue(doc.get().startsWith("x"));
  }

  @Test
  void repairedLoneCarriageReturnIsUndoneWithTheTyping() throws Exception {
    Document d = new Document(model.document().chunk(0, model.document().lineCount()).replace("\n", "\r\n"));
    ResultDocumentAdapter a = ResultDocumentAdapter.forSharedBuffer(d, "\r\n");
    a.connect(model);
    UndoStack u = new UndoStack(a, model);
    String before = d.get();
    d.replace(1, 0, "x\ry");
    assertEquals("ax\r\ny\r\nb\r\n", d.get().substring(0, 10));
    d.replace(u.typingEnd(), 0, "z"); // typing goes on after the repaired text
    assertEquals("ax\r\nyz\r\nb\r\n", d.get().substring(0, 11));
    u.undo();
    assertEquals(before, d.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertFalse(u.canUndo());
  }

  @Test
  void anActionThatThrowsAnErrorIsStillAnUndoStep() {
    assertThrows(InternalError.class, () -> undo.runAction(() -> {
      model.take(0, ThreeSide.LEFT);
      throw new InternalError("a virtual machine failure after the change");
    }));
    assertTrue(undo.canUndo(), "the change is recorded");
    undo.undo();
    assertEquals("a\nb\nc\nd\ne\n", doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
  }
}
