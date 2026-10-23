package net.aftergit.merge.mcp;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.git.RepositoryLocator;
import net.aftergit.merge.git.agents.OpenModels;
import net.aftergit.merge.git.agents.ProposalSource;
import net.aftergit.merge.mcp.protocol.McpHttpServer;
import net.aftergit.merge.mcp.protocol.McpProtocol;
import net.aftergit.merge.mcp.service.ConflictResolverService;
import net.aftergit.merge.mcp.service.ProposalStore;
import net.aftergit.merge.mcp.service.StoreProposalSource;
import net.aftergit.merge.mcp.tools.ToolSet;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jgit.lib.Repository;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The MCP server following its switch (agents design §1, §2, §4). While the switch is off nothing runs, no port is
 * open, no token exists and secure storage is not even opened. Switching off stops at once, revokes the token and
 * forgets the proposals in memory; the stored files stay.
 *
 * <p>Threads: switching (apply, follow, regenerateToken) is serialized by a lock that the getters never take, so
 * running(), url(), token(), port() and failure() answer at once, also while secure storage is slow or locked.
 * Listeners run after that lock is released, on the thread that made the change. A switch that runs again on the
 * same thread while it is still switching (e.g. from the event loop of a secure storage password dialog) does not
 * start a second server: it only makes the running switch look at the preferences once more when it is done.
 */
public final class AgentServer {
  public static final String INSTRUCTIONS = "AfterGit shows the git merge conflicts of this Eclipse workspace. "
      + "Call list_conflicts, then get_conflict for a file, and propose a resolution per conflict block with "
      + "propose_resolution and a short rationale. The user reviews every proposal in AfterGit; in an open editor a "
      + "proposal for an untouched conflict is applied at once. validate compiles Java files with your proposals in "
      + "place; auto_resolve first lets AfterGit resolve what it can. No tool saves, stages or writes files.";

  private static AgentServer instance;

  private final TokenStore tokens;
  private final BundleContext context;
  private final IEclipsePreferences prefs;
  private final IEclipsePreferences.IPreferenceChangeListener preferenceListener = e -> apply();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  /** Serializes switching; never taken by the getters, never held while listeners run. */
  private final Object switching = new Object();
  private ServiceTracker<OpenModels, OpenModels> openModels;
  /** The running server with its port, published together: a getter never sees one without the other. */
  private volatile Live live;
  private ProposalStore store;
  private ServiceRegistration<ProposalSource> registration;
  private volatile String token;
  private int configuredPort = -1;
  private volatile String failure;
  private boolean following;
  /** An apply() is running (guarded by {@link #switching}, so only its own thread can see it true). */
  private boolean applying;
  /** A nested apply() came while one ran: look at the preferences again. */
  private boolean again;

  private record Live(McpHttpServer server, int port) {
    String url() {
      return "http://127.0.0.1:" + port + McpHttpServer.PATH;
    }
  }

  public static synchronized AgentServer instance() {
    if (instance == null) {
      instance = new AgentServer(new SecureTokenStore(SecureTokenStore::openDefault),
          FrameworkUtil.getBundle(AgentServer.class).getBundleContext(), McpPreferences.node());
    }
    return instance;
  }

  /** The server the preference page and the startup use (tests); the one replaced is stopped. Null makes the real
   * one again on demand. */
  public static void setInstanceForTests(AgentServer server) {
    AgentServer old;
    synchronized (AgentServer.class) {
      old = instance;
      instance = server;
    }
    if (old != null && old != server) {
      old.unfollow();
      old.shutdown();
    }
  }

  public AgentServer(TokenStore tokens, BundleContext context, IEclipsePreferences prefs) {
    this.tokens = tokens;
    this.context = context;
    this.prefs = prefs;
  }

  /** Follows the switch and the port from now on, and applies them now. */
  public void follow() {
    synchronized (switching) {
      if (!following) {
        prefs.addPreferenceChangeListener(preferenceListener);
        following = true;
      }
    }
    apply();
  }

  public void unfollow() {
    synchronized (switching) {
      if (following) {
        prefs.removePreferenceChangeListener(preferenceListener);
        following = false;
      }
    }
  }

  /** Starts, stops or restarts the server as the preferences say. */
  public void apply() {
    boolean changed = false;
    synchronized (switching) {
      if (applying) {
        again = true; // the same thread, nested: the running apply() takes the preferences again when it is done
        return;
      }
      applying = true;
      try {
        do {
          again = false;
          changed |= applyLocked();
        } while (again);
      } finally {
        applying = false;
      }
    }
    if (changed) {
      fire();
    }
  }

  /** Stops as if the switch went off, whatever the preferences say (the instance is replaced). */
  void shutdown() {
    boolean changed;
    synchronized (switching) {
      changed = off();
    }
    if (changed) {
      fire();
    }
  }

  private boolean applyLocked() {
    boolean on = prefs.getBoolean(McpPreferences.ENABLED, false);
    int port = prefs.getInt(McpPreferences.PORT, 0);
    if (!on) {
      return off();
    }
    if (live != null && port == configuredPort) {
      return false;
    }
    if (live != null) {
      stopServer(); // another port: the same token on the new one
    }
    start(port);
    return true;
  }

  private boolean off() {
    boolean changed = failure != null;
    failure = null;
    if (live != null || token != null) {
      stop();
      changed = true;
    }
    return changed;
  }

  private void start(int port) {
    failure = null;
    try {
      String t = token;
      if (t == null) {
        t = tokens.get();
      }
      if (t == null) {
        t = tokens.create();
      }
      token = t; // kept in memory from now on: requests and restarts never ask secure storage again
      openModels = new ServiceTracker<>(context, OpenModels.class, null);
      openModels.open();
      ProposalStore newStore = new ProposalStore();
      sweep(newStore);
      ServiceTracker<OpenModels, OpenModels> models = openModels;
      ConflictResolverService service = new ConflictResolverService(newStore, models::getService,
          Clock.systemUTC());
      McpHttpServer server = new McpHttpServer(new McpProtocol("AfterGit", version(), INSTRUCTIONS,
          ToolSet.create(service), new McpLog()), () -> token, port);
      server.start();
      store = newStore;
      configuredPort = port;
      registration = context.registerService(ProposalSource.class, new StoreProposalSource(newStore), null);
      Live now = new Live(server, server.port());
      live = now;
      ILog.of(AgentServer.class).log(Status.info("The AfterGit MCP server listens on " + now.url()));
    } catch (IOException | RuntimeException e) {
      failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      ILog.of(AgentServer.class).log(Status.warning("The AfterGit MCP server could not start on port " + port + ": "
          + failure));
      if (openModels != null) {
        openModels.close();
        openModels = null;
      }
    }
  }

  private void stop() {
    boolean wasRunning = live != null;
    stopServer();
    tokens.revoke();
    token = null;
    configuredPort = -1;
    if (wasRunning) {
      ILog.of(AgentServer.class).log(Status.info("The AfterGit MCP server stopped; its token is revoked"));
    }
  }

  private void stopServer() {
    Live was = live;
    live = null; // the getters see the server gone at once
    McpHttpServer server = was == null ? null : was.server();
    if (registration != null) {
      try {
        registration.unregister();
      } catch (IllegalStateException e) {
        // already gone with the bundle
      }
      registration = null;
    }
    if (server != null) {
      server.stop(); // does not wait for running calls
    }
    if (store != null) {
      store.close(); // forgets the memory; a call still running touches no file any more
      store = null;
    }
    if (openModels != null) {
      openModels.close();
      openModels = null;
    }
  }

  public boolean running() {
    return live != null;
  }

  public int port() {
    Live l = live;
    return l == null ? -1 : l.port();
  }

  public String url() {
    Live l = live;
    return l == null ? null : l.url();
  }

  /** The token while the server runs, else null. */
  public String token() {
    return live == null ? null : token;
  }

  /** Why the server did not start, or null. */
  public String failure() {
    return failure;
  }

  /**
   * A new token, valid at once; the old one is refused from now on. Null while the server does not run (also when it
   * was switched off while the token was made: then no token is kept). Throws when secure storage fails; the old
   * token then stays valid.
   */
  public String regenerateToken() {
    String fresh;
    synchronized (switching) {
      Live l = live;
      if (l == null || applying) {
        return null; // off, or nested in a switch that is still running on this thread
      }
      applying = true; // a switch nested in create() (password dialog) waits until the new token is in place
      try {
        again = false;
        fresh = tokens.create();
        token = fresh;
        l.server().endSessions();
      } finally {
        while (again) { // also when create() failed: a switch deferred meanwhile must still run
          again = false;
          applyLocked(); // e.g. switched off meanwhile: stops and revokes the token just made
        }
        applying = false;
      }
      if (live == null) {
        fresh = null;
      }
    }
    fire();
    return fresh;
  }

  /**
   * Deletes the stored proposals of conflicts that were resolved while the server was off (agents design §1: they stay
   * only until their conflict is resolved). A failure costs only disk space and never stops the start.
   */
  private static void sweep(ProposalStore store) {
    try {
      int n = store.sweepResolved(openRepositories());
      if (n > 0) {
        ILog.of(AgentServer.class).log(Status.info("Deleted the stored proposals of " + n
            + " resolved file(s)"));
      }
    } catch (IOException | RuntimeException e) {
      ILog.of(AgentServer.class).log(Status.warning("The stored proposals of resolved files could not be deleted: "
          + e.getMessage()));
    }
  }

  /** The repositories of the open projects of the workspace. */
  private static Set<Repository> openRepositories() {
    Set<Repository> repositories = new LinkedHashSet<>();
    for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
      if (p.isOpen()) {
        RepositoryLocator.locate(p).ifPresent(rf -> repositories.add(rf.repository()));
      }
    }
    return repositories;
  }

  /** "Delete stored proposals": the proposal files of every repository with an open project; returns how many. */
  public int deleteStoredProposals() throws IOException {
    Set<Repository> repositories = openRepositories();
    synchronized (switching) {
      if (store != null) {
        store.dropMemory();
      }
      return ProposalStore.deleteAll(repositories);
    }
  }

  /**
   * {@code listener} runs after the server started, stopped, failed to start or got a new token, on the thread that
   * made the change and with no lock of the server held: it may call any method of the server.
   */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void fire() {
    for (Runnable l : listeners) {
      l.run();
    }
  }

  private static String version() {
    Bundle b = FrameworkUtil.getBundle(AgentServer.class);
    return b == null ? "0" : b.getVersion().toString();
  }
}
