package net.aftergit.merge.ui;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IUndoManager;

/**
 * The undo manager of a result viewer: routes everything to the resolver's own undo, which restores block
 * states together with the text (spec §4.4). Compound changes (quick fixes, completions) become one step.
 */
final class UndoBridge implements IUndoManager {
  private final MergeViewer viewer;

  UndoBridge(MergeViewer viewer) {
    this.viewer = viewer;
  }

  @Override
  public void connect(ITextViewer textViewer) {
    // the resolver's undo stack follows the result document itself
  }

  @Override
  public void disconnect() {
    viewer.resetCompoundEdit();
  }

  @Override
  public void beginCompoundChange() {
    viewer.beginCompoundEdit();
  }

  @Override
  public void endCompoundChange() {
    viewer.endCompoundEdit();
  }

  @Override
  public void reset() {
    viewer.resetCompoundEdit(); // the history itself belongs to the resolver
  }

  @Override
  public void setMaximalUndoLevel(int undoLevel) {
    // UndoStack.LIMIT applies
  }

  @Override
  public boolean undoable() {
    return viewer.canUndo();
  }

  @Override
  public boolean redoable() {
    return viewer.canRedo();
  }

  @Override
  public void undo() {
    viewer.undo();
  }

  @Override
  public void redo() {
    viewer.redo();
  }
}
