package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Agents design §3: proposals in an open resolver, applied to untouched conflicts, waiting for the others. */
class AgentProposalsTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
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
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    viewer.setProposalForgetter(forgotten::add);
    viewer.setAgentsActive(true);
    shell.open();
  }

  @AfterEach
  void close() {
    shell.dispose();
  }

  Proposal proposal(int block, String text) {
    return new Proposal(BlockId.of(model, block), text, "keeps both lines", "test-agent", T);
  }

  String result() {
    return viewer.pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  @Test
  void anUntouchedConflictTakesTheProposalAtOnceAsOneUndoStep() {
    assertEquals(Offer.APPLIED, viewer.offerProposal(proposal(0, "L1\nR1\n")));
    var b = model.blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals("ai:test-agent", b.resolvedBy());
    assertEquals("keeps both lines", b.rationale());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", result());
    assertTrue(viewer.pendingProposals().isEmpty());
    viewer.undo();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("a\nb\nc\nd\ne\n", result());
  }

  @Test
  void revertRestoresTheConflictAndForgetsTheProposal() {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    viewer.revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("a\nb\nc\nd\ne\n", result());
    assertEquals(List.of(p.block()), forgotten);
  }

  @Test
  void theToolbarRevertOfAnAiBlockForgetsItToo() {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    viewer.selectBlock(0);
    viewer.revert();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);
  }

  @Test
  void takingASideOverAnAgentBlockForgetsTheProposalOnce() {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);
    viewer.undo();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state(), "undo brings the agent's text back");
    assertEquals("a\nL1\nR1\nc\nd\ne\n", result());
    assertEquals(List.of(p.block()), forgotten, "undo stores nothing again");
    viewer.redo();
    viewer.undo();
    viewer.takeBlock(0, ThreeSide.RIGHT, false);
    assertEquals(List.of(p.block()), forgotten, "forgotten once per block");
  }

  @Test
  void takingBothFromTheToolbarOverAnAgentBlockForgetsTheProposal() {
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    viewer.selectBlock(0);
    viewer.takeLeftThenRight();
    assertEquals(BlockState.TAKEN_BOTH, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);
  }

  @Test
  void typingIntoAnAgentBlockForgetsTheProposalOnce() throws Exception {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    IDocument d = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    d.replace(d.getLineOffset(1), 0, "x");
    UiDriver.flush();
    d.replace(d.getLineOffset(1), 0, "y");
    UiDriver.flush();
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);
    while (model.blocks().get(0).state() == BlockState.EDITED && viewer.canUndo()) {
      viewer.undo(); // the keystrokes may be one undo step or two
      UiDriver.flush();
    }
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state(), "undo brings the agent's text back");
    assertEquals(List.of(p.block()), forgotten, "undo stores nothing again");
  }

  @Test
  void ignoringAnAgentBlockForgetsTheProposalOnce() {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    viewer.ignoreBlock(0);
    assertEquals(BlockState.IGNORED, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);
    viewer.undo();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state(), "undo brings the agent's block back");
    viewer.ignoreBlock(0);
    assertEquals(List.of(p.block()), forgotten, "forgotten once per block");
  }

  @Test
  void aTakeAfterARevertAndItsUndoDoesNotTellTheStoreAgain() {
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    viewer.revertBlock(0);
    viewer.undo();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(List.of(p.block()), forgotten);
  }

  /** Joins lines 1 to 3 after a Take of block 0, as an import fix may rewrite a run over both blocks. */
  static final class Rewriting extends PlainPaneSetup {
    @Override
    public void afterBodyChange(MergeModel m, String before) {
      List<String> lines = new ArrayList<>(List.of(m.document().chunk(0, m.document().lineCount()).split("\n", -1)));
      if (m.blocks().get(0).state() == BlockState.TAKEN_LEFT && !lines.get(1).startsWith("// ")) {
        String joined = "// " + lines.get(1) + lines.get(2) + lines.get(3);
        lines.subList(1, 4).clear();
        lines.add(1, joined);
        m.applyDerivedText(String.join("\n", lines));
      }
    }
  }

  @Test
  void aBlockMadeEditedByADerivedChangeIsNoOverride() {
    shell.dispose();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell, new Rewriting());
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    viewer.setProposalForgetter(forgotten::add);
    viewer.setAgentsActive(true);
    shell.open();
    viewer.offerProposal(proposal(1, "X\n"));
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(1).state());
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(BlockState.EDITED, model.blocks().get(1).state(), "the rewrite took the agent's lines");
    assertTrue(forgotten.isEmpty(), "the user did not override the agent's block");
  }

  @Test
  void takingASideOverAnAutoSourceBlockForgetsNothing() {
    viewer.offerProposal(new Proposal(BlockId.of(model, 0), "X\n", "simple", Proposal.AUTO, T));
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertTrue(forgotten.isEmpty());
  }

  @Test
  void takingAWaitingProposalsBlockKeepsItWaiting() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    viewer.takeBlock(0, ThreeSide.RIGHT, true);
    assertEquals(Set.of(0), viewer.pendingProposals().keySet());
    assertTrue(forgotten.isEmpty());
  }

  @Test
  void markReviewedKeepsTheTextAndClearsTheFlag() {
    viewer.offerProposal(proposal(0, "L1\nR1\n"));
    viewer.markBlockReviewed(0);
    assertFalse(model.blocks().get(0).needsReview());
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", result());
  }

  @Test
  void aResolvedBlockKeepsTheProposalWaiting() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(Offer.PENDING, viewer.offerProposal(proposal(0, "L1\nR1\n")));
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertEquals(Set.of(0), viewer.pendingProposals().keySet());
  }

  @Test
  void anEditedBlockKeepsTheProposalWaiting() throws Exception {
    IDocument d = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    d.replace(d.getLineOffset(1), 1, "B");
    UiDriver.flush();
    assertEquals(BlockState.EDITED, model.blocks().get(0).state());
    assertEquals(Offer.PENDING, viewer.offerProposal(proposal(0, "L1\nR1\n")));
  }

  @Test
  void applyingAWaitingProposalReplacesTheBlockAsOneUndoStep() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "L1\nR1\n"));
    assertTrue(viewer.applyPending(0));
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertEquals("a\nL1\nR1\nc\nd\ne\n", result());
    assertTrue(viewer.pendingProposals().isEmpty());
    viewer.undo();
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertEquals("a\nL1\nc\nd\ne\n", result());
    assertFalse(viewer.applyPending(0), "nothing waits any more");
  }

  @Test
  void discardingAWaitingProposalForgetsIt() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal p = proposal(0, "L1\nR1\n");
    viewer.offerProposal(p);
    assertTrue(viewer.discardPending(0));
    assertTrue(viewer.pendingProposals().isEmpty());
    assertEquals(List.of(p.block()), forgotten);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
  }

  @Test
  void aProposalTheBlockAlreadyHoldsDoesNotWait() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(Offer.APPLIED, viewer.offerProposal(proposal(0, "L1\n")));
    assertTrue(viewer.pendingProposals().isEmpty());
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
  }

  @Test
  void aChangedConflictIsNotFound() {
    Proposal p = new Proposal(new BlockId(0, "000000000000"), "x\n", "", "test-agent", T);
    assertEquals(Offer.NOT_FOUND, viewer.offerProposal(p));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
  }

  @Test
  void proposalsOfferedTogetherAreOneUndoStep() {
    assertEquals(List.of(Offer.APPLIED, Offer.APPLIED),
        viewer.offerAll(List.of(proposal(0, "X\n"), proposal(1, "Y\n"))));
    assertEquals("a\nX\nc\nY\ne\n", result());
    viewer.undo();
    assertEquals("a\nb\nc\nd\ne\n", result());
    assertEquals(0, model.blocks().stream().filter(b -> b.state() == BlockState.AI_RESOLVED).count());
  }

  @Test
  void withdrawRevertsAnUnreviewedAppliedProposalAndDropsAWaitingOne() {
    Proposal applied = proposal(0, "X\n");
    viewer.offerProposal(applied);
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    Proposal waiting = proposal(1, "Y\n");
    viewer.offerProposal(waiting);
    assertTrue(viewer.withdrawProposal(applied));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(viewer.withdrawProposal(waiting));
    assertTrue(viewer.pendingProposals().isEmpty());
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
    assertTrue(forgotten.isEmpty(), "the agent took them back; the store is the server's business");
  }

  @Test
  void withdrawLeavesAReviewedOrChangedBlockAlone() {
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    viewer.markBlockReviewed(0);
    assertFalse(viewer.withdrawProposal(p));
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
  }

  @Test
  void switchingAgentsOffDropsWaitingProposalsButKeepsAppliedBlocks() {
    viewer.offerProposal(proposal(0, "X\n"));
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(1, "Y\n"));
    viewer.setAgentsActive(false);
    assertFalse(viewer.agentsActive());
    assertTrue(viewer.pendingProposals().isEmpty());
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
  }

  @Test
  void whileAgentsAreOffAnOfferChangesNothingAndNothingWaits() {
    viewer.setAgentsActive(false);
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    assertEquals(List.of(Offer.NOT_FOUND, Offer.NOT_FOUND),
        viewer.offerAll(List.of(proposal(0, "X\n"), proposal(1, "Y\n"))));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertTrue(viewer.pendingProposals().isEmpty());
    assertFalse(viewer.canShowPendingProposal(1));
  }

  @Test
  void heldProposalsAreNeverAppliedOnceAgentsWentOff() {
    viewer.setAgentsActive(false);
    viewer.offerAll(List.of(proposal(0, "X\n")), true);
    viewer.releaseHeld();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    viewer.setAgentsActive(true);
    viewer.offerAll(List.of(proposal(0, "X\n")), true);
    viewer.setAgentsActive(false);
    viewer.releaseHeld();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertFalse(viewer.hasHeld());
  }

  @Test
  void aWaitingProposalTheUserAnswersAfterAgentsWentOffChangesNothing() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    MergeViewer.setProposalChooserForTests((s, p) -> {
      viewer.setAgentsActive(false); // switched off while the dialog is open
      return PendingProposalDialog.Choice.APPLY;
    });
    try {
      viewer.showPendingProposal(0);
    } finally {
      MergeViewer.setProposalChooserForTests(null);
    }
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertTrue(forgotten.isEmpty());
  }

  @Test
  void autoProposalsBecomeAutoResolvedBlocks() {
    Proposal p = new Proposal(BlockId.of(model, 0), "X\n", "simple", Proposal.AUTO, T);
    viewer.offerProposal(p);
    assertEquals(BlockState.AUTO_RESOLVED, model.blocks().get(0).state());
    assertEquals("auto", model.blocks().get(0).resolvedBy());
    assertTrue(model.blocks().get(0).needsReview());
  }

  @Test
  void twoProposalsForOneBlockInOneCallActLikeTwoCalls() {
    assertEquals(List.of(Offer.APPLIED, Offer.PENDING),
        viewer.offerAll(List.of(proposal(0, "X\n"), proposal(0, "Y\n"))));
    assertEquals("a\nX\nc\nd\ne\n", result());
    assertEquals("Y\n", viewer.pendingProposals().get(0).text());
  }

  @Test
  void theSameProposalTwiceInOneCallIsAppliedOnce() {
    assertEquals(List.of(Offer.APPLIED, Offer.APPLIED),
        viewer.offerAll(List.of(proposal(0, "X\n"), proposal(0, "X\n"))));
    assertTrue(viewer.pendingProposals().isEmpty());
  }

  @Test
  void heldProposalsWaitWithoutChangingTheModelUntilReleased() {
    assertEquals(List.of(Offer.PENDING), viewer.offerAll(List.of(proposal(0, "X\n")), true));
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("a\nb\nc\nd\ne\n", result());
    assertFalse(viewer.canUndo());
    assertEquals(Set.of(0), viewer.pendingProposals().keySet());
    viewer.releaseHeld();
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertTrue(viewer.pendingProposals().isEmpty());
  }

  @Test
  void aHeldProposalTheUserDiscardedIsNotOfferedAgain() {
    viewer.offerAll(List.of(proposal(0, "X\n")), true);
    viewer.discardPending(0);
    viewer.releaseHeld();
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
  }

  @Test
  void revertingAnAppliedAutoSourceProposalForgetsIt() {
    Proposal p = new Proposal(BlockId.of(model, 0), "X\n", "simple", Proposal.AUTO, T);
    viewer.offerProposal(p);
    viewer.revertBlock(0);
    assertEquals(List.of(p.block()), forgotten);
  }

  @Test
  void revertingABlockOfNoProposalForgetsNothing() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.revertBlock(0);
    assertTrue(forgotten.isEmpty());
  }

  @Test
  void aFailingForgetterStopsNeitherRevertNorDiscard() {
    viewer.setProposalForgetter(id -> {
      throw new IllegalStateException("the store is gone");
    });
    viewer.offerProposal(proposal(0, "X\n"));
    viewer.revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals("a\nb\nc\nd\ne\n", result());
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(1, "Y\n"));
    assertTrue(viewer.discardPending(1));
    assertTrue(viewer.pendingProposals().isEmpty());
  }

  @Test
  void aNewInputStartsWithoutAgents() {
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    assertFalse(viewer.agentsActive());
    assertTrue(viewer.pendingProposals().isEmpty());
  }
}
