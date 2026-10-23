package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModelException;
import net.aftergit.merge.git.agents.OpenModels;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.resources.IFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

/** Agents design §3: an open resolver, the proposal source of the server and the OpenModels service. */
class AgentEditorTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
  static final String BASE = "a\nb\nc\nd\ne\n";
  static final String OURS = "a\nL1\nc\nL3\ne\n";
  static final String THEIRS = "a\nR1\nc\nR3\ne\n";
  @TempDir
  File dir;
  ConflictRepos repos;
  FakeProposalSource source;
  final List<String> status = new ArrayList<>();

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
    source = new FakeProposalSource();
  }

  @AfterEach
  void tearDown() throws Exception {
    source.unregister();
    repos.dispose();
    MergePreferences.reset();
  }

  IFile conflictFile() throws Exception {
    repos.conflict("a.txt", BASE, OURS, THEIRS);
    return repos.plainProject("agents", "a.txt");
  }

  static Proposal proposal(int block, String text) {
    MergeModel same = HybridInitializer.initialize(new MergeInput(BASE, OURS, THEIRS, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    return new Proposal(BlockId.of(same, block), text, "both lines", "test-agent", T);
  }

  MergeEditorInput open(IFile file) {
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    input.setStatusReporter(status::add);
    ConflictRepos.open(input);
    ConflictRepos.page().activate(UiDriver.editor(input));
    UiDriver.flush();
    return input;
  }

  @Test
  void storedProposalsAreAppliedWhenTheFileOpens() throws Exception {
    IFile file = conflictFile();
    source.stored.put(file, List.of(proposal(0, "L1\nR1\n")));
    source.register();
    MergeEditorInput input = open(file);
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    MergeBlock b = input.session().model().blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals("both lines", b.rationale());
  }

  @Test
  void staleStoredProposalsAreDiscardedAndTheStatusLineSaysSo() throws Exception {
    IFile file = conflictFile();
    source.stale = true;
    source.register();
    MergeEditorInput input = open(file);
    ConflictRepos.waitUntil(() -> input.viewer().noticeText().contains(Messages.aiStaleNotice), 10,
        "the stale notice");
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
  }

  @Test
  void withoutTheSourceNothingIsAppliedAndNothingShows() throws Exception {
    IFile file = conflictFile();
    source.stored.put(file, List.of(proposal(0, "L1\nR1\n"))); // never registered
    MergeEditorInput input = open(file);
    assertFalse(input.viewer().agentsActive());
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
  }

  @Test
  void turningTheSourceOnWhileOpenAppliesTheStoredProposals() throws Exception {
    IFile file = conflictFile();
    MergeEditorInput input = open(file);
    source.stored.put(file, List.of(proposal(1, "L3\nR3\n")));
    source.register();
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    assertEquals(BlockState.AI_RESOLVED, input.session().model().blocks().get(1).state());
  }

  @Test
  void waitingProposalsGoWhenTheSourceGoes() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    v.takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(Offer.PENDING, v.offerProposal(proposal(0, "L1\nR1\n")));
    source.unregister();
    ConflictRepos.waitUntil(() -> !v.agentsActive(), 10, "the agents to go");
    assertTrue(v.pendingProposals().isEmpty());
    assertEquals(BlockState.TAKEN_LEFT, input.session().model().blocks().get(0).state());
  }

  @Test
  void anOfferThatComesAfterTheSourceWentIsRefusedAsClosed() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    ViewerModel model = new ViewerModel(input);
    // the registration is gone at once; the resolver hears of it only later, on the UI thread
    source.unregister();
    assertTrue(input.viewer().agentsActive(), "the switch-off has not reached the resolver yet");
    OpenModelException offered = assertThrows(OpenModelException.class, () -> model.offer(proposal(0, "L1\nR1\n")));
    assertEquals(UiCall.CLOSED, offered.getMessage());
    OpenModelException withdrawn = assertThrows(OpenModelException.class,
        () -> model.withdraw(proposal(0, "L1\nR1\n")));
    assertEquals(UiCall.CLOSED, withdrawn.getMessage());
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    assertTrue(input.viewer().pendingProposals().isEmpty());
    UiDriver.flush();
    assertFalse(input.viewer().agentsActive());
  }

  @Test
  void aRevertWhileAgentsAreOffIsNotUndoneWhenTheyComeBack() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to show");
    Proposal p = proposal(0, "L1\nR1\n");
    assertEquals(Offer.APPLIED, offerFromServer(input, p));
    source.stored.put(file, List.of(p)); // the store keeps it: the files stay while agents are off
    source.unregister();
    ConflictRepos.waitUntil(() -> !v.agentsActive(), 10, "the agents to go");
    v.revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    assertTrue(source.forgotten.isEmpty(), "the store is out of reach while agents are off");
    source.register();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to come back");
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    assertTrue(v.pendingProposals().isEmpty(), "the reverted proposal is not offered again");
    assertEquals(List.of(p.block()), source.forgotten);
  }

  @Test
  void aTakeOverAnAgentBlockWhileAgentsAreOffIsToldToTheStoreWhenTheyComeBack() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to show");
    Proposal p = proposal(0, "L1\nR1\n");
    assertEquals(Offer.APPLIED, offerFromServer(input, p));
    source.stored.put(file, List.of(p));
    source.unregister();
    ConflictRepos.waitUntil(() -> !v.agentsActive(), 10, "the agents to go");
    v.takeBlock(0, ThreeSide.LEFT, false);
    assertTrue(source.forgotten.isEmpty(), "the store is out of reach while agents are off");
    source.register();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to come back");
    assertEquals(BlockState.TAKEN_LEFT, input.session().model().blocks().get(0).state());
    assertTrue(v.pendingProposals().isEmpty(), "the overridden proposal is not offered again");
    assertEquals(List.of(p.block()), source.forgotten);
  }

  @Test
  void anIgnoreOfAnAgentBlockWhileAgentsAreOffIsToldToTheStoreWhenTheyComeBack() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to show");
    Proposal p = proposal(1, "L3\nR3\n");
    assertEquals(Offer.APPLIED, offerFromServer(input, p));
    source.stored.put(file, List.of(p));
    source.unregister();
    ConflictRepos.waitUntil(() -> !v.agentsActive(), 10, "the agents to go");
    v.ignoreBlock(1);
    assertTrue(source.forgotten.isEmpty(), "the store is out of reach while agents are off");
    source.register();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to come back");
    assertEquals(BlockState.IGNORED, input.session().model().blocks().get(1).state());
    assertTrue(v.pendingProposals().isEmpty(), "the ignored proposal is not offered again");
    assertEquals(List.of(p.block()), source.forgotten);
  }

  @Test
  void aTakeOverAnAgentBlockTellsTheStore() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    ConflictRepos.waitUntil(v::agentsActive, 10, "the agents to show");
    Proposal p = proposal(1, "L3\nR3\n");
    assertEquals(Offer.APPLIED, offerFromServer(input, p));
    v.takeBlock(1, ThreeSide.RIGHT, false);
    assertEquals(List.of(p.block()), source.forgotten);
  }

  @Test
  void aStoredProposalAnEditorWasOfferedBeforeOnlyWaitsWhenTheFileOpensAgain() throws Exception {
    IFile file = conflictFile();
    Proposal shown = proposal(0, "L1\nR1\n");
    Proposal fresh = proposal(1, "L3\nR3\n");
    source.stored.put(file, List.of(shown, fresh));
    source.shown.add(shown.block()); // e.g. applied before, then undone or reverted while agents were off
    source.register();
    MergeEditorInput input = open(file);
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    assertEquals(shown, input.viewer().pendingProposals().get(0));
    assertEquals(BlockState.AI_RESOLVED, input.session().model().blocks().get(1).state());
    assertTrue(input.viewer().applyPending(0));
    assertEquals(BlockState.AI_RESOLVED, input.session().model().blocks().get(0).state());
  }

  @Test
  void discardAndRevertForgetThroughTheSource() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    Proposal applied = proposal(0, "L1\nR1\n");
    v.offerProposal(applied);
    v.revertBlock(0);
    v.takeBlock(1, ThreeSide.LEFT, false);
    Proposal waiting = proposal(1, "L3\nR3\n");
    v.offerProposal(waiting);
    v.discardPending(1);
    assertEquals(List.of(applied.block(), waiting.block()), source.forgotten);
  }

  @Test
  void theOpenModelsServiceFindsTheOpenFileAndWorksOnTheUiThread() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    ServiceTracker<OpenModels, OpenModels> tracker = new ServiceTracker<>(
        FrameworkUtil.getBundle(AgentEditorTest.class).getBundleContext(), OpenModels.class, null);
    tracker.open();
    try {
      OpenModels models = tracker.waitForService(5000);
      assertNotNull(models, "merge.ui registers OpenModels through OSGI-INF");
      assertTrue(models.find(file.getProject().getFile("other.txt")).isEmpty());
      OpenModel model = models.find(file).orElseThrow();
      assertEquals(file, model.file());
      CompletableFuture<Offer> offered = CompletableFuture.supplyAsync(() -> {
        try {
          return model.offer(proposal(0, "L1\nR1\n"));
        } catch (OpenModelException e) {
          throw new CompletionException(e);
        }
      });
      ConflictRepos.waitUntil(offered::isDone, 15, "the offer");
      assertEquals(Offer.APPLIED, offered.join());
      CompletableFuture<Boolean> onUi = CompletableFuture.supplyAsync(() -> {
        try {
          return model.read(m -> Display.getCurrent() != null && m == input.session().model());
        } catch (OpenModelException e) {
          throw new CompletionException(e);
        }
      });
      ConflictRepos.waitUntil(onUi::isDone, 15, "the read");
      assertTrue(onUi.join());
      assertEquals(ComparisonPolicy.DEFAULT, models.policy());
      assertNull(models.javaChecks(file), "a text file has no Java checks");
      ConflictRepos.page().closeAllEditors(false);
      UiDriver.flush();
      assertTrue(models.find(file).isEmpty(), "a closed resolver is no longer open");
    } finally {
      tracker.close();
    }
  }

  @Test
  void saveAndMarkResolvedDropsTheStoredProposalsOfTheFile() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    v.takeBlock(0, ThreeSide.LEFT, false);
    v.takeBlock(1, ThreeSide.RIGHT, false);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(List.of(file), source.resolved);
  }

  @Test
  void aSaveWithoutStagingKeepsTheStoredProposals() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, true));
    ConflictRepos.open(input);
    UiDriver.flush();
    input.viewer().takeBlock(0, ThreeSide.LEFT, false);
    assertEquals(MergeEditorInput.FinishOutcome.SAVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertTrue(source.resolved.isEmpty());
  }

  @Test
  void anOfferWaitsWhileTheUsersContentAssistIsOpenAndIsAppliedOnceItCloses() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    var result = (org.eclipse.jface.text.TextViewer) input.viewer().pane(PaneKind.RESULT).viewer();
    PopupsTest.AssistPopup popup = new PopupsTest.AssistPopup();
    assertTrue(result.requestWidgetToken(popup, 20));
    assertEquals(Offer.PENDING, offerFromServer(input, proposal(0, "L1\nR1\n")));
    assertFalse(popup.closed, "the user's popup stays open");
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    result.releaseWidgetToken(popup); // the user closed it
    ConflictRepos.waitUntil(() -> input.session().model().blocks().get(0).state() == BlockState.AI_RESOLVED, 10,
        "the held proposal");
  }

  @Test
  void finishCountsUnreviewedAiBlocksApart() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    MergeViewer v = input.viewer();
    v.offerProposal(proposal(0, "L1\nR1\n"));
    v.takeBlock(1, ThreeSide.RIGHT, false);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    ConflictRepos.waitUntil(() -> !status.isEmpty(), 10, "the status message");
    String message = status.get(0);
    assertTrue(message.contains(MessageFormat.format(Messages.finishAiNotReviewed, 1)), message);
    assertFalse(message.contains(MessageFormat.format(Messages.finishNotReviewed, 1)), message);
  }

  /** Offers {@code p} from another thread, as the server does, and pumps the UI until the answer is there. */
  static Offer offerFromServer(MergeEditorInput input, Proposal p) {
    CompletableFuture<Offer> offered = CompletableFuture.supplyAsync(() -> {
      try {
        return new ViewerModel(input).offer(p);
      } catch (OpenModelException e) {
        throw new CompletionException(e);
      }
    });
    ConflictRepos.waitUntil(offered::isDone, 15, "the offer");
    return offered.join();
  }

  Set<String> conflicting() throws Exception {
    try (Git git = Git.open(dir)) {
      return git.status().call().getConflicting();
    }
  }

  @Test
  void aProposalOfferedDuringTheSaveQuestionNeverReachesTheSavedFile() throws Exception {
    IFile file = conflictFile();
    source.register();
    List<Offer> during = new ArrayList<>();
    List<BlockState> stateDuring = new ArrayList<>();
    MergeEditorInput[] opened = new MergeEditorInput[1];
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        during.add(offerFromServer(opened[0], proposal(0, "L1\nR1\n")));
        stateDuring.add(opened[0].session().model().blocks().get(0).state());
        return SaveDecision.SaveChoice.MARKERS;
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        return SaveDecision.FinishChoice.CANCEL;
      }
    });
    opened[0] = input;
    ConflictRepos.open(input);
    UiDriver.flush();
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    input.viewer().takeBlock(1, ThreeSide.LEFT, false); // a change to save
    input.saveChanges(new NullProgressMonitor());
    assertEquals(List.of(Offer.PENDING), during);
    assertEquals(List.of(BlockState.UNRESOLVED), stateDuring);
    String saved = repos.read("a.txt");
    assertTrue(saved.contains("<<<<<<<"), saved);
    assertFalse(saved.contains("L1\nR1\n"), saved);
    assertEquals(Set.of("a.txt"), conflicting());
    ConflictRepos.waitUntil(() -> input.session().model().blocks().get(0).state() == BlockState.AI_RESOLVED, 10,
        "the held proposal to be offered again after the save");
    assertEquals(saved, repos.read("a.txt"), "offered again, never saved");
  }

  @Test
  void aProposalOfferedDuringTheFinishQuestionNeverReachesTheSavedFile() throws Exception {
    IFile file = conflictFile();
    source.register();
    List<Offer> during = new ArrayList<>();
    MergeEditorInput[] opened = new MergeEditorInput[1];
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(new SaveDecision() {
      @Override
      public SaveDecision.SaveChoice onUnresolved(int unresolved) {
        during.add(offerFromServer(opened[0], proposal(1, "L3\nR3\n")));
        return SaveDecision.SaveChoice.MARKERS;
      }

      @Override
      public SaveDecision.FinishChoice onFinishWithConflicts(int unresolved) {
        during.add(offerFromServer(opened[0], proposal(0, "L1\nR1\n")));
        return SaveDecision.FinishChoice.SAVE_WITHOUT_STAGING;
      }
    });
    opened[0] = input;
    ConflictRepos.open(input);
    UiDriver.flush();
    ConflictRepos.waitUntil(() -> input.viewer().agentsActive(), 10, "the agents to show");
    assertEquals(MergeEditorInput.FinishOutcome.SAVED, input.saveAndMarkResolved(new NullProgressMonitor()));
    assertEquals(List.of(Offer.PENDING, Offer.PENDING), during);
    String saved = repos.read("a.txt");
    assertFalse(saved.contains("L1\nR1\n"), saved);
    assertFalse(saved.contains("L3\nR3\n"), saved);
    assertEquals(Set.of("a.txt"), conflicting());
    ConflictRepos.waitUntil(() -> input.session().model().blocks().get(1).state() == BlockState.AI_RESOLVED, 10,
        "the held proposals to be offered again after the save");
    assertEquals(BlockState.AI_RESOLVED, input.session().model().blocks().get(0).state());
    assertEquals(saved, repos.read("a.txt"), "offered again, never saved");
  }

  @Test
  void offersNeverWriteTheFileNorStageIt() throws Exception {
    IFile file = conflictFile();
    source.register();
    MergeEditorInput input = open(file);
    byte[] before = repos.bytes("a.txt");
    MergeViewer v = input.viewer();
    assertEquals(Offer.APPLIED, offerFromServer(input, proposal(0, "L1\nR1\n")));
    v.takeBlock(1, ThreeSide.LEFT, false);
    assertEquals(Offer.PENDING, offerFromServer(input, proposal(1, "L3\nR3\n")));
    assertTrue(v.applyPending(1));
    UiDriver.flush();
    assertArrayEquals(before, repos.bytes("a.txt"));
    assertEquals(Set.of("a.txt"), conflicting());
    assertTrue(input.isDirty());
  }
}
