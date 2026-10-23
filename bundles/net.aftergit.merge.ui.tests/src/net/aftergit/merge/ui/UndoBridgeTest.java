package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UndoBridgeTest {
  Shell shell;
  MergeViewer viewer;
  MergeModel model;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(model, new Labels("o", "t"), "\n", true));
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  @Test
  void routesUndoAndCompoundChangesToTheMergeViewer() throws Exception {
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    UndoBridge bridge = new UndoBridge(viewer);
    String before = doc.get();
    assertFalse(bridge.undoable());
    bridge.beginCompoundChange();
    doc.replace(0, 0, "x");
    doc.replace(doc.getLength(), 0, "y\n");
    bridge.endCompoundChange();
    assertTrue(bridge.undoable());
    bridge.undo();
    assertEquals(before, doc.get());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(bridge.redoable());
    bridge.redo();
    assertTrue(doc.get().startsWith("x"));
  }
}
