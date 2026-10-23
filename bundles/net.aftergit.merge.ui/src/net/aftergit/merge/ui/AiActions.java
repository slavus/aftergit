package net.aftergit.merge.ui;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Resolve with AI in one editor (resolve-with-ai design §3): the entries in "More" and in the result's context menu
 * while a resolver is ready, and the run that asks it. All methods but {@link #onUi} and {@link #filePath} run on
 * the UI thread.
 */
final class AiActions {
  /** The block a request is for, as it was when the request was built, and the request. */
  record Prepared(BlockId block, AiRequest request) {}

  /** The user's answer to the question before a remote resolver is asked (design §5). */
  record Consent(boolean send, boolean dontAskAgain) {}

  /** Asks the user; replaced in tests. */
  interface ConsentAsker {
    Consent ask(Shell shell, String title, String message);
  }

  private static volatile ConsentAsker consent = AiActions::askConsent;

  private final MergeViewer viewer;
  private final Display display;
  private volatile boolean closed;
  private volatile String filePath = "";
  private Function<Proposal, Offer> offer;
  private AiResolveJob running;
  private int generation;
  private Menu resultMenu;

  AiActions(MergeViewer viewer) {
    this.viewer = viewer;
    this.display = Display.getCurrent();
    this.offer = p -> viewer.offerAi(p, false);
  }

  /** How an answer reaches the editor; the editor input holds it during a save (see MergeEditorInput#offerAi). */
  void setOffer(Function<Proposal, Offer> offer) {
    this.offer = offer;
  }

  /** The workspace path of the file, for the log. */
  void setFilePath(String path) {
    filePath = path == null ? "" : path;
  }

  String filePath() {
    String p = filePath;
    if (!p.isEmpty()) {
      return p;
    }
    return viewer.getInput() instanceof MergeSession s ? s.fileName() : "";
  }

  boolean running() {
    return running != null;
  }

  AiResolveJob job() {
    return running;
  }

  boolean closed() {
    return closed;
  }

  /**
   * Adds the entries to {@code menu} while at least one resolver is ready and returns true; otherwise adds nothing,
   * hides the answers of the AI resolvers (design §1) and returns false.
   */
  boolean fill(Menu menu) {
    List<AiResolverContributions.Contribution> ready = AiResolverContributions.ready();
    viewer.setAiActive(!ready.isEmpty());
    if (ready.isEmpty()) {
      return false;
    }
    boolean idle = running == null;
    boolean one = idle && openConflict() >= 0;
    boolean all = idle && !openConflicts().isEmpty();
    if (ready.size() == 1) {
      AiResolverContributions.Contribution c = ready.get(0);
      item(menu, MergeToolbar.withKeys(Messages.aiResolveConflict, SiteServices.AI_RESOLVE_CONFLICT), one,
          () -> resolveConflict(c));
      item(menu, MergeToolbar.withKeys(Messages.aiResolveAll, SiteServices.AI_RESOLVE_ALL), all, () -> resolveAll(c));
    } else {
      Menu conflict = submenu(menu, Messages.aiResolveConflict);
      Menu every = submenu(menu, Messages.aiResolveAll);
      for (AiResolverContributions.Contribution c : ready) {
        String name = AiResolverContributions.name(c);
        item(conflict, name, one, () -> resolveConflict(c));
        item(every, name, all, () -> resolveAll(c));
      }
    }
    return true;
  }

  private static void item(Menu menu, String text, boolean enabled, Runnable run) {
    MenuItem mi = new MenuItem(menu, SWT.PUSH);
    mi.setText(text);
    mi.setEnabled(enabled);
    mi.addListener(SWT.Selection, e -> run.run());
  }

  private static Menu submenu(Menu menu, String text) {
    MenuItem cascade = new MenuItem(menu, SWT.CASCADE);
    cascade.setText(text);
    Menu sub = new Menu(cascade);
    cascade.setMenu(sub);
    return sub;
  }

  /**
   * Gives the result its context menu with the entries (Ruling 2 of plan 6b); while no resolver is ready it does not
   * open. A widget that has a menu already keeps it.
   */
  void installResultMenu(StyledText text) {
    if (text.getMenu() != null) {
      return;
    }
    Menu menu = new Menu(text);
    menu.addListener(SWT.Show, e -> {
      for (MenuItem i : menu.getItems()) {
        i.dispose();
      }
      fill(menu);
    });
    text.addListener(SWT.MenuDetect, e -> {
      if (AiResolverContributions.ready().isEmpty()) {
        e.doit = false;
        viewer.setAiActive(false);
      }
    });
    text.setMenu(menu);
    resultMenu = menu;
  }

  Menu resultMenu() {
    return resultMenu;
  }

  private MergeModel model() {
    return viewer.getInput() instanceof MergeSession s ? s.model() : null;
  }

  private static boolean open(MergeBlock b) {
    return b.isConflict() && b.state() == BlockState.UNRESOLVED;
  }

  /** The block "Resolve Conflict with AI" acts on (as Take Left does), when it is an open conflict; else -1. */
  int openConflict() {
    MergeModel m = model();
    int b = m == null ? -1 : viewer.currentBlock();
    return b >= 0 && open(m.blocks().get(b)) ? b : -1;
  }

  /** The open conflicts of the file, in order. */
  List<BlockId> openConflicts() {
    MergeModel m = model();
    List<BlockId> out = new ArrayList<>();
    if (m != null) {
      for (MergeBlock b : m.blocks()) {
        if (open(b)) {
          out.add(BlockId.of(m, b.index()));
        }
      }
    }
    return out;
  }

  /**
   * The command "Resolve Conflict with AI": with one ready resolver at once; with several the result's menu opens at
   * the caret, where the user picks one (as from the context menu).
   */
  void resolveConflictCommand() {
    List<AiResolverContributions.Contribution> ready = AiResolverContributions.ready();
    if (ready.size() == 1) {
      resolveConflict(ready.get(0));
    } else if (ready.size() > 1) {
      showResultMenu();
    }
  }

  /** The command "Resolve All with AI", as {@link #resolveConflictCommand}. */
  void resolveAllCommand() {
    List<AiResolverContributions.Contribution> ready = AiResolverContributions.ready();
    if (ready.size() == 1) {
      resolveAll(ready.get(0));
    } else if (ready.size() > 1) {
      showResultMenu();
    }
  }

  private void showResultMenu() {
    if (resultMenu == null || resultMenu.isDisposed()) {
      return;
    }
    StyledText t = viewer.pane(PaneKind.RESULT).text();
    org.eclipse.swt.graphics.Point p = t.getLocationAtOffset(t.getCaretOffset());
    resultMenu.setLocation(t.toDisplay(p.x, p.y + t.getLineHeight()));
    resultMenu.setVisible(true);
  }

  void resolveConflict(AiResolverContributions.Contribution c) {
    int b = openConflict();
    if (b >= 0 && running == null) {
      start(c, List.of(BlockId.of(model(), b)));
    }
  }

  void resolveAll(AiResolverContributions.Contribution c) {
    List<BlockId> blocks = openConflicts();
    if (!blocks.isEmpty() && running == null) {
      start(c, blocks);
    }
  }

  /** Replaces the question in tests; null puts the dialog back. */
  static void setConsentForTests(ConsentAsker asker) {
    consent = asker == null ? AiActions::askConsent : asker;
  }

  /** Send / Cancel with "Do not ask again in this workspace"; closing the dialog is Cancel. */
  static Consent askConsent(Shell shell, String title, String message) {
    MessageDialogWithToggle d = new MessageDialogWithToggle(shell, title, null, message, MessageDialog.QUESTION,
        new String[] {Messages.aiConsentSend, IDialogConstants.CANCEL_LABEL}, 1, Messages.aiConsentToggle, false);
    int r = d.open();
    boolean send = r != IDialogConstants.CANCEL_ID && r != SWT.DEFAULT;
    return new Consent(send, send && d.getToggleState());
  }

  /**
   * The consent key the user allowed for this run when {@code c} may be asked about {@code blocks} blocks now: ""
   * for a local resolver or a destination allowed before, its key when the user says Send; null when not.
   */
  private String consented(AiResolverContributions.Contribution c, int blocks) {
    String key = AiResolverContributions.consentKey(c);
    if (!AiResolverContributions.remote(c) || MergePreferences.aiRemoteAllowed(key)) {
      return "";
    }
    String name = AiResolverContributions.name(c);
    String fileName = viewer.getInput() instanceof MergeSession s ? s.fileName() : "";
    String message = MessageFormat.format(Messages.aiConsentMessage, name, fileName, blocks,
        AiResolverContributions.contextLines(c), AiResolverContributions.shownDestination(c));
    Consent answer = consent.ask(viewer.getControl().getShell(), NLS.bind(Messages.aiConsentTitle, name), message);
    if (answer.send() && answer.dontAskAgain()) {
      MergePreferences.setAiRemoteAllowed(key, true);
    }
    return answer.send() ? key : null;
  }

  /**
   * True when {@code c} may be sent a request now (job thread, before each one): it is local, or its destination is
   * the one the user allowed when the run started ({@code allowedThisRun}) or one allowed for the workspace.
   */
  static boolean mayAsk(AiResolverContributions.Contribution c, String allowedThisRun) {
    if (!AiResolverContributions.remote(c)) {
      return true;
    }
    String key = AiResolverContributions.consentKey(c);
    return key.equals(allowedThisRun) || MergePreferences.aiRemoteAllowed(key);
  }

  /** A new session in the editor: a running run is cancelled, and nothing of it reaches the new session. */
  void newSession() {
    generation++;
    cancel();
  }

  /** The session counter the job started with; bumped by {@link #newSession()} (UI thread). */
  int generation() {
    return generation;
  }

  /** True while {@code job} is this editor's run, not cancelled, for the session it started in (UI thread). */
  boolean current(AiResolveJob job) {
    return !closed && running == job && job.generation() == generation && !job.stopped();
  }

  /** The editor closed (design §3): a running request is cancelled and its answer dropped. */
  void close() {
    closed = true;
    cancel();
  }

  /** Cancels a running run (a new session, the editor closing); what is applied stays. */
  void cancel() {
    AiResolveJob j = running;
    if (j != null) {
      j.cancel();
    }
  }

  private void start(AiResolverContributions.Contribution c, List<BlockId> blocks) {
    String allowed = consented(c, blocks.size());
    if (allowed == null) {
      return;
    }
    AiResolveJob job = new AiResolveJob(this, c, blocks, generation, allowed);
    running = job;
    job.addJobChangeListener(new JobChangeAdapter() {
      @Override
      public void done(IJobChangeEvent event) {
        onUi(() -> {
          if (running == job) {
            running = null;
          }
          return null;
        });
      }
    });
    job.schedule();
  }

  /**
   * Runs {@code s} on the UI thread and returns its value; null once the editor is closed or the display is gone.
   * Called from the job's thread.
   */
  <T> T onUi(Supplier<T> s) {
    Display d = display;
    if (d == null || d.isDisposed()) {
      return null;
    }
    List<T> out = new ArrayList<>(1);
    try {
      d.syncExec(() -> {
        if (!closed && !viewer.getControl().isDisposed()) {
          out.add(s.get());
        }
      });
    } catch (SWTException e) {
      return null; // the display went away meanwhile
    }
    return out.isEmpty() ? null : out.get(0);
  }

  /**
   * The request for {@code id} when it is still an open conflict, after the status line says that it is asked;
   * null otherwise (UI thread).
   */
  Prepared prepare(BlockId id, AiResolverContributions.Contribution c, int n, int m) {
    MergeModel model = model();
    int b = model == null ? -1 : id.find(model);
    if (b < 0 || !open(model.blocks().get(b))) {
      return null;
    }
    AiRequest request = viewer.aiRequest(b, AiResolverContributions.contextLines(c));
    if (request == null) {
      return null;
    }
    viewer.showAiNotice(NLS.bind(Messages.aiAsking, new Object[] {AiResolverContributions.name(c), n, m}));
    return new Prepared(BlockId.of(model, b), request);
  }

  /** Offers an answer (UI thread); NOT_FOUND when the AI resolvers were hidden meanwhile. */
  Offer offer(Proposal p) {
    return offer.apply(p);
  }

  void report(String text) {
    viewer.showAiNotice(text);
  }

  void finished(String text) {
    viewer.showAiNotice(text);
  }
}
