package net.aftergit.merge.ui;

import java.time.Instant;
import java.util.List;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequests;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;

/**
 * One run of Resolve with AI in one editor (resolve-with-ai design §3): one request per block, in order, on this
 * job's thread; every request is built and every answer offered on the UI thread. Always ends with OK or CANCEL:
 * an error status would open a dialog, and the failures are in the status line and the log instead.
 */
final class AiResolveJob extends Job {
  static final Object FAMILY = new Object();

  record Outcome(int applied, int waiting, int failed, boolean cancelled) {}

  private final AiActions actions;
  private final AiResolverContributions.Contribution resolver;
  private final String name;
  private final List<BlockId> blocks;
  private volatile Outcome outcome;
  private volatile IProgressMonitor monitor;
  private volatile boolean stopped;
  private final int generation;
  private final String allowed;

  /**
   * A run for {@code blocks} in the session {@code generation} of the editor; {@code allowed} is the consent key the
   * user allowed when it started ("" for none).
   */
  AiResolveJob(AiActions actions, AiResolverContributions.Contribution resolver, List<BlockId> blocks, int generation,
      String allowed) {
    super(NLS.bind(Messages.aiJobName, AiResolverContributions.name(resolver)));
    this.actions = actions;
    this.resolver = resolver;
    this.name = AiResolverContributions.name(resolver);
    this.blocks = List.copyOf(blocks);
    this.generation = generation;
    this.allowed = allowed;
    setUser(true);
  }

  @Override
  public boolean belongsTo(Object family) {
    return family == FAMILY;
  }

  int generation() {
    return generation;
  }

  /** True once cancelled (the progress view, a new session, the editor closing). */
  boolean stopped() {
    return stopped;
  }

  @Override
  protected void canceling() {
    stopped = true;
  }

  Outcome outcome() {
    return outcome;
  }

  String resolverName() {
    return name;
  }

  /**
   * The proposal source for a resolver of this name: the name, but never {@link Proposal#AUTO} (any case), which
   * marks AfterGit's own automatic pass; a resolver of that name becomes "auto-model".
   */
  static String source(String name) {
    return Proposal.AUTO.equalsIgnoreCase(name) ? Proposal.AUTO + "-model" : name;
  }

  /**
   * True for an error the run must not swallow: a VirtualMachineError other than a StackOverflowError (a deep
   * recursion of the resolver, which only fails its block). It goes on to the job manager.
   */
  static boolean fatal(Throwable e) {
    return e instanceof VirtualMachineError && !(e instanceof StackOverflowError);
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    this.monitor = monitor;
    int applied = 0;
    int waiting = 0;
    int failed = 0;
    String lastFailure = null;
    String stoppedAs = null; // Messages.aiStopped (cancel, closed editor), aiSwitchedOff or aiNotAllowed
    String refused = "";
    monitor.beginTask(getName(), blocks.size());
    try {
      for (int i = 0; i < blocks.size(); i++) {
        if (cancelled()) {
          stoppedAs = Messages.aiStopped;
          break;
        }
        if (!AiResolverContributions.isReady(resolver)) {
          stoppedAs = Messages.aiSwitchedOff; // design §1: nothing is sent while it is off
          break;
        }
        BlockId id = blocks.get(i);
        int n = i + 1;
        AiActions.Prepared p = actions.onUi(
            () -> actions.current(this) ? actions.prepare(id, resolver, n, blocks.size()) : null);
        monitor.worked(1);
        if (p == null) {
          if (cancelled()) {
            stoppedAs = Messages.aiStopped;
            break;
          }
          continue; // resolved meanwhile (Ruling 4)
        }
        if (!AiActions.mayAsk(resolver, allowed)) { // right before sending: the endpoint may change at any time
          stoppedAs = Messages.aiNotAllowed; // design §5: a destination the user did not confirm gets nothing
          refused = AiResolverContributions.shownDestination(resolver);
          break;
        }
        long start = System.nanoTime();
        AiAnswer answer;
        try {
          AiRequests.checkSize(p.request());
          answer = resolver.resolver().resolve(p.request(), this::cancelled);
          if (answer == null) {
            throw new AiException(Messages.aiNoAnswer);
          }
          if (answer.resolution().length() > AiAnswer.MAX_CHARS) {
            throw new AiException(Messages.aiTooLong);
          }
        } catch (AiException e) {
          if (cancelled()) {
            log(p, "cancelled", start, e.sent() ? p.request().size() : 0, 0);
            stoppedAs = Messages.aiStopped;
            break;
          }
          failed++;
          lastFailure = e.getMessage();
          // the log never gets a server's own words (design §5), and a request that never left says so
          if (e.sent()) {
            log(p, "failed: " + e.logMessage(), start, p.request().size(), 0);
          } else {
            log(p, "refused before sending: " + e.logMessage(), start, 0, 0);
          }
          report(NLS.bind(Messages.aiBlockFailed, name, e.getMessage()));
          continue;
        } catch (RuntimeException | Error e) { // a bug of the resolver; the job still ends with OK or CANCEL
          if (fatal(e)) {
            throw e;
          }
          failed++;
          lastFailure = e.getClass().getSimpleName();
          AiLog.failure(name, e);
          log(p, "failed: " + lastFailure, start, p.request().size(), 0);
          report(NLS.bind(Messages.aiBlockFailed, name, lastFailure));
          continue;
        }
        if (cancelled()) {
          log(p, "dropped: cancelled", start, p.request().size(), answer.resolution().length());
          stoppedAs = Messages.aiStopped;
          break;
        }
        Proposal proposal = new Proposal(p.block(), answer.resolution(), answer.rationale(), source(name),
            Instant.now());
        Offer o = actions.onUi(() -> actions.current(this) ? actions.offer(proposal) : null);
        if (o == null) {
          log(p, actions.closed() ? "dropped: the editor is closed" : "dropped: cancelled", start,
              p.request().size(), answer.resolution().length());
          stoppedAs = Messages.aiStopped;
          break;
        }
        switch (o) {
          case APPLIED -> applied++;
          case PENDING -> waiting++;
          case NOT_FOUND -> {
            failed++;
            lastFailure = Messages.aiGone;
          }
        }
        log(p, switch (o) {
          case APPLIED -> "applied";
          case PENDING -> "waiting";
          case NOT_FOUND -> "dropped: the conflict is gone";
        }, start, p.request().size(), answer.resolution().length());
      }
    } finally {
      monitor.done();
    }
    Outcome out = new Outcome(applied, waiting, failed, Messages.aiStopped.equals(stoppedAs));
    outcome = out;
    String text = summary(out, stoppedAs == null ? Messages.aiDone : stoppedAs, lastFailure, refused);
    actions.onUi(() -> {
      if (actions.generation() == generation) { // a new session shows nothing of the old one's run
        actions.finished(text);
      }
      return null;
    });
    return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
  }

  /** Cancelled from the progress view, by a new session or by closing the editor. */
  private boolean cancelled() {
    IProgressMonitor m = monitor;
    return stopped || actions.closed() || m != null && m.isCanceled();
  }

  private void report(String text) {
    actions.onUi(() -> {
      if (actions.current(this)) {
        actions.report(text);
      }
      return null;
    });
  }

  private String summary(Outcome o, String key, String lastFailure, String refused) {
    String counts = NLS.bind(key, new Object[] {name, o.applied(), o.waiting(), o.failed(), refused});
    return lastFailure == null ? counts
        : MergeViewer.joinNotices(counts, NLS.bind(Messages.aiLastFailure, lastFailure));
  }

  private void log(AiActions.Prepared p, String outcomeText, long start, int sent, int received) {
    AiLog.request(name, actions.filePath(), p.block(), outcomeText, (System.nanoTime() - start) / 1_000_000, sent,
        received);
  }
}
