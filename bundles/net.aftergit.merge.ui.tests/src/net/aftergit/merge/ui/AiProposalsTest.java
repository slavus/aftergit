package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §1, §3: answers of AI resolvers in an open resolver, apart from the agents' switch. */
class AiProposalsTest {
  static final Instant T = Instant.parse("2026-10-16T10:00:00Z");
  Shell shell;
  MergeViewer viewer;
  MergeModel model;
  final List<BlockId> forgotten = new ArrayList<>();

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n",
        "a\nR1\nc\nR3\ne\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true, "Calc.txt"));
    viewer.setProposalForgetter(forgotten::add);
    viewer.setAiActive(true);
    shell.open();
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  Proposal ai(int block, String text) {
    return new Proposal(BlockId.of(model, block), text, "ours, then theirs", "Stand-in model", T);
  }

  Proposal agent(int block, String text) {
    return new Proposal(BlockId.of(model, block), text, "agent", "test-agent", T);
  }

  String result() {
    return viewer.pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  @Test
  void anAnswerForAnUntouchedConflictIsAppliedAtOnceAsOneUndoStep() {
    assertEquals(Offer.APPLIED, viewer.offerAi(ai(0, "L1\nR1\n"), false));
    MergeBlock b = model.blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals("ai:Stand-in model", b.resolvedBy());
    assertEquals("ours, then theirs", b.rationale());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", result());
    assertTrue(viewer.statusText().contains("1"), viewer.statusText()); // counted as AI-resolved to review
    viewer.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("a\nb\nc\nd\ne\n", result());
  }

  @Test
  void revertRestoresTheConflictAndTellsNoAgentsStore() {
    viewer.offerAi(ai(0, "L1\nR1\n"), false);
    viewer.revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(forgotten.isEmpty(), "nothing of an AI resolver is stored");
  }

  @Test
  void markReviewedKeepsTheText() {
    viewer.offerAi(ai(0, "L1\nR1\n"), false);
    viewer.markBlockReviewed(0);
    assertFalse(model.blocks().get(0).needsReview());
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
  }

  @Test
  void aTouchedBlockGetsAWaitingProposalToApplyOrDiscard() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal p = ai(0, "L1\nR1\n");
    assertEquals(Offer.PENDING, viewer.offerAi(p, false));
    assertEquals(p, viewer.pendingProposals().get(0));
    assertTrue(viewer.canShowPendingProposal(0));
    assertTrue(viewer.applyPending(0));
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    assertEquals(Offer.PENDING, viewer.offerAi(ai(1, "L3\nR3\n"), false));
    assertTrue(viewer.discardPending(1));
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
    assertTrue(forgotten.isEmpty(), "a discarded AI proposal tells no agents store");
    viewer.revertBlock(0);
    assertTrue(forgotten.isEmpty(), "nor does the revert of a waiting AI proposal that was applied");
  }

  @Test
  void whileTheAiResolversAreHiddenNothingIsAppliedOrWaits() {
    viewer.setAiActive(false);
    assertEquals(Offer.NOT_FOUND, viewer.offerAi(ai(0, "L1\nR1\n"), false));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(viewer.pendingProposals().isEmpty());
  }

  @Test
  void hidingTheAiResolversDropsTheirWaitingProposalsButNotTheAgents() {
    viewer.setAgentsActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    Proposal fromAgent = agent(0, "L1\nR1\n");
    assertEquals(Offer.PENDING, viewer.offerProposal(fromAgent));
    assertEquals(Offer.PENDING, viewer.offerAi(ai(1, "L3\nR3\n"), false));
    viewer.setAiActive(false);
    assertEquals(java.util.Map.of(0, fromAgent), viewer.pendingProposals());
    assertFalse(viewer.canShowPendingProposal(1));
  }

  @Test
  void overridingAnAiResolversAnswerForgetsNothing() throws Exception {
    viewer.setAgentsActive(true);
    viewer.offerAi(ai(0, "L1\nR1\n"), false);
    viewer.offerAi(ai(1, "L3\nR3\n"), false);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.selectBlock(1);
    viewer.takeRightThenLeft();
    viewer.undo();
    org.eclipse.jface.text.IDocument d = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    d.replace(d.getLineOffset(3), 0, "x");
    UiDriver.flush();
    assertEquals(BlockState.EDITED, model.blocks().get(1).state());
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    while (model.blocks().get(1).state() == BlockState.EDITED && viewer.canUndo()) {
      viewer.undo();
      UiDriver.flush();
    }
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(1).state());
    viewer.ignoreBlock(1);
    assertEquals(BlockState.IGNORED, model.blocks().get(1).state());
    assertTrue(forgotten.isEmpty(), "nothing of an AI resolver is stored");
  }

  @Test
  void switchingTheAgentsOffKeepsTheAiProposalsWaiting() {
    viewer.setAgentsActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    viewer.offerProposal(agent(0, "L1\nR1\n"));
    Proposal p = ai(1, "L3\nR3\n");
    viewer.offerAi(p, false);
    viewer.setAgentsActive(false);
    assertEquals(java.util.Map.of(1, p), viewer.pendingProposals());
    assertTrue(viewer.applyPending(1));
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(1).state());
  }

  @Test
  void withoutTheAgentsAnAgentsProposalStillDoesNothing() {
    assertEquals(Offer.NOT_FOUND, viewer.offerProposal(agent(0, "L1\nR1\n")));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
  }

  @Test
  void anAiProposalOfferedDuringASaveIsHeldAndOfferedAfterwards() {
    assertEquals(Offer.PENDING, viewer.offerAi(ai(0, "L1\nR1\n"), true));
    assertTrue(viewer.hasHeld());
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    viewer.releaseHeld();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertTrue(viewer.pendingProposals().isEmpty());
  }

  @Test
  void aNewInputDropsTheWaitingAiProposals() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerAi(ai(0, "L1\nR1\n"), false);
    viewer.setInput(viewer.getInput());
    assertTrue(viewer.pendingProposals().isEmpty());
    assertTrue(viewer.aiActive(), "being ready is no matter of the session");
  }

  @Test
  void theRequestIsBuiltFromTheOpenModel() {
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    AiRequest r = viewer.aiRequest(0, 1);
    assertEquals("Calc.txt", r.fileName());
    assertEquals("txt", r.language());
    assertEquals("b\n", r.base());
    assertEquals("L1\n", r.ours());
    assertEquals("R1\n", r.theirs());
    assertEquals("b\n", r.result());
    assertEquals("a\n", r.before());
    assertEquals("c\n", r.after());
    assertEquals("ours", r.oursLabel());
    assertEquals("theirs", r.theirsLabel());
    assertEquals(List.of(), r.resultErrors(), "plain text has no compile errors");
  }

  @Test
  void anAiNoticeShowsInTheStatusLine() {
    viewer.showAiNotice("Asking Stand-in model… (1 of 2)");
    assertTrue(viewer.noticeText().contains("Asking Stand-in model"), viewer.noticeText());
  }
}
