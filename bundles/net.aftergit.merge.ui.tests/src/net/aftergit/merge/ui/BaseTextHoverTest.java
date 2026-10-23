package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

class BaseTextHoverTest {
  @Test
  void showsBaseOnlyOverUnresolvedConflicts() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      var left = viewer.pane(PaneKind.LEFT).viewer();
      BaseTextHover hover = new BaseTextHover(viewer, PaneKind.LEFT);
      int line1 = left.getDocument().getLineOffset(1);
      assertTrue(hover.getHoverInfo(left, new Region(line1, 0)).contains("b"));
      assertNull(hover.getHoverInfo(left, new Region(0, 0)));
      viewer.selectBlock(0);
      viewer.takeLeft();
      assertNull(hover.getHoverInfo(left, new Region(line1, 0)));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void warnsOverBlocksAutoResolvedByIgnoringWhitespace() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(
          new MergeInput("a b c\nx y\nk l\n", "a\tb\tc\nx y\nk L\n", "a  b c\nx y\nk l\n", null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      var result = viewer.pane(PaneKind.RESULT).viewer();
      BaseTextHover hover = new BaseTextHover(viewer, PaneKind.RESULT);
      String info = hover.getHoverInfo(result, new Region(0, 0));
      assertEquals(Messages.whitespaceIgnoredHover, info);
      assertTrue(info.startsWith("Auto-resolved by ignoring whitespace"));
      assertNull(hover.getHoverInfo(result, new Region(result.getDocument().getLineOffset(1), 0)));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void whitespaceWarningOnlyInResultAndOnlyUntilReviewed() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(new MergeInput("a b c\n", "a\tb\tc\n", "a  b c\n", null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.defaults(false)).model();
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      for (PaneKind k : new PaneKind[] {PaneKind.LEFT, PaneKind.RIGHT}) {
        assertNull(new BaseTextHover(viewer, k).getHoverInfo(viewer.pane(k).viewer(), new Region(0, 0)), "" + k);
      }
      var result = viewer.pane(PaneKind.RESULT).viewer();
      BaseTextHover hover = new BaseTextHover(viewer, PaneKind.RESULT);
      assertEquals(Messages.whitespaceIgnoredHover, hover.getHoverInfo(result, new Region(0, 0)));
      m.markReviewed(0);
      assertNull(hover.getHoverInfo(result, new Region(0, 0)));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void saysWhenTheNewerVersionWasTakenUntilReviewed() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(new MergeInput("<version>1.0.0</version>\n",
          "<version>1.1.0</version>\n", "<version>1.0.1</version>\n", null), ComparisonPolicy.DEFAULT,
          ResolverPipeline.defaults(false, id -> true, java.util.List.of()).forFile("pom.xml")).model();
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      var result = viewer.pane(PaneKind.RESULT).viewer();
      assertEquals("<version>1.1.0</version>\n", result.getDocument().get());
      BaseTextHover hover = new BaseTextHover(viewer, PaneKind.RESULT);
      assertEquals(Messages.newerVersionHover, hover.getHoverInfo(result, new Region(0, 0)));
      assertTrue(Messages.newerVersionHover.contains("newer version"));
      m.markReviewed(0);
      assertNull(hover.getHoverInfo(result, new Region(0, 0)));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void noWarningOverOrdinaryAutoResolution() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(new MergeInput("int a = 0; int b = 0;\n",
          "int a = 1; int b = 0;\n", "int a = 0; int b = 3;\n", null), ComparisonPolicy.DEFAULT,
          ResolverPipeline.defaults(false)).model();
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      var result = viewer.pane(PaneKind.RESULT).viewer();
      assertNull(new BaseTextHover(viewer, PaneKind.RESULT).getHoverInfo(result, new Region(0, 0)));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void typedNewlineIsNotAutoIndented() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      MergeModel m = HybridInitializer.initialize(new MergeInput("  x\n", "  x\n", "  x\n", null),
          ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
      viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true));
      var result = viewer.pane(PaneKind.RESULT).viewer();
      assertEquals("  x\n", result.getDocument().get());
      // goes through the widget's verify listeners, i.e. the auto-edit strategies
      result.getTextWidget().replaceTextRange(3, 0, "\n");
      assertEquals("  x\n\n", result.getDocument().get());
    } finally {
      shell.dispose();
    }
  }

  @Test
  void hoverManagerIsInstalledOnAllPanes() throws Exception {
    Shell shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    try {
      MergeViewer viewer = new MergeViewer(shell);
      for (PaneKind k : PaneKind.values()) {
        var v = viewer.pane(k).viewer();
        var f = org.eclipse.jface.text.TextViewer.class.getDeclaredField("fTextHoverManager");
        f.setAccessible(true);
        assertNotNull(f.get(v), "hover manager on " + k);
      }
    } finally {
      shell.dispose();
    }
  }
}
