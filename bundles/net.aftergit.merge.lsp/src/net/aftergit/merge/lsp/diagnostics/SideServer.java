package net.aftergit.merge.lsp.diagnostics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.aftergit.merge.lsp.Grammars;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * AfterGit's own instance of a language server of the file (languages design, Part 2, Ruling 2): started from the
 * server's lsp4e definition, so it is the same program with the same initialization options, but ours and theirs
 * reach it under their virtual URIs without the user's instance, its editors or the Problems view seeing them. One
 * document is checked at a time, each opening under a URI of its own. A server that leaves a check unanswered
 * rejects such documents: neither this instance nor a later one is asked again in this session (SideServers).
 */
@SuppressWarnings("restriction") // lsp4e's definitions and LSPEclipseUtils, exported to lsp4e's friends only
public final class SideServer {
  /** How long a stopped server process (and the processes it started) may take to end before it is killed. */
  private static final long END_MILLIS = 2000;

  private final LanguageServerDefinition definition;
  private final String rootUri;
  private final SideClient client;
  private final AtomicBoolean shutdownSent = new AtomicBoolean();
  private volatile StreamConnectionProvider provider;
  private volatile ProcessHandle process;
  private volatile LanguageServer server;
  private volatile Future<Void> listening;
  private volatile ExecutorService rpc;
  private volatile CompletableFuture<?> initializing;
  private final ReentrantLock checking = new ReentrantLock();
  /** Guarded by {@link #checking}. */
  private int openings;
  private volatile boolean answered;
  private volatile boolean rejecting;
  private volatile boolean stopped;

  private SideServer(LanguageServerDefinition definition, File root, String name) {
    this.definition = definition;
    this.rootUri = LSPEclipseUtils.toUri(root).toString();
    this.client = new SideClient(rootUri, name, definitionClient(definition));
  }

  /** The definitions whose client failed or is only lsp4e's own: not made again in this session. */
  private static final Set<String> NO_CLIENT = ConcurrentHashMap.newKeySet();

  /**
   * The client lsp4e makes for the user's instance of {@code definition} (its settings), or null: when it fails
   * (logged once per definition), or when lsp4e gives only its default client, which has no settings of the
   * server's own (that is also what lsp4e falls back to when the definition's client cannot be made, after logging
   * it). Either way it is not made again in this session, so a failing client is reported once, not at every start.
   */
  private static LanguageClient definitionClient(LanguageServerDefinition definition) {
    if (NO_CLIENT.contains(definition.id)) {
      return null;
    }
    try {
      LanguageClient c = definition.createLanguageClient();
      if (c == null || c.getClass().getName().startsWith("org.eclipse.lsp4e.")) {
        NO_CLIENT.add(definition.id);
        return null;
      }
      return c;
    } catch (RuntimeException | LinkageError e) {
      if (NO_CLIENT.add(definition.id)) {
        ILog.of(SideServer.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The client of the language "
            + "server " + definition.label + " could not be made; AfterGit's checks of ours and theirs use the "
            + "server's default settings", Grammars.loggable(e)));
      }
      return null; // the server then runs with its default settings
    }
  }

  /** Forgets the clients that failed, with the silent servers (a new session); for SideServers. */
  static void forgetClients() {
    NO_CLIENT.clear();
  }

  /**
   * An initialized instance of {@code definition} for {@code project}; throws when it does not start within
   * {@code startMillis} (the whole start: the connection, the process and {@code initialize}), and then it is
   * stopped. Blocks for as long, so never on the UI thread.
   */
  public static SideServer start(LanguageServerDefinition definition, IProject project, long startMillis)
      throws Exception {
    IPath location = project.getLocation();
    if (location == null) {
      throw new IOException("The project " + project.getName() + " is not on the local disk");
    }
    SideServer s = new SideServer(definition, location.toFile(), project.getName());
    CompletableFuture<Void> started = new CompletableFuture<>();
    Thread t = new Thread(() -> {
      try {
        s.launch(startMillis);
        started.complete(null);
      } catch (Throwable x) {
        started.completeExceptionally(x); // rethrown to the caller below
      }
    }, "AfterGit side check: start of " + definition.label);
    t.setDaemon(true);
    t.start();
    try {
      started.get(startMillis, TimeUnit.MILLISECONDS);
      return s;
    } catch (ExecutionException x) {
      s.stop();
      Throwable cause = x.getCause();
      if (cause instanceof Exception e) {
        throw e;
      }
      if (cause instanceof Error e) {
        throw e;
      }
      throw x;
    } catch (Throwable x) { // the deadline, an interrupt, or an Error of this thread
      s.stop();
      throw x;
    }
  }

  private void launch(long startMillis) throws Exception {
    StreamConnectionProvider p = definition.createConnectionProvider();
    provider = p;
    abortIfStopped();
    p.start();
    process = processOf(p);
    abortIfStopped();
    drain(p.getErrorStream());
    URI root = URI.create(rootUri);
    AtomicReference<LanguageServer> proxy = new AtomicReference<>();
    rpc = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "AfterGit side check: " + definition.label);
      t.setDaemon(true);
      return t;
    }); // its own threads, ended with the instance
    abortIfStopped();
    Launcher.Builder<LanguageServer> builder = definition.createLauncherBuilder();
    Class<? extends LanguageServer> serverInterface = definition.getServerInterface();
    // with more than one local service lsp4j takes no class loader from them: the proxy of the server's interface
    // is made in the loader of that interface, which sees lsp4j too
    Launcher<LanguageServer> launcher = builder.setLocalServices(client.localServices())
        .setClassLoader(serverInterface.getClassLoader())
        .setExecutorService(rpc)
        .setRemoteInterface(serverInterface)
        .setInput(p.getInputStream())
        .setOutput(p.getOutputStream())
        .wrapMessages(consumer -> message -> {
          consumer.consume(message);
          LanguageServer s = proxy.get();
          if (s != null) {
            try {
              p.handleMessage(message, s, root); // as lsp4e does: some providers answer the server here
            } catch (RuntimeException e) {
              // the provider's business; the check goes on
            }
          }
        })
        .create();
    LanguageServer s = launcher.getRemoteProxy();
    proxy.set(s);
    listening = launcher.startListening();
    server = s;
    abortIfStopped();
    CompletableFuture<?> init = s.initialize(initializeParams(root, p.getInitializationOptions(root)));
    initializing = init;
    abortIfStopped();
    init.get(startMillis, TimeUnit.MILLISECONDS);
    s.initialized(new InitializedParams());
  }

  /** {@link #stop} may run while the start goes on: what the start made after it is ended here. */
  private void abortIfStopped() {
    if (stopped) {
      release();
      throw new CancellationException("stopped while starting");
    }
  }

  private static ProcessHandle processOf(StreamConnectionProvider p) {
    try {
      return p instanceof IAdaptable a ? a.getAdapter(ProcessHandle.class) : null;
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  private InitializeParams initializeParams(URI root, Object options) {
    InitializeParams params = new InitializeParams();
    params.setProcessId((int) ProcessHandle.current().pid());
    params.setRootUri(root.toString());
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toString(), new File(root).getName())));
    params.setInitializationOptions(options);
    params.setClientInfo(new ClientInfo("AfterGit"));
    TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
    PublishDiagnosticsCapabilities diagnostics = new PublishDiagnosticsCapabilities(false);
    diagnostics.setVersionSupport(true);
    text.setPublishDiagnostics(diagnostics);
    WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
    workspace.setConfiguration(true);
    workspace.setWorkspaceFolders(true);
    params.setCapabilities(new ClientCapabilities(workspace, text, null));
    return params;
  }

  /** A server process writes its errors to a pipe that must be read, or it blocks once the pipe is full. */
  private static void drain(InputStream errors) {
    if (errors == null) {
      return;
    }
    Thread t = new Thread(() -> {
      try (InputStream in = errors) {
        in.transferTo(OutputStream.nullOutputStream());
      } catch (IOException e) {
        // the server stopped
      }
    }, "AfterGit side check: server errors");
    t.setDaemon(true);
    t.start();
  }

  /**
   * The diagnostics of {@code text} as a document next to the file: opened under a URI of its own derived from
   * {@code sibling} ({@link VirtualUris#opening}), answered, closed. Null when the server does not answer within
   * {@code checkMillis} (it rejects such documents from then on, logged once), when {@code monitor} is cancelled,
   * when the instance stops or its process has ended. One check at a time: a check that waits for its turn ends
   * as soon as {@code monitor} is cancelled. Blocks for up to {@code checkMillis} after its turn came, so never on
   * the UI thread.
   */
  public List<Diagnostic> check(URI sibling, String languageId, String text, long checkMillis,
      long quietMillis, IProgressMonitor monitor) {
    // one document at a time; a check that waits for its turn still sees its cancel and the end of the instance
    try {
      while (!checking.tryLock(100, TimeUnit.MILLISECONDS)) {
        if (stopped || monitor != null && monitor.isCanceled()) {
          return null;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    try {
      return checkNow(sibling, languageId, text, checkMillis, quietMillis, monitor);
    } finally {
      checking.unlock();
    }
  }

  private List<Diagnostic> checkNow(URI sibling, String languageId, String text, long checkMillis,
      long quietMillis, IProgressMonitor monitor) {
    if (rejecting || !alive() || monitor != null && monitor.isCanceled()) {
      return null;
    }
    URI uri = VirtualUris.opening(sibling, () -> ++openings);
    if (uri == null) {
      return null;
    }
    String u = uri.toString();
    int v = openings;
    SideClient.Answer answer = client.expect(u, v);
    if (stopped) { // stop() abandoned the answers before this one was there
      client.forget(u);
      return null;
    }
    TextDocumentService documents = server.getTextDocumentService();
    documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(u, languageId, v, text)));
    try {
      List<Diagnostic> found = answer.await(checkMillis, quietMillis, monitor);
      if (found != null) {
        answered = true;
      }
      if (found == null && (monitor == null || !monitor.isCanceled()) && alive()) {
        rejecting = true;
        SideServers.rememberSilent(definition, checkMillis); // not asked again in this session (logged once)
      }
      return found;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      client.forget(u);
      if (!stopped) { // a stopped server has nothing open any more
        documents.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(u)));
      }
    }
  }

  /**
   * True once a check was answered: a server may publish nothing until it has loaded the project, so the first
   * check of an instance may be given longer.
   */
  public boolean answeredOnce() {
    return answered;
  }

  /** True once a check went unanswered. */
  public boolean rejecting() {
    return rejecting;
  }

  /** Not stopped, and its process (when it has one) still runs. */
  public boolean alive() {
    ProcessHandle h = process;
    return !stopped && (h == null || h.isAlive());
  }

  /**
   * Ends the instance: a check that waits returns null at once (no rejection), the server is asked to shut down
   * (for a second), and its process and the processes it started are ended, killed when they do not end within two
   * seconds. Safe to call more than once and while the start goes on.
   */
  public void stop() {
    stopped = true;
    client.abandonAll();
    release();
  }

  private void release() {
    ProcessHandle h = process;
    List<ProcessHandle> started = new ArrayList<>();
    if (h != null) {
      started.add(h);
      h.descendants().forEach(started::add); // before shutdown and exit: then they are no longer its descendants
    }
    CompletableFuture<?> init = initializing;
    if (init != null) {
      init.cancel(true); // a start still waiting for initialize ends now
    }
    LanguageServer s = server;
    if (s != null && shutdownSent.compareAndSet(false, true)) {
      try {
        s.shutdown().get(1, TimeUnit.SECONDS);
        s.exit();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception | LinkageError e) {
        // stopping anyway
      }
    }
    StreamConnectionProvider p = provider;
    if (p != null) {
      try {
        p.stop();
      } catch (RuntimeException | LinkageError e) {
        // the process is ended below
      }
    }
    Future<Void> l = listening; // after the streams closed: the reader then ends at their end, not interrupted
    if (l != null) {
      l.cancel(true);
    }
    ExecutorService r = rpc;
    if (r != null) {
      r.shutdownNow();
    }
    client.dispose();
    end(started);
  }

  /** SIGTERM to each process, then the kill to those still running after {@value #END_MILLIS} ms. */
  private static void end(List<ProcessHandle> processes) {
    if (processes.isEmpty()) {
      return;
    }
    processes.forEach(ProcessHandle::destroy);
    long deadline = System.nanoTime() + END_MILLIS * 1_000_000;
    boolean interrupted = false;
    for (ProcessHandle x : processes) {
      long left = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
      if (!interrupted) {
        try {
          x.onExit().get(left, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException | TimeoutException e) {
          // killed below
        }
      }
      if (x.isAlive()) {
        x.destroyForcibly();
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public boolean stopped() {
    return stopped;
  }
}
