package net.aftergit.merge.lsp.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.internal.LspActivator;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;

/**
 * AfterGit's server instances, one per server and project, shared by the resolvers and Resolve All: started on the
 * first use, stopped {@value #IDLE_MILLIS} ms after the last use ends. An instance that failed to start stays failed
 * for as long, so Resolve All does not wait for it at every file. {@link #stopAll} ends them all when the bundle
 * stops (Eclipse shuts down).
 */
@SuppressWarnings("restriction")
public final class SideServers {
  public static final long START_MILLIS = 30_000;
  public static final long CHECK_MILLIS = 10_000;
  /**
   * How long the first check of an instance may take: typescript-language-server, for one, answers initialize at
   * once but publishes nothing until tsserver has loaded the project, which takes as long as a start in a large one.
   */
  public static final long FIRST_CHECK_MILLIS = START_MILLIS;
  public static final long QUIET_MILLIS = 300;
  public static final long IDLE_MILLIS = 30_000;
  /** How long {@link #stopAll} waits for all stops together: one stop takes up to 1 s for shutdown, 2 s to end. */
  static final long STOP_ALL_MILLIS = 3500;

  private static final Map<String, Entry> ENTRIES = new HashMap<>();
  /**
   * The ids of the server definitions that left a virtual document unanswered: for the rest of the session (until
   * Eclipse or this bundle restarts) they are not asked again, so a later check or resolver does not wait for them.
   */
  private static final Set<String> SILENT = ConcurrentHashMap.newKeySet();
  private static final ScheduledThreadPoolExecutor STOPPER = new ScheduledThreadPoolExecutor(1, r -> {
    Thread t = new Thread(r, "AfterGit side checks: idle stop");
    t.setDaemon(true);
    return t;
  });

  static {
    STOPPER.setKeepAliveTime(1, TimeUnit.SECONDS); // no thread stays while nothing waits to stop
    STOPPER.allowCoreThreadTimeOut(true);
    STOPPER.setRemoveOnCancelPolicy(true);
  }

  private SideServers() {}

  private static final class Entry {
    final String key;
    final Object starting = new Object();
    volatile SideServer server;
    volatile boolean failed;
    volatile boolean ended;
    /** The start going on (or done), shared by every user that waits for it; guarded by {@code starting}. */
    CompletableFuture<Void> start;
    volatile Thread starter;
    int users;
    ScheduledFuture<?> stopping;

    Entry(String key) {
      this.key = key;
    }
  }

  /**
   * A use of the instance of {@code definition} for {@code project}; started here when needed (an instance whose
   * process ended is started anew), never after the bundle stopped (then the use is {@link Lease#stopped}). Close it
   * when done. Blocks while the instance starts, up to
   * {@value #START_MILLIS} ms, so never on the UI thread.
   */
  public static Lease acquire(LanguageServerDefinition definition, IProject project) {
    return acquire(definition, project, null);
  }

  /**
   * As {@link #acquire(LanguageServerDefinition, IProject)}, but a cancel of {@code monitor} (or an interrupt) while
   * the instance starts ends the wait at once with an {@link OperationCanceledException}; the start goes on for its
   * other users and stops when the instance is idle.
   */
  public static Lease acquire(LanguageServerDefinition definition, IProject project, IProgressMonitor monitor) {
    LspActivator.sideServersUsed = true;
    String key = definition.id + '\n' + project.getName();
    Entry e;
    synchronized (ENTRIES) {
      if (LspActivator.stopped) { // the bundle stopped: a stopped use, which a check reads as cancelled
        Entry none = new Entry(key);
        none.ended = true;
        none.users = 1;
        return new Lease(none);
      }
      e = ENTRIES.computeIfAbsent(key, k -> new Entry(k));
      e.users++;
      if (e.stopping != null) {
        e.stopping.cancel(false);
        e.stopping = null;
      }
    }
    Lease lease = null;
    try {
      CompletableFuture<Void> start;
      synchronized (e.starting) { // one start per instance; a second user waits for the same one
        SideServer old = e.server;
        if (old != null && !old.alive() && !e.ended) {
          e.server = null; // its process ended on its own
          old.stop();
        }
        if (e.server == null && !e.failed && !e.ended && (e.start == null || e.start.isDone())) {
          e.start = startInBackground(e, definition, project);
        }
        start = e.start;
      }
      if (start != null) {
        await(start, monitor);
      }
      lease = new Lease(e);
      return lease;
    } finally {
      if (lease == null) {
        release(e);
      }
    }
  }

  /** Waits for {@code start} (which ends by its own deadline), looking at {@code monitor} ten times a second. */
  private static void await(CompletableFuture<Void> start, IProgressMonitor monitor) {
    while (true) {
      if (monitor != null && monitor.isCanceled()) {
        throw new OperationCanceledException();
      }
      try {
        start.get(100, TimeUnit.MILLISECONDS);
        return;
      } catch (TimeoutException x) {
        // look at the monitor again
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
        throw new OperationCanceledException();
      } catch (ExecutionException x) {
        return; // start() records a failure itself
      }
    }
  }

  private static CompletableFuture<Void> startInBackground(Entry e, LanguageServerDefinition definition,
      IProject project) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    Thread t = new Thread(() -> {
      try {
        start(e, definition, project);
      } finally {
        e.starter = null;
        done.complete(null);
      }
    }, "AfterGit side check: starting " + definition.label);
    t.setDaemon(true);
    e.starter = t;
    t.start();
    return done;
  }

  private static void start(Entry e, LanguageServerDefinition definition, IProject project) {
    try {
      SideServer s = SideServer.start(definition, project, LspTestHooks.sideTime(0, START_MILLIS));
      e.server = s;
      if (e.ended) {
        s.stop(); // stopAll ran while it started
      }
    } catch (Throwable x) {
      if (x instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      e.failed = true;
      if (e.ended) { // stopped while it started: no failure of the server
        if (x instanceof VirtualMachineError v) {
          throw v;
        }
        return;
      }
      ILog.of(SideServers.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The language server "
          + definition.label + " could not be started for the checks of ours and theirs; new errors of its "
          + "files are shown as errors", Grammars.loggable(x)));
      if (x instanceof VirtualMachineError v) {
        throw v;
      }
    }
  }

  /** Remembers that {@code definition} did not check a virtual document within {@code checkMillis}; logged once. */
  static void rememberSilent(LanguageServerDefinition definition, long checkMillis) {
    if (SILENT.add(definition.id)) {
      ILog.of(SideServers.class).log(new Status(IStatus.INFO, Grammars.PLUGIN_ID, "The language server "
          + definition.label + " did not check a document that is not on disk within " + checkMillis
          + " ms; it is not asked again until Eclipse restarts, and new errors of its files are shown as errors"));
    }
  }

  /** True when {@code definition} left a virtual document unanswered in this session ({@link #rememberSilent}). */
  public static boolean silent(LanguageServerDefinition definition) {
    return SILENT.contains(definition.id);
  }

  /** Forgets the silent servers, as a restart of Eclipse does: when the bundle starts again, and for the tests. */
  public static void forgetSilent() {
    SILENT.clear();
    SideServer.forgetClients();
  }

  /** The instances running now (for the tests and the log). */
  public static int running() {
    synchronized (ENTRIES) {
      return (int) ENTRIES.values().stream().filter(x -> x.server != null && x.server.alive()).count();
    }
  }

  /**
   * Stops every instance at once, including those kept for their idle time and those in use (their checks return
   * null at once); the next {@link #acquire} starts anew. Called when the bundle stops.
   */
  public static void stopAll() {
    List<Entry> all;
    synchronized (ENTRIES) {
      all = new ArrayList<>(ENTRIES.values());
      ENTRIES.clear();
      for (Entry e : all) {
        if (e.stopping != null) {
          e.stopping.cancel(false);
          e.stopping = null;
        }
      }
    }
    CompletableFuture<?>[] stops = all.stream().map(e -> {
      CompletableFuture<Void> done = new CompletableFuture<>();
      Thread t = new Thread(() -> {
        try {
          stop(e);
        } finally {
          done.complete(null);
        }
      }, "AfterGit side check: stop");
      t.setDaemon(true);
      t.start();
      return done;
    }).toArray(CompletableFuture[]::new);
    try { // all at once under one deadline, so the shutdown of Eclipse waits for one stop, not for each
      CompletableFuture.allOf(stops).get(STOP_ALL_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException x) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException x) {
      // the stops that go on end their processes on their own threads
    }
    STOPPER.purge();
  }

  private static void release(Entry e) {
    long idle = LspTestHooks.sideTime(3, IDLE_MILLIS);
    synchronized (ENTRIES) {
      if (--e.users > 0 || ENTRIES.get(e.key) != e) {
        return; // still in use, or stopped by stopAll
      }
      if (idle > 0) {
        e.stopping = STOPPER.schedule(() -> stopIfIdle(e), idle, TimeUnit.MILLISECONDS);
        return;
      }
      ENTRIES.remove(e.key);
    }
    stop(e);
  }

  private static void stopIfIdle(Entry e) {
    synchronized (ENTRIES) {
      if (e.users > 0 || ENTRIES.get(e.key) != e) {
        return;
      }
      ENTRIES.remove(e.key);
    }
    stop(e);
  }

  /** Not under the start's lock: a start that goes on sees {@code ended} and stops what it started. */
  private static void stop(Entry e) {
    e.ended = true;
    Thread starting = e.starter;
    if (starting != null) {
      starting.interrupt(); // a start that waits ends now, and stops what it made
    }
    SideServer s = e.server;
    if (s != null) {
      s.stop();
    }
  }

  /** One use of an instance. */
  public static final class Lease implements AutoCloseable {
    private final Entry entry;
    private boolean closed;

    private Lease(Entry entry) {
      this.entry = entry;
    }

    /** True when the instance was stopped while this use held it ({@link #stopAll}): no answer is a rejection. */
    public boolean stopped() {
      return entry.ended;
    }

    /** The instance, or null when it did not start. */
    public SideServer server() {
      return entry.server;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        release(entry);
      }
    }
  }
}
