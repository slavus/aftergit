package net.aftergit.merge.lsp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.lsp.diagnostics.SideServer;
import net.aftergit.merge.lsp.diagnostics.SideServers;
import net.aftergit.merge.lsp.diagnostics.VirtualUris;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

/**
 * The language server's errors of ours and of theirs (languages design, Part 2), the counterpart of the Java
 * SideAnalysis: checked once, in the background, by AfterGit's own instance of each server of the file, as virtual
 * documents next to the file. When a server does not check them, both sides count as having none of its errors, so
 * every error of that server in the result in an auto-resolved block marks it ("new errors" falls back to "errors"
 * for that server); the answers of the other servers of the file still count.
 */
@SuppressWarnings("restriction") // LanguageServerDefinition: org.eclipse.lsp4e names its friends only
public final class SideChecks extends Job {
  /** Family of the side checks, for {@code Job.getJobManager().join}. */
  public static final Object FAMILY = new Object();
  private static final AtomicBoolean resolveAllFailureLogged = new AtomicBoolean();

  private final IFile file;
  private final String left;
  private final String right;
  private final Runnable done;
  /** The errors of ours and of theirs by server id, of the servers that checked them; null while unknown. */
  private volatile Map<String, List<LineProblem>> leftByServer;
  private volatile Map<String, List<LineProblem>> rightByServer;
  /** The ids of the servers that did not check ours and theirs. */
  private final Set<String> rejectedServers = ConcurrentHashMap.newKeySet();
  private volatile boolean finished;

  private SideChecks(IFile file, String left, String right, Runnable done) {
    super("Errors of ours and theirs");
    setSystem(true);
    this.file = file;
    this.left = left;
    this.right = right;
    this.done = done;
    addJobChangeListener(new JobChangeAdapter() {
      @Override
      public void done(IJobChangeEvent event) {
        finished = true; // also for a job cancelled before it ran
      }
    });
  }

  /**
   * Starts the checks; {@code done} runs on the job's thread once both sides are known (also when a server rejected
   * them), never after a cancel was seen. Ours and theirs are '\n' texts.
   */
  public static SideChecks start(IFile file, String left, String right, Runnable done) {
    SideChecks c = new SideChecks(file, left, right, done);
    c.schedule();
    return c;
  }

  /** Errors of ours by model line, or null while unknown (also for good without a server or after a cancel). */
  public List<LineProblem> leftErrors() {
    return leftErrors(Set.of());
  }

  /** Errors of theirs by model line, or null while unknown. */
  public List<LineProblem> rightErrors() {
    return rightErrors(Set.of());
  }

  /** Errors of ours by model line found by the servers not in {@code excluded}, or null while unknown. */
  public List<LineProblem> leftErrors(Collection<String> excluded) {
    return union(leftByServer, excluded);
  }

  /** Errors of theirs by model line found by the servers not in {@code excluded}, or null while unknown. */
  public List<LineProblem> rightErrors(Collection<String> excluded) {
    return union(rightByServer, excluded);
  }

  /** The lists of {@code byServer} of the servers not in {@code excluded}, in the servers' order; null for null. */
  static List<LineProblem> union(Map<String, List<LineProblem>> byServer, Collection<String> excluded) {
    if (byServer == null) {
      return null;
    }
    List<LineProblem> out = new ArrayList<>();
    byServer.forEach((id, errors) -> {
      if (!excluded.contains(id)) {
        out.addAll(errors);
      }
    });
    return List.copyOf(out);
  }

  /**
   * True when a server did not check ours and theirs: its errors are then in neither list, and only its errors in
   * the result count as new (languages design, Part 2: a server that does not answer rejects its own sides only).
   */
  public boolean rejected() {
    return !rejectedServers.isEmpty();
  }

  /** The lsp4e ids of the servers that did not check ours and theirs. */
  public Set<String> rejectedServers() {
    return Set.copyOf(rejectedServers);
  }

  /** True once the checks ended, with or without errors, or were cancelled. */
  public boolean finished() {
    return finished;
  }

  @Override
  public boolean belongsTo(Object family) {
    return family == FAMILY;
  }

  @Override
  protected IStatus run(IProgressMonitor monitor) {
    try {
      List<LanguageServerDefinition> definitions = Servers.definitionsFor(file);
      if (definitions.isEmpty()) {
        return Status.OK_STATUS; // no server (any more): no marks
      }
      Map<String, List<LineProblem>> l = new LinkedHashMap<>();
      Map<String, List<LineProblem>> r = new LinkedHashMap<>();
      List<Outcome> outcomes;
      try {
        outcomes = checkAll(file, definitions, List.of(left, right), List.of("ours", "theirs"), false, monitor);
      } catch (OperationCanceledException e) {
        return Status.CANCEL_STATUS; // a cancel, an interrupt or Eclipse stopping the instances: no rejection
      }
      if (monitor.isCanceled()) {
        return Status.CANCEL_STATUS;
      }
      for (Outcome o : outcomes) {
        if (o.failure() != null) {
          ILog.of(SideChecks.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "Ours and theirs of "
              + file.getFullPath() + " could not be checked by " + o.definition().label + "; its new errors are "
              + "shown as errors", Grammars.loggable(o.failure())));
        }
        if (o.found() == null) {
          rejectedServers.add(o.definition().id);
        } else {
          l.put(o.definition().id, o.found().get(0));
          r.put(o.definition().id, o.found().get(1));
        }
      }
      leftByServer = Collections.unmodifiableMap(l);
      rightByServer = Collections.unmodifiableMap(r);
    } finally {
      finished = true;
    }
    try {
      done.run(); // outside the check's try: a failing listener is not a failed check
    } catch (RuntimeException e) {
      ILog.of(SideChecks.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID,
          "A listener of the checks of ours and theirs failed", e));
    }
    return Status.OK_STATUS;
  }

  /**
   * The errors of {@code text} ('\n' lines) as the content of {@code file}, by model line, from every server that
   * checked it; null when unknown (no server, no server that checks it, or a cancel). Blocks while the servers start
   * and answer, so never on the UI thread.
   */
  public static List<LineProblem> errors(IFile file, String text, IProgressMonitor monitor) {
    List<List<LineProblem>> found = errors(file, List.of(text), false, monitor);
    return found == null ? null : found.get(0);
  }

  /**
   * The errors of the result, ours and theirs ('\n' lines) as the content of {@code file}, by model line, in this
   * order, for Resolve All: each server checks the result first, and ours and theirs only when it found an error
   * there (an empty list each otherwise); a server that leaves one of them unchecked is left out of all three, so
   * that the three are compared alike. Null when no server checked them, or on a cancel. Blocks while the servers
   * start and answer, so never on the UI thread.
   */
  public static List<List<LineProblem>> errors(IFile file, String result, String left, String right,
      IProgressMonitor monitor) {
    return errors(file, List.of(result, left, right), true, monitor);
  }

  private static List<List<LineProblem>> errors(IFile file, List<String> texts, boolean sidesOnlyForErrors,
      IProgressMonitor monitor) {
    List<LanguageServerDefinition> definitions = Servers.definitionsFor(file);
    if (definitions.isEmpty()) {
      return null;
    }
    List<String> roles = texts.stream().map(t -> "check").toList();
    List<List<LineProblem>> out = new ArrayList<>();
    texts.forEach(t -> out.add(new ArrayList<>()));
    boolean answered = false;
    List<Outcome> outcomes;
    try {
      outcomes = checkAll(file, definitions, texts, roles, sidesOnlyForErrors, monitor);
    } catch (OperationCanceledException e) {
      return null;
    }
    for (Outcome o : outcomes) {
      if (o.failure() != null && (monitor == null || !monitor.isCanceled())
          && resolveAllFailureLogged.compareAndSet(false, true)) {
        ILog.of(SideChecks.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "A text of "
            + file.getFullPath() + " could not be checked by its language server " + o.definition().label
            + "; Resolve All leaves out its errors", Grammars.loggable(o.failure())));
      }
      if (o.found() != null) {
        answered = true;
        for (int i = 0; i < texts.size(); i++) {
          out.get(i).addAll(o.found().get(i));
        }
      }
    }
    return answered ? out.stream().map(List::copyOf).toList() : null;
  }

  /** AfterGit's server instances running now (see SideServers). */
  public static int runningServers() {
    return SideServers.running();
  }

  /**
   * {@code texts} checked as {@code roles} by AfterGit's instance of {@code d}, by model line; null when it gives no
   * answer for one of them (it did not start, or it rejects such documents, also when it did so earlier in this
   * session: then it is not asked at all). With {@code restOnlyForErrors}, the
   * texts after the first are not checked when the first has no error (empty lists). The first check of an instance
   * may take {@link SideServers#FIRST_CHECK_MILLIS}: a server may publish nothing until it has loaded the project.
   * Throws {@link OperationCanceledException} on a cancel, an interrupt, or when the instance is stopped meanwhile.
   */
  /** What one server answered: its lists, null for no answer, and what it threw (then no answer), or null. */
  record Outcome(LanguageServerDefinition definition, List<List<LineProblem>> found, Throwable failure) {}

  /**
   * {@link #checkWith} for each of {@code definitions} at once, each on a thread of its own, so the wait is that of
   * the slowest server, not the sum; in the order of {@code definitions}. A server that throws gives no answer (its
   * failure is in the outcome). Throws {@link OperationCanceledException} on a cancel, an interrupt, or when Eclipse
   * stops the instances; the checks that still run see the monitor themselves and end.
   */
  static List<Outcome> checkAll(IFile file, List<LanguageServerDefinition> definitions, List<String> texts,
      List<String> roles, boolean restOnlyForErrors, IProgressMonitor monitor) {
    List<CompletableFuture<Outcome>> running = new ArrayList<>();
    for (LanguageServerDefinition d : definitions) {
      CompletableFuture<Outcome> f = new CompletableFuture<>();
      Runnable check = () -> {
        try {
          f.complete(new Outcome(d, checkWith(file, d, texts, roles, restOnlyForErrors, monitor), null));
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          f.complete(new Outcome(d, null, e)); // an OperationCanceledException too: rethrown below
        } catch (Throwable e) {
          f.completeExceptionally(e);
        }
      };
      if (definitions.size() == 1) {
        check.run(); // one server: no thread of its own
      } else {
        Thread t = new Thread(check, "AfterGit side check: " + d.label);
        t.setDaemon(true);
        t.start();
      }
      running.add(f);
    }
    CompletableFuture<Void> all = CompletableFuture.allOf(running.toArray(CompletableFuture[]::new));
    while (true) {
      cancelled(monitor, false);
      try {
        all.get(100, TimeUnit.MILLISECONDS);
        break;
      } catch (TimeoutException e) {
        // look at the monitor again
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new OperationCanceledException();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof Error err) {
          throw err; // a VirtualMachineError other than a stack overflow of the check
        }
        throw new IllegalStateException(e.getCause());
      }
    }
    List<Outcome> out = new ArrayList<>();
    for (CompletableFuture<Outcome> f : running) {
      Outcome o = f.join();
      if (o.failure() instanceof OperationCanceledException c) {
        throw c;
      }
      out.add(o);
    }
    return out;
  }

  static List<List<LineProblem>> checkWith(IFile file, LanguageServerDefinition d, List<String> texts,
      List<String> roles, boolean restOnlyForErrors, IProgressMonitor monitor) {
    if (SideServers.silent(d)) {
      return null; // it left a virtual document unanswered in this session: not asked again
    }
    long checkMillis = LspTestHooks.sideTime(1, SideServers.CHECK_MILLIS);
    long quietMillis = LspTestHooks.sideTime(2, SideServers.QUIET_MILLIS);
    long firstMillis = Math.max(checkMillis, LspTestHooks.sideTime(4, SideServers.FIRST_CHECK_MILLIS));
    cancelled(monitor, false);
    try (SideServers.Lease lease = SideServers.acquire(d, file.getProject(), monitor)) {
      SideServer server = lease.server();
      if (server == null) {
        cancelled(monitor, lease.stopped());
        return null; // it did not start (logged once)
      }
      String languageId = languageId(d, file.getName());
      List<List<LineProblem>> out = new ArrayList<>();
      for (int i = 0; i < texts.size(); i++) {
        if (i > 0 && restOnlyForErrors && out.get(0).isEmpty()) {
          out.add(List.of());
          continue;
        }
        URI uri = VirtualUris.sibling(file, roles.get(i));
        long wait = server.answeredOnce() ? checkMillis : firstMillis;
        List<Diagnostic> found = uri == null ? null
            : server.check(uri, languageId, texts.get(i), wait, quietMillis, monitor);
        if (found == null) {
          cancelled(monitor, server.stopped() || lease.stopped());
          return null;
        }
        out.add(List.copyOf(errorLines(found)));
      }
      return out;
    }
  }

  /** Throws when the check was cancelled, interrupted or {@code stopped}: then no answer is no rejection. */
  private static void cancelled(IProgressMonitor monitor, boolean stopped) {
    if (stopped || Thread.currentThread().isInterrupted() || (monitor != null && monitor.isCanceled())) {
      throw new OperationCanceledException();
    }
  }

  /**
   * The errors among {@code diagnostics} (a diagnostic without severity is one, as lsp4e reads it), by the line where
   * each starts. Public for the tests only; the user interface never sees lsp4j types.
   */
  public static List<LineProblem> errorLines(List<Diagnostic> diagnostics) {
    List<LineProblem> out = new ArrayList<>();
    for (Diagnostic d : diagnostics) {
      boolean error = d != null && (d.getSeverity() == null || d.getSeverity() == DiagnosticSeverity.Error);
      if (error && d.getRange() != null && d.getRange().getStart() != null) {
        out.add(new LineProblem(d.getRange().getStart().getLine(), ResultDiagnostics.message(d)));
      }
    }
    return out;
  }

  /**
   * The language id lsp4e sends for the file to {@code d}: its mapping of the file's content type, else the
   * extension. Public for the tests only.
   */
  public static String languageId(LanguageServerDefinition d, String fileName) {
    IContentType[] types = Platform.getContentTypeManager().findContentTypesFor(fileName);
    Map<IContentType, String> ids = d.languageIdMappings;
    for (IContentType t : types) {
      String id = ids.get(t);
      if (id != null) {
        return id;
      }
    }
    for (IContentType t : types) {
      for (Map.Entry<IContentType, String> e : ids.entrySet()) {
        if (t.isKindOf(e.getKey())) {
          return e.getValue();
        }
      }
    }
    String ext = new Path(fileName).getFileExtension();
    return ext != null ? ext : fileName;
  }
}
