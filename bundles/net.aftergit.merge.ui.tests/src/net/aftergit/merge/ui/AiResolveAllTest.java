package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve with AI design §3, §5, §6: Resolve All with failures, a cancel, a closed editor, a switch turned off. */
class AiResolveAllTest {
  static final String BASE = "1\nb\n3\nd\n5\nf\n7\n";
  static final String OURS = "1\nL1\n3\nL2\n5\nL3\n7\n";
  static final String THEIRS = "1\nR1\n3\nR2\n5\nR3\n7\n";
  static final String NAME = "Stand-in model";
  @TempDir
  File dir;
  ConflictRepos repos;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener listener = (status, plugin) -> {
    if ("net.aftergit.merge.ui".equals(status.getPlugin())) {
      logged.add(status);
    }
  };

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    StandInAiResolver.reset();
    StandInAiResolver.ready = true;
    repos = new ConflictRepos(dir);
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() throws Exception {
    ConflictRepos.waitUntil(() -> Job.getJobManager().find(AiResolveJob.FAMILY).length == 0, 30,
        "the AI runs to end");
    Platform.removeLogListener(listener);
    StandInAiResolver.reset();
    repos.dispose();
    MergePreferences.reset();
  }

  MergeEditorInput open() throws Exception {
    repos.conflict("a.txt", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("ai", "a.txt");
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    ConflictRepos.open(input);
    ConflictRepos.page().activate(UiDriver.editor(input));
    UiDriver.flush();
    return input;
  }

  static MergeModel model(MergeViewer v) {
    return ((MergeSession) v.getInput()).model();
  }

  static String done(String key, int applied, int waiting, int failed) {
    return NLS.bind(key, new Object[] {NAME, applied, waiting, failed});
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  void resolveAll(MergeViewer v) {
    UiDriver.clickMore(v, Messages.aiResolveAll);
  }

  static void waitForTheRun(MergeViewer v) {
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
  }

  @Test
  void aFailedBlockIsReportedAndTheRunGoesOn() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      if (r.ours().equals("L1\n")) {
        throw new AiException("No answer within 120 s");
      }
      return new AiAnswer(r.theirs(), "");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    waitForTheRun(v);
    MergeModel m = model(v);
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(1).state());
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(2).state());
    assertTrue(v.noticeText().contains(done(Messages.aiDone, 2, 0, 1)), v.noticeText());
    assertTrue(v.noticeText().contains("No answer within 120 s"), v.noticeText());
  }

  @Test
  void aResolverThatThrowsFailsTheBlockAndTheRunGoesOn() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      switch (r.ours()) {
        case "L1\n" -> throw new IllegalStateException("a bug of the resolver");
        case "L2\n" -> throw new StackOverflowError();
        default -> {
          return null;
        }
      }
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    waitForTheRun(v);
    assertEquals(3, StandInAiResolver.requests.size());
    MergeModel m = model(v);
    for (int i = 0; i < 3; i++) {
      assertEquals(BlockState.UNRESOLVED, m.blocks().get(i).state());
    }
    assertTrue(v.noticeText().contains(done(Messages.aiDone, 0, 0, 3)), v.noticeText());
    assertTrue(v.noticeText().contains(Messages.aiNoAnswer), v.noticeText());
    assertEquals(2, logged.stream().filter(s -> s.getSeverity() == IStatus.WARNING).count(), logged.toString());
    for (IStatus s : logged) {
      assertFalse(s.getException() instanceof VirtualMachineError, "never a raw StackOverflowError in the log");
    }
  }

  @Test
  void anAnswerOverTheLimitIsRefused() throws Exception {
    StandInAiResolver.answerer = (r, c) -> new AiAnswer("x".repeat(AiAnswer.MAX_CHARS + 1), "");
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertTrue(v.noticeText().contains("200 000"), v.noticeText());
  }

  @Test
  void cancelStopsTheRemainingBlocksAndKeepsWhatIsApplied() throws Exception {
    CountDownLatch second = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, cancelled) -> {
      if (r.ours().equals("L1\n")) {
        return new AiAnswer("L1\nR1\n", "");
      }
      second.countDown();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!cancelled.getAsBoolean() && System.nanoTime() < end) {
        Thread.onSpinWait();
      }
      throw new AiException("The request was cancelled");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    ConflictRepos.waitUntil(() -> second.getCount() == 0, 10, "the second request");
    AiResolveJob job = v.ai().job();
    job.cancel();
    waitForTheRun(v);
    MergeModel m = model(v);
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(0).state(), "what is applied stays");
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(1).state());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(2).state());
    assertEquals(2, StandInAiResolver.requests.size(), "the third block is never asked");
    assertTrue(job.outcome().cancelled());
    assertTrue(v.noticeText().contains(done(Messages.aiStopped, 1, 0, 0)), v.noticeText());
  }

  @Test
  void anAnswerArrivingAfterCancelIsDropped() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      asked.countDown();
      await(go); // a resolver that does not look at cancelled
      return new AiAnswer("late\n", "");
    };
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the request");
    v.ai().job().cancel();
    go.countDown();
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertTrue(v.pendingProposals().isEmpty());
  }

  @Test
  void aBlockResolvedMeanwhileIsSkipped() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      if (r.ours().equals("L1\n")) {
        asked.countDown();
        await(go);
      }
      return new AiAnswer(r.ours() + r.theirs(), "");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the first request");
    v.takeBlock(1, ThreeSide.LEFT, false);
    go.countDown();
    waitForTheRun(v);
    assertEquals(List.of("L1\n", "L3\n"), StandInAiResolver.requests.stream().map(r -> r.ours()).toList());
    assertEquals(BlockState.TAKEN_LEFT, model(v).blocks().get(1).state());
    assertTrue(v.noticeText().contains(done(Messages.aiDone, 2, 0, 0)), v.noticeText());
  }

  @Test
  void closingTheEditorDuringARequestDropsTheAnswer() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    StandInAiResolver.answerer = (r, cancelled) -> {
      asked.countDown();
      await(go);
      sawCancel.set(cancelled.getAsBoolean());
      return new AiAnswer("late\n", "");
    };
    MergeEditorInput input = open();
    MergeViewer v = input.viewer();
    resolveAll(v);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the first request");
    ConflictRepos.page().closeEditor(UiDriver.editor(input), false);
    UiDriver.flush();
    go.countDown();
    ConflictRepos.waitUntil(() -> Job.getJobManager().find(AiResolveJob.FAMILY).length == 0, 30, "the run to end");
    assertTrue(sawCancel.get(), "closing cancels the request");
    assertEquals(1, StandInAiResolver.requests.size(), "nothing more is asked");
    assertTrue(logged.stream().anyMatch(s -> s.getMessage().contains("dropped")), logged.toString());
    assertTrue(logged.stream().noneMatch(s -> s.getSeverity() >= IStatus.WARNING), logged.toString());
    assertTrue(repos.read("a.txt").contains("<<<<<<<"), "nothing was saved: git's markers are still there");
  }

  @Test
  void reloadingTheEditorCancelsTheRun() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      asked.countDown();
      await(go);
      return new AiAnswer("late\n", "");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the first request");
    v.setInput(v.getInput());
    go.countDown();
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertEquals(1, StandInAiResolver.requests.size());
  }

  @Test
  void switchingTheResolverOffStopsTheRun() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      StandInAiResolver.ready = false; // the user unticks the switch while the first request runs
      return new AiAnswer(r.ours(), "");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    waitForTheRun(v);
    assertEquals(1, StandInAiResolver.requests.size(), "nothing is sent once it is off");
    assertTrue(v.noticeText().contains(done(Messages.aiSwitchedOff, 1, 0, 0)), v.noticeText());
  }

  @Test
  void anAnswerArrivingWhileADialogIsOpenWaitsAndIsAppliedOnceItCloses() throws Exception {
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      asked.countDown();
      await(go);
      return new AiAnswer(r.ours() + r.theirs(), "");
    };
    MergeEditorInput input = open();
    MergeViewer v = input.viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the request");
    Shell dialog = new Shell(Display.getCurrent(), SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
    try {
      dialog.setSize(200, 100);
      dialog.open(); // a modal dialog (such as "Save changes?") holds the offers; nothing blocks here
      assertTrue(input.agentsHeld());
      go.countDown();
      waitForTheRun(v);
      assertTrue(v.hasHeld(), "the answer waits while the dialog is open");
      assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    } finally {
      dialog.dispose();
    }
    ConflictRepos.waitUntil(() -> model(v).blocks().get(0).state() == BlockState.AI_RESOLVED, 10,
        "the held answer to be offered again");
    assertFalse(v.hasHeld());
  }

  @Test
  void aNewSessionWhileAnAnswerComesBackDropsItAndSendsNothingMore() throws Exception {
    CountDownLatch reloading = new CountDownLatch(1);
    MergeViewer[] viewer = new MergeViewer[1];
    StandInAiResolver.answerer = (r, c) -> {
      viewer[0].getControl().getDisplay().asyncExec(() -> {
        reloading.countDown();
        try {
          Thread.sleep(300); // the UI thread is busy while the job queues the answer
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        viewer[0].setInput(viewer[0].getInput());
      });
      await(reloading);
      return new AiAnswer("late\n", "");
    };
    MergeViewer v = open().viewer();
    viewer[0] = v;
    resolveAll(v);
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state(), "the old run's answer is dropped");
    assertTrue(v.pendingProposals().isEmpty());
    assertEquals(1, StandInAiResolver.requests.size(), "nothing is sent for the new session");
    assertFalse(v.noticeText().contains(done(Messages.aiStopped, 0, 0, 0)), v.noticeText());
  }

  @Test
  void anyErrorOfTheResolverFailsTheBlockAndTheRunGoesOn() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      if (r.ours().equals("L1\n")) {
        throw new AssertionError("a broken invariant of the resolver");
      }
      return new AiAnswer(r.theirs(), "");
    };
    MergeViewer v = open().viewer();
    resolveAll(v);
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertEquals(BlockState.AI_RESOLVED, model(v).blocks().get(2).state());
    assertTrue(v.noticeText().contains(done(Messages.aiDone, 2, 0, 1)), v.noticeText());
    assertTrue(logged.stream().noneMatch(s -> s.getSeverity() == IStatus.ERROR), logged.toString());
  }

  @Test
  void onlyAStackOverflowOfTheVirtualMachineErrorsCountsAsAFailedBlock() {
    assertFalse(AiResolveJob.fatal(new StackOverflowError()), "a deep recursion of the resolver fails its block");
    assertFalse(AiResolveJob.fatal(new AssertionError("a bug")));
    assertFalse(AiResolveJob.fatal(new IllegalStateException("a bug")));
    assertTrue(AiResolveJob.fatal(new OutOfMemoryError("test")), "out of memory goes on to the job manager");
    assertTrue(AiResolveJob.fatal(new InternalError("test")));
  }

  static List<String> infos(List<IStatus> logged) {
    return logged.stream().filter(st -> st.getSeverity() == IStatus.INFO).map(IStatus::getMessage).toList();
  }

  @Test
  void theServersWordsGoToTheStatusLineButNeverIntoTheLog() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      throw new AiException("HTTP 400 from stand-in.example: invalid content 'L1'", "HTTP 400 from stand-in.example",
          true);
    };
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    assertTrue(v.noticeText().contains("invalid content 'L1'"), v.noticeText());
    List<String> infos = infos(logged);
    assertEquals(1, infos.size(), infos.toString());
    assertTrue(infos.get(0).contains("failed: HTTP 400 from stand-in.example in "), infos.get(0));
    assertFalse(infos.get(0).contains("invalid content"), infos.get(0));
  }

  @Test
  void aRequestRefusedBeforeSendingIsLoggedAsRefusedWithNothingSent() throws Exception {
    StandInAiResolver.answerer = (r, c) -> {
      throw AiException.notSent("The API key could not be read");
    };
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    List<String> infos = infos(logged);
    assertEquals(1, infos.size(), infos.toString());
    assertTrue(infos.get(0).contains("refused before sending: The API key could not be read"), infos.get(0));
    assertTrue(infos.get(0).contains("(sent 0 characters, received 0)"), infos.get(0));
  }

  @Test
  void aRequestOverTheLimitIsLoggedAsRefused() throws Exception {
    StandInAiResolver.contextLines = 0;
    String big = "x".repeat(net.aftergit.merge.core.ai.AiRequest.MAX_CHARS) + "\n";
    repos.conflict("a.txt", BASE, OURS.replace("L1\n", big), THEIRS);
    IFile file = repos.plainProject("ai", "a.txt");
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    ConflictRepos.open(input);
    UiDriver.flush();
    MergeViewer v = input.viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    assertTrue(StandInAiResolver.requests.isEmpty());
    List<String> infos = infos(logged);
    assertEquals(1, infos.size(), infos.toString());
    assertTrue(infos.get(0).contains("refused before sending: "), infos.get(0));
    assertTrue(infos.get(0).contains("(sent 0 characters, received 0)"), infos.get(0));
  }
}
