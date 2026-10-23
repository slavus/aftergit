package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve with AI design §3, §5: the entries, one conflict, a touched block, a failure, the log. */
class AiResolveTest {
  static final String BASE = "a\nb\nc\nd\ne\n";
  static final String OURS = "a\nL1\nc\nL3\ne\n";
  static final String THEIRS = "a\nR1\nc\nR3\ne\n";
  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener listener = (status, plugin) -> {
    if (status.getMessage().startsWith("Resolve with AI") || status.getMessage().startsWith("The AI resolver")) {
      logged.add(status);
    }
  };

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    StandInAiResolver.reset();
    repos = new ConflictRepos(dir);
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() throws Exception {
    ConflictRepos.waitUntil(() -> Job.getJobManager().find(AiResolveJob.FAMILY).length == 0, 30,
        "the AI runs to end");
    Platform.removeLogListener(listener);
    AiResolverContributions.setForTests(null);
    StandInAiResolver.reset();
    repos.dispose();
    MergePreferences.reset();
  }

  MergeEditorInput open() throws Exception {
    repos.conflict("a.txt", BASE, OURS, THEIRS);
    file = repos.plainProject("ai", "a.txt");
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

  static Menu more(MergeViewer v) {
    return v.toolbar().createMoreMenu(v.toolbar().control());
  }

  /** The texts of the items of {@code menu}, a submenu's as "parent > item". */
  static List<String> items(Menu menu) {
    List<String> out = new ArrayList<>();
    for (MenuItem i : menu.getItems()) {
      out.add(i.getText());
      if (i.getMenu() != null) {
        for (String inner : items(i.getMenu())) {
          out.add(i.getText() + " > " + inner);
        }
      }
    }
    return out;
  }

  static MenuItem item(Menu menu, String text) {
    for (MenuItem i : menu.getItems()) {
      if (text.equals(i.getText())) {
        return i;
      }
    }
    throw new AssertionError("no item '" + text + "' in " + items(menu));
  }

  /** The result's context menu as it is when it opens. */
  static Menu resultMenu(MergeViewer v) {
    Menu m = v.ai().resultMenu();
    m.notifyListeners(SWT.Show, new Event());
    return m;
  }

  static boolean resultMenuOpens(MergeViewer v) {
    Event e = new Event();
    e.doit = true;
    v.pane(PaneKind.RESULT).text().notifyListeners(SWT.MenuDetect, e);
    return e.doit;
  }

  static void waitForTheRun(MergeViewer v) {
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
  }

  @Test
  void withoutAReadyResolverTheEditorShowsNothingOfIt() throws Exception {
    MergeViewer v = open().viewer();
    Menu m = more(v);
    try {
      assertFalse(items(m).contains(Messages.aiResolveConflict));
      assertFalse(items(m).contains(Messages.aiResolveAll));
    } finally {
      m.dispose();
    }
    assertFalse(resultMenuOpens(v), "no context menu at all while no resolver is ready");
    assertFalse(v.aiActive());
    assertTrue(StandInAiResolver.requests.isEmpty());
  }

  @Test
  void aReadyResolverAddsBothEntriesToMoreAndToTheResultMenu() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    Menu m = more(v);
    try {
      assertTrue(item(m, Messages.aiResolveConflict).isEnabled());
      assertTrue(item(m, Messages.aiResolveAll).isEnabled());
    } finally {
      m.dispose();
    }
    assertTrue(resultMenuOpens(v));
    assertEquals(List.of(Messages.aiResolveConflict, Messages.aiResolveAll), items(resultMenu(v)));
    assertTrue(v.aiActive());
  }

  @Test
  void resolveConflictIsOffWhereThereIsNoOpenConflict() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    v.takeBlock(0, ThreeSide.LEFT, false);
    v.selectBlock(0);
    Menu m = more(v);
    try {
      assertFalse(item(m, Messages.aiResolveConflict).isEnabled());
      assertTrue(item(m, Messages.aiResolveAll).isEnabled(), "block 1 is still open");
    } finally {
      m.dispose();
    }
  }

  @Test
  void severalReadyResolversBecomeSubmenusByName() throws Exception {
    AiResolverContributions.setForTests(List.of(
        new AiResolverContributions.Contribution("a", "A", StandInAiResolver.fixed("Model A", false,
            (r, c) -> new AiAnswer("A\n", ""))),
        new AiResolverContributions.Contribution("b", "B", StandInAiResolver.fixed("Model B", false,
            (r, c) -> new AiAnswer("B\n", "")))));
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    Menu m = more(v);
    try {
      List<String> all = items(m);
      assertTrue(all.containsAll(List.of(Messages.aiResolveConflict + " > Model A",
          Messages.aiResolveConflict + " > Model B", Messages.aiResolveAll + " > Model A",
          Messages.aiResolveAll + " > Model B")), all.toString());
      MenuItem b = item(item(m, Messages.aiResolveConflict).getMenu(), "Model B");
      b.notifyListeners(SWT.Selection, new Event());
    } finally {
      m.dispose();
    }
    waitForTheRun(v);
    assertEquals("B\n", model(v).resultText(0));
    assertEquals("ai:Model B", model(v).blocks().get(0).resolvedBy());
  }

  @Test
  void resolveConflictAppliesTheAnswerAsOneUndoStepThatCanBeRevertedOrReviewed() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    MergeModel m = model(v);
    MergeBlock b = m.blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals("L1\nR1\n", m.resultText(0));
    assertEquals("ai:Stand-in model", b.resolvedBy());
    assertEquals("ours, then theirs", b.rationale());
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(1).state(), "only the chosen block");
    assertEquals(1, StandInAiResolver.requests.size());
    assertEquals("L1\n", StandInAiResolver.requests.get(0).ours());
    assertEquals("a.txt", StandInAiResolver.requests.get(0).fileName());
    assertTrue(v.noticeText().contains(NLS.bind(Messages.aiDone, new Object[] {"Stand-in model", 1, 0, 0})),
        v.noticeText());
    v.undo();
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    v.redo();
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(0).state());
    v.revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    v.markBlockReviewed(0);
    assertFalse(m.blocks().get(0).needsReview());
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(0).state());
  }

  @Test
  void theResultMenuResolvesTheBlockAtTheCaret() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    v.selectBlock(1);
    item(resultMenu(v), Messages.aiResolveConflict).notifyListeners(SWT.Selection, new Event());
    waitForTheRun(v);
    assertEquals("L3\nR3\n", model(v).resultText(1));
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
  }

  @Test
  void aBlockTouchedWhileAskingGetsAWaitingProposal() throws Exception {
    StandInAiResolver.ready = true;
    CountDownLatch asked = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      asked.countDown();
      try {
        go.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new AiAnswer("L1\nR1\n", "both");
    };
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    ConflictRepos.waitUntil(() -> asked.getCount() == 0, 10, "the request");
    v.takeBlock(0, ThreeSide.LEFT, false);
    go.countDown();
    waitForTheRun(v);
    assertEquals(BlockState.TAKEN_LEFT, model(v).blocks().get(0).state(), "the user's choice stays");
    assertEquals("L1\nR1\n", v.pendingProposals().get(0).text());
    assertTrue(v.noticeText().contains(NLS.bind(Messages.aiDone, new Object[] {"Stand-in model", 0, 1, 0})),
        v.noticeText());
    v.setAgentsActive(false);
    v.selectBlock(0);
    Menu m = more(v);
    try {
      assertTrue(item(m, Messages.proposalShow).isEnabled(), "a waiting AI proposal is shown while agents are off");
    } finally {
      m.dispose();
    }
  }

  @Test
  void aFailureLeavesTheBlockAndShowsTheMessageWithoutADialog() throws Exception {
    StandInAiResolver.ready = true;
    StandInAiResolver.answerer = (r, c) -> {
      throw new AiException("HTTP 500 from the model");
    };
    MergeEditorInput input = open();
    MergeViewer v = input.viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertTrue(v.noticeText().contains("HTTP 500 from the model"), v.noticeText());
    assertFalse(input.agentsHeld(), "no modal dialog");
    assertTrue(logged.stream().anyMatch(s -> s.getSeverity() == IStatus.INFO
        && s.getMessage().contains("failed: HTTP 500 from the model")), logged.toString());
  }

  @Test
  void resolveAllAsksOnceForEachOpenConflictInOrder() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    waitForTheRun(v);
    assertEquals(List.of("L1\n", "L3\n"), StandInAiResolver.requests.stream().map(r -> r.ours()).toList());
    MergeModel m = model(v);
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(1).state());
    assertEquals("L1\nR1\nc\n", StandInAiResolver.requests.get(1).before(),
        "the second request sees the first answer in its context (3 lines of the stand-in)");
    v.undo();
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(1).state(), "one undo step per answer");
    assertEquals(BlockState.AI_RESOLVED, m.blocks().get(0).state());
  }

  @Test
  void everyRequestIsLoggedAtInfoWithoutContents() throws Exception {
    StandInAiResolver.ready = true;
    MergeViewer v = open().viewer();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    waitForTheRun(v);
    List<IStatus> requests = logged.stream().filter(s -> s.getMessage().startsWith("Resolve with AI:")).toList();
    assertEquals(2, requests.size(), logged.toString());
    for (IStatus s : requests) {
      assertEquals(IStatus.INFO, s.getSeverity());
      assertTrue(s.getMessage().contains("Stand-in model"), s.getMessage());
      assertTrue(s.getMessage().contains(file.getFullPath().toString()), s.getMessage());
      assertTrue(s.getMessage().contains(": applied in "), s.getMessage());
      assertTrue(s.getMessage().contains("sent "), s.getMessage());
      assertFalse(s.getMessage().contains("L1") || s.getMessage().contains("R3"), "never contents");
    }
  }

  @Test
  void aRequestOverTheLimitIsRefusedBeforeItIsSent() throws Exception {
    StandInAiResolver.ready = true;
    String big = "x".repeat(net.aftergit.merge.core.ai.AiRequest.MAX_CHARS) + "\n";
    repos.conflict("a.txt", BASE, "a\n" + big + "c\nd\ne\n", THEIRS);
    file = repos.plainProject("ai", "a.txt");
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    ConflictRepos.open(input);
    MergeViewer v = input.viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    assertTrue(StandInAiResolver.requests.isEmpty(), "nothing was sent");
    assertTrue(v.noticeText().contains("200 000"), v.noticeText());
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
  }

  @Test
  void whileARunGoesOnBothEntriesAreOff() throws Exception {
    StandInAiResolver.ready = true;
    CountDownLatch go = new CountDownLatch(1);
    StandInAiResolver.answerer = (r, c) -> {
      try {
        go.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new AiAnswer("x\n", "");
    };
    MergeEditorInput input = open();
    MergeViewer v = input.viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    Menu m = more(v);
    try {
      assertFalse(item(m, Messages.aiResolveConflict).isEnabled());
      assertFalse(item(m, Messages.aiResolveAll).isEnabled());
      assertFalse(input.agentsHeld(), "no progress dialog");
    } finally {
      m.dispose();
      go.countDown();
    }
    waitForTheRun(v);
  }

  @Test
  void aResolverNamedAutoIsNeverTakenForAfterGitsOwnPass() throws Exception {
    AiResolverContributions.setForTests(List.of(new AiResolverContributions.Contribution("x", "X",
        StandInAiResolver.fixed("auto", false, (r, c) -> new AiAnswer("A\n", "")))));
    MergeViewer v = open().viewer();
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    waitForTheRun(v);
    MergeBlock b = model(v).blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertNotEquals(Proposal.AUTO, b.resolvedBy());
    assertEquals("ai:auto-model", b.resolvedBy());
  }

  @Test
  void aViewerWithoutASessionBuildsNoRequest() {
    Shell shell = new Shell(Display.getCurrent());
    try {
      MergeViewer v = new MergeViewer(shell);
      assertNull(v.aiRequest(0, 3));
      assertEquals(-1, v.ai().openConflict());
      assertTrue(v.ai().openConflicts().isEmpty());
    } finally {
      shell.dispose();
    }
  }
}
