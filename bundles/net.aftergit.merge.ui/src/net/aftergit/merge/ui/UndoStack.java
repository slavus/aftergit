package net.aftergit.merge.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.ModelSnapshot;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

/** Undo/redo of the result: each step restores the text and the block states (spec §4.4). */
final class UndoStack {
  static final int LIMIT = 200;

  record State(String text, ModelSnapshot snapshot) {}

  /**
   * What an undo or redo touched: the first block whose state changed (else -1) and the end of the
   * replaced text (else -1), so the caret can land there.
   */
  record Restored(int block, int textOffset) {
    static final Restored NOTHING = new Restored(-1, -1);
  }

  private record Entry(State before, State after) {}

  private final ResultDocumentAdapter adapter;
  private final IDocument document;
  private final MergeModel model;
  private final Deque<Entry> undo = new ArrayDeque<>();
  private final Deque<Entry> redo = new ArrayDeque<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private State typingBefore;
  private int typingEnd = -1;
  private int compound;
  /** The newest captured or restored state, shared by the next capture while nothing has changed. */
  private State last;
  private long lastStamp = IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;

  UndoStack(ResultDocumentAdapter adapter, MergeModel model) {
    this.adapter = adapter;
    this.document = adapter.document();
    this.model = model;
    adapter.setEditListener(new ResultDocumentAdapter.EditListener() {
      @Override
      public void aboutToEdit(int offset, int length, String text) {
        boolean continues = typingBefore != null
            && (compound > 0 || offset == typingEnd || length > 0 && offset + length == typingEnd);
        if (!continues) {
          closeGroup();
          typingBefore = capture();
        }
      }

      @Override
      public void edited(int offset, int length, String text) {
        typingEnd = offset + text.length();
        redo.clear();
        fire();
      }

      @Override
      public void repaired(int offset, int length, String text) {
        // the repair belongs to the edit before it: same group, the typing end moves with the text
        if (typingEnd >= offset + length) {
          typingEnd += text.length() - length;
        } else if (typingEnd > offset) {
          typingEnd = offset + text.length();
        }
        fire();
      }
    });
  }

  /** The before and after states of the newest undo step, or null; for tests. */
  State[] topStates() {
    Entry e = undo.peek();
    return e == null ? null : new State[] {e.before(), e.after()};
  }

  int typingEnd() {
    return typingEnd;
  }

  void addListener(Runnable r) {
    listeners.add(r);
  }

  /** Runs {@code action} as one undo step; also when it throws (an Error too), what it changed is that step. */
  void runAction(Runnable action) {
    forceClose();
    State before = capture();
    Throwable failure = null;
    try {
      action.run();
    } catch (RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {
      try {
        State after = capture();
        if (!after.equals(before)) {
          push(new Entry(before, after));
          redo.clear();
        }
        fire();
      } catch (RuntimeException | Error e) {
        if (failure == null) {
          throw e;
        }
        failure.addSuppressed(e); // the action's own failure is the one to report
      }
    }
  }

  /** Edits until the matching {@link #endCompound()} form one undo step (a quick fix, a completion with its import). */
  void beginCompound() {
    if (compound == 0) {
      closeGroup();
    }
    compound++;
  }

  void endCompound() {
    if (compound == 0) {
      return;
    }
    if (--compound == 0) {
      closeGroup();
    }
  }

  /** Ends any open compound and closes the group; a leaked or interrupted compound must not outlive this. */
  void forceClose() {
    compound = 0;
    closeGroup();
  }

  void closeGroup() {
    if (compound > 0) {
      return; // the compound closes the group when it ends
    }
    if (typingBefore != null) {
      State after = capture();
      // compare the whole state: typing that is undone by hand still changes block states
      if (!after.equals(typingBefore)) {
        push(new Entry(typingBefore, after));
      }
      typingBefore = null;
      typingEnd = -1;
    }
  }

  boolean canUndo() {
    return typingBefore != null || !undo.isEmpty();
  }

  boolean canRedo() {
    return typingBefore == null && !redo.isEmpty();
  }

  Restored undo() {
    forceClose();
    Entry e = undo.poll();
    if (e == null) {
      return Restored.NOTHING;
    }
    Restored block = restore(e.before());
    redo.push(e);
    fire();
    return block;
  }

  Restored redo() {
    forceClose();
    Entry e = redo.poll();
    if (e == null) {
      return Restored.NOTHING;
    }
    Restored block = restore(e.after());
    undo.push(e);
    fire();
    return block;
  }

  private State capture() {
    ModelSnapshot snapshot = model.snapshot();
    long stamp = stamp();
    boolean textUnchanged = last != null && stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
        && stamp == lastStamp;
    if (textUnchanged && snapshot.equals(last.snapshot())) {
      return last;
    }
    remember(new State(textUnchanged ? last.text() : document.get(), snapshot), stamp);
    return last;
  }

  /** Restores {@code s} and reports what changed; only the differing span of the text is replaced. */
  private Restored restore(State s) {
    ModelSnapshot current = model.snapshot();
    int[] end = {-1};
    adapter.runUntracked(() -> end[0] = TextSpans.replaceMinimal(document, s.text()));
    model.restore(s.snapshot());
    remember(s, stamp());
    return new Restored(firstStateChange(current, s.snapshot()), end[0]);
  }

  /** First block whose resolution differs (positions alone shift with any edit above them). */
  private static int firstStateChange(ModelSnapshot a, ModelSnapshot b) {
    int n = Math.min(a.entries().size(), b.entries().size());
    for (int i = 0; i < n; i++) {
      ModelSnapshot.Entry x = a.entries().get(i);
      ModelSnapshot.Entry y = b.entries().get(i);
      if (x.state() != y.state() || x.strategy() != y.strategy() || x.needsReview() != y.needsReview()
          || !java.util.Objects.equals(x.resolvedBy(), y.resolvedBy())
          || !java.util.Objects.equals(x.rationale(), y.rationale())) {
        return i;
      }
    }
    return -1;
  }

  private void remember(State s, long stamp) {
    last = s;
    lastStamp = stamp;
  }

  private long stamp() {
    return document instanceof IDocumentExtension4 d
        ? d.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
  }

  private void push(Entry e) {
    undo.push(e);
    while (undo.size() > LIMIT) {
      undo.removeLast();
    }
  }

  private void fire() {
    for (Runnable r : listeners) {
      r.run();
    }
  }
}
