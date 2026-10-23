package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** block-states design §4: an automatic resolution is only a starting point the user can replace. */
class AutoOverrideTest {
  static final String AUTO = "x A z B y\n";
  Shell shell;
  MergeViewer viewer;
  MergeModel model;

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput("x a z b y\n", "x A z b y\n", "x a z B y\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    model.applyResolution(0, AUTO, BlockState.AUTO_RESOLVED, Strategy.TEXT, "simple", null);
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    shell.open();
    UiDriver.flush();
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  private String result() {
    return viewer.pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  private List<PaneStyle.Gutter> gutters() {
    return viewer.pane(PaneKind.RESULT).originColumn().gutters();
  }

  @Test
  void takeLeftReplacesAnAutomaticResolutionAndCountsAsManual() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertFalse(model.blocks().get(0).needsReview());
    assertEquals("x A z b y\n", result());
    assertEquals(List.of(new PaneStyle.Gutter(0, 0, 1, PaneStyle.Bar.TAKEN, PaneStyle.Origin.LEFT,
        PaneStyle.Badge.NONE)), gutters(), "yellow, ◀, the A badge gone");
    assertEquals("✓ All conflicts resolved", viewer.statusText(), "nothing left to review");
  }

  @Test
  void takingTheOtherSideNextGivesBoth() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.takeRight();
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
    assertEquals("x A z b y\nx a z B y\n", result());
  }

  @Test
  void takeBothWorksOnAnAutomaticResolution() {
    viewer.selectBlock(0);
    viewer.takeRightThenLeft();
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
    assertEquals("x a z B y\nx A z b y\n", result());
  }

  @Test
  void undoBringsBackTheAutomaticResolutionWithItsReviewState() {
    viewer.selectBlock(0);
    viewer.takeRight();
    viewer.undo();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertTrue(model.blocks().get(0).needsReview());
    assertEquals(AUTO, result());
    assertEquals(PaneStyle.Badge.PENDING, gutters().get(0).badge());

    viewer.markReviewed();
    viewer.takeLeft();
    viewer.undo();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertFalse(model.blocks().get(0).needsReview(), "a reviewed resolution comes back reviewed");
    assertEquals(PaneStyle.Badge.REVIEWED, gutters().get(0).badge());
  }

  @Test
  void theConnectorOffersTakeOnAnAutomaticResolution() {
    assertTrue(ConnectorCanvas.canTake(model.blocks().get(0), ThreeSide.LEFT));
    assertTrue(ConnectorCanvas.canTake(model.blocks().get(0), ThreeSide.RIGHT));
    UiDriver.clickConnector(viewer, PaneKind.LEFT, 0, true, 0);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    UiDriver.clickConnector(viewer, PaneKind.RIGHT, 0, true, 0);
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
  }

  @Test
  void typingInAnAutomaticResolutionMakesItEditedAndUndoBringsItBackToReview() throws Exception {
    StyledText w = viewer.pane(PaneKind.RESULT).text();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    w.setFocus();
    w.setCaretOffset(doc.getLineOffset(0) + 1);
    Event e = new Event();
    e.character = 'q';
    w.notifyListeners(SWT.KeyDown, e);
    UiDriver.flush();
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    assertEquals(0, model.needsReviewCount());
    assertEquals(PaneStyle.Bar.EDITED, gutters().get(0).bar());
    assertEquals(PaneStyle.Origin.EDITED, gutters().get(0).origin());
    assertEquals(PaneStyle.Badge.NONE, gutters().get(0).badge(), "the badge goes");
    w.setCaretOffset(doc.getLength()); // the caret moves away: the typing is one undo step
    viewer.undo();
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertTrue(model.blocks().get(0).needsReview());
    assertEquals(AUTO, result());
    assertTrue(viewer.statusText().contains("to review"), viewer.statusText());
  }

  @Test
  void revertOfAnOverriddenResolutionGivesTheOpenConflict() {
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.revert();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("x a z b y\n", result());
  }

  @Test
  void aReviewedAiResolutionOffersTakeAndATakeRemovesItsSparkle() {
    model.applyResolution(0, "x Q z Q y\n", BlockState.AI_RESOLVED, Strategy.TEXT, "agent", "why");
    viewer.refresh();
    assertFalse(ConnectorCanvas.canTake(model.blocks().get(0), ThreeSide.LEFT),
        "while it waits for review ↶ stands where » would be");
    viewer.selectBlock(0);
    viewer.markReviewed();
    assertTrue(ConnectorCanvas.canTake(model.blocks().get(0), ThreeSide.LEFT));
    UiDriver.clickConnector(viewer, PaneKind.LEFT, 0, true, 0);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertFalse(UiDriver.paint(viewer, PaneKind.LEFT).sparkles().contains(0), "the sparkle goes with the AI text");
  }

  @Test
  void undoOfATakeBringsBackTheAiResolutionWithItsSparkle() {
    model.applyResolution(0, "x Q z Q y\n", BlockState.AI_RESOLVED, Strategy.TEXT, "agent", "why");
    viewer.refresh();
    assertTrue(UiDriver.paint(viewer, PaneKind.LEFT).sparkles().contains(0));
    viewer.selectBlock(0);
    viewer.markReviewed();
    UiDriver.clickConnector(viewer, PaneKind.LEFT, 0, true, 0);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertFalse(UiDriver.paint(viewer, PaneKind.LEFT).sparkles().contains(0));
    viewer.undo();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertFalse(model.blocks().get(0).needsReview(), "a reviewed AI resolution comes back reviewed");
    assertEquals("x Q z Q y\n", result());
    assertTrue(UiDriver.paint(viewer, PaneKind.LEFT).sparkles().contains(0), "the sparkle comes back with the AI text");
    assertTrue(UiDriver.paint(viewer, PaneKind.RIGHT).sparkles().contains(0));
  }

  @Test
  void takeRightReplacesAPendingAiResolution() {
    model.applyResolution(0, "x Q z Q y\n", BlockState.AI_RESOLVED, Strategy.TEXT, "agent", "why");
    viewer.refresh();
    viewer.selectBlock(0);
    viewer.takeRight();
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(0).state());
    assertEquals("x a z B y\n", result());
  }
}
