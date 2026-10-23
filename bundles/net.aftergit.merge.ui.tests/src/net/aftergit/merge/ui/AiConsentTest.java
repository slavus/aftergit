package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolve with AI design §5: before the first request to a remote resolver the user is asked what goes where. */
class AiConsentTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  final List<String> asked = new ArrayList<>();
  AiActions.Consent answer = new AiActions.Consent(false, false);

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    StandInAiResolver.reset();
    StandInAiResolver.ready = true;
    StandInAiResolver.remote = true;
    repos = new ConflictRepos(dir);
    AiActions.setConsentForTests((shell, title, message) -> {
      asked.add(title + "\n" + message);
      return answer;
    });
  }

  @AfterEach
  void tearDown() throws Exception {
    ConflictRepos.waitUntil(() -> Job.getJobManager().find(AiResolveJob.FAMILY).length == 0, 30,
        "the AI runs to end");
    AiActions.setConsentForTests(null);
    StandInAiResolver.reset();
    repos.dispose();
    MergePreferences.reset();
  }

  MergeViewer open() throws Exception {
    repos.conflict("a.txt", AiResolveTest.BASE, AiResolveTest.OURS, AiResolveTest.THEIRS);
    IFile file = repos.plainProject("ai", "a.txt");
    MergeEditorInput input = new MergeEditorInput(file);
    input.setSaveDecision(ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    ConflictRepos.open(input);
    ConflictRepos.page().activate(UiDriver.editor(input));
    UiDriver.flush();
    return input.viewer();
  }

  static MergeModel model(MergeViewer v) {
    return ((MergeSession) v.getInput()).model();
  }

  void resolveFirst(MergeViewer v) {
    v.selectBlock(0);
    UiDriver.clickMore(v, Messages.aiResolveConflict);
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
  }

  @Test
  void aRemoteResolverAsksFirstAndCancelSendsNothing() throws Exception {
    MergeViewer v = open();
    resolveFirst(v);
    assertEquals(1, asked.size());
    assertTrue(StandInAiResolver.requests.isEmpty());
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
  }

  @Test
  void theQuestionSaysWhatIsSentAndToWhom() throws Exception {
    MergeViewer v = open();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    String q = asked.get(0);
    assertTrue(q.startsWith(NLS.bind(Messages.aiConsentTitle, "Stand-in model")), q);
    assertTrue(q.contains("Stand-in model"), q);
    assertTrue(q.contains("a.txt"), q);
    assertTrue(q.contains("2 conflict blocks"), q);
    assertTrue(q.contains("3 lines"), q);
    assertTrue(q.contains("https://stand-in.example:443"), q);
  }

  @Test
  void sendWithoutTheToggleAsksAgainNextTime() throws Exception {
    answer = new AiActions.Consent(true, false);
    MergeViewer v = open();
    resolveFirst(v);
    assertEquals(1, StandInAiResolver.requests.size());
    v.revertBlock(0);
    resolveFirst(v);
    assertEquals(2, asked.size());
    assertFalse(MergePreferences.aiRemoteAllowed(StandInAiResolver.destination));
  }

  @Test
  void doNotAskAgainIsRememberedForThisResolverInTheWorkspace() throws Exception {
    answer = new AiActions.Consent(true, true);
    MergeViewer v = open();
    resolveFirst(v);
    assertTrue(MergePreferences.aiRemoteAllowed(StandInAiResolver.destination));
    v.revertBlock(0);
    resolveFirst(v);
    assertEquals(1, asked.size());
    assertEquals(2, StandInAiResolver.requests.size());
  }

  @Test
  void aNewDestinationIsAskedAboutAgainAfterDoNotAskAgain() throws Exception {
    answer = new AiActions.Consent(true, true);
    MergeViewer v = open();
    resolveFirst(v);
    v.revertBlock(0);
    StandInAiResolver.destination = "https://other.example:443"; // the user changed the endpoint
    answer = new AiActions.Consent(false, false);
    resolveFirst(v);
    assertEquals(2, asked.size());
    assertTrue(asked.get(1).contains("https://other.example:443"), asked.get(1));
    assertEquals(1, StandInAiResolver.requests.size(), "nothing goes to the new destination");
    assertTrue(MergePreferences.aiRemoteAllowed("https://stand-in.example:443"));
    assertFalse(MergePreferences.aiRemoteAllowed("https://other.example:443"));
  }

  @Test
  void sendCoversEveryBlockOfTheRunWithoutAskingAgain() throws Exception {
    answer = new AiActions.Consent(true, false);
    MergeViewer v = open();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
    assertEquals(1, asked.size());
    assertEquals(2, StandInAiResolver.requests.size());
  }

  @Test
  void aLoopbackEndpointSwitchedToARemoteOneDuringARunStopsItBeforeSending() throws Exception {
    StandInAiResolver.remote = false;
    StandInAiResolver.destination = "http://localhost:11434";
    StandInAiResolver.answerer = (r, c) -> {
      StandInAiResolver.remote = true; // the user points the endpoint elsewhere while the first request runs
      StandInAiResolver.destination = "https://other.example:443";
      return new AiAnswer(r.ours(), "");
    };
    MergeViewer v = open();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
    assertTrue(asked.isEmpty(), "nobody is asked in the middle of a run");
    assertEquals(1, StandInAiResolver.requests.size(), "nothing is sent to the new destination");
    assertTrue(v.noticeText().contains(NLS.bind(Messages.aiNotAllowed,
        new Object[] {"Stand-in model", 1, 0, 0, "https://other.example:443"})), v.noticeText());
    UiDriver.clickMore(v, Messages.aiResolveAll);
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
    assertEquals(1, asked.size(), "the next run asks");
    assertEquals(1, StandInAiResolver.requests.size());
  }

  @Test
  void aRemoteDestinationSwitchedDuringARunStopsItBeforeSending() throws Exception {
    answer = new AiActions.Consent(true, false);
    StandInAiResolver.answerer = (r, c) -> {
      StandInAiResolver.destination = "https://other.example:443";
      return new AiAnswer(r.ours(), "");
    };
    MergeViewer v = open();
    UiDriver.clickMore(v, Messages.aiResolveAll);
    ConflictRepos.waitUntil(() -> !v.ai().running(), 30, "the AI run to end");
    assertEquals(1, StandInAiResolver.requests.size());
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(1).state());
  }

  @Test
  void aDestinationSwitchedWhileTheRequestIsBuiltGetsNothing() throws Exception {
    answer = new AiActions.Consent(true, false);
    boolean[] allowed = new boolean[1];
    AiActions.setConsentForTests((shell, title, message) -> {
      asked.add(title + "\n" + message);
      allowed[0] = true;
      return answer;
    });
    StandInAiResolver.onContextLines = () -> {
      if (allowed[0]) { // the request of the first block is being built: the user points the endpoint elsewhere
        StandInAiResolver.destination = "https://other.example:443";
      }
    };
    MergeViewer v = open();
    resolveFirst(v);
    assertEquals(1, asked.size());
    assertTrue(StandInAiResolver.requests.isEmpty(), "nothing goes to a destination the user did not allow");
    assertEquals(BlockState.UNRESOLVED, model(v).blocks().get(0).state());
    assertTrue(v.noticeText().contains(NLS.bind(Messages.aiNotAllowed,
        new Object[] {"Stand-in model", 0, 0, 0, "https://other.example:443"})), v.noticeText());
  }

  @Test
  void aLocalResolverNeverAsks() throws Exception {
    StandInAiResolver.remote = false;
    MergeViewer v = open();
    resolveFirst(v);
    assertTrue(asked.isEmpty());
    assertEquals(1, StandInAiResolver.requests.size());
  }

  /** Presses {@code button} in the dialog titled {@code title} once it shows, ticking its toggle first if asked. */
  static void pressWhenShown(String title, String button, boolean toggle) {
    Display d = Display.getCurrent();
    Runnable[] look = new Runnable[1];
    look[0] = () -> {
      for (Shell s : d.getShells()) {
        if (s.isVisible() && title.equals(s.getText())) {
          if (toggle) {
            Button t = find(s, Messages.aiConsentToggle);
            t.setSelection(true);
            t.notifyListeners(SWT.Selection, new Event());
          }
          find(s, button).notifyListeners(SWT.Selection, new Event());
          return;
        }
      }
      d.timerExec(50, look[0]);
    };
    d.timerExec(50, look[0]);
  }

  static Button find(Composite c, String text) {
    for (Control child : c.getChildren()) {
      if (child instanceof Button b && text.equals(b.getText())) {
        return b;
      }
      if (child instanceof Composite inner) {
        Button b = find(inner, text);
        if (b != null) {
          return b;
        }
      }
    }
    return null;
  }

  @Test
  void theRealDialogOffersSendCancelAndTheToggle() throws Exception {
    AiActions.setConsentForTests(null);
    MergeViewer v = open();
    String title = NLS.bind(Messages.aiConsentTitle, "Stand-in model");
    pressWhenShown(title, org.eclipse.jface.dialogs.IDialogConstants.CANCEL_LABEL, false);
    resolveFirst(v);
    assertTrue(StandInAiResolver.requests.isEmpty(), "Cancel sends nothing");
    pressWhenShown(title, Messages.aiConsentSend, true);
    resolveFirst(v);
    assertEquals(1, StandInAiResolver.requests.size(), "Send sends");
    assertTrue(MergePreferences.aiRemoteAllowed(StandInAiResolver.destination));
  }
}
