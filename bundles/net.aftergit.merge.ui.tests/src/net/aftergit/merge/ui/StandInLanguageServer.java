package net.aftergit.merge.ui;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * The deterministic language server of the tests (languages design, Testing), for "*.agdiag" files (fragment.xml).
 * Line by line: "def NAME" declares NAME; each "use NAME" without a declaration in the document is the error
 * "Unknown name NAME"; "import PATH" is the error "Cannot find PATH" unless that file exists on disk next to the
 * document; "warn" is the warning "A warning"; "keyed" is the error "Not in the schema of NAME" for a document of
 * the file NAME itself, never for AfterGit's virtual documents (a server whose schema goes by the file name);
 * a line with both "ambient" and "declare" is the error "Ambient only" for AfterGit's virtual documents only (the
 * other way round). Each
 * version is answered once, after {@link #delayMillis}. Before a save it returns {@link #saveEdit} as an edit
 * (willSaveWaitUntil).
 */
public final class StandInLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService,
    LanguageClientAware {
  static final String ID = "net.aftergit.merge.ui.tests.standin.server";
  private static final Pattern DEF = Pattern.compile("^\\s*def (\\w+)");
  private static final Pattern USE = Pattern.compile("\\buse (\\w+)");
  private static final Pattern IMPORT = Pattern.compile("^\\s*import (\\S+)");

  /** Every instance started, in order: lsp4e's for the editors and the result, AfterGit's own for the sides. */
  static final List<StandInLanguageServer> INSTANCES = new CopyOnWriteArrayList<>();
  /**
   * Every instance started since Eclipse started, never cleared: lsp4e keeps its instance for the editors and the
   * result running from one test to the next, so a test may see it only here.
   */
  static final List<StandInLanguageServer> EVER = new CopyOnWriteArrayList<>();
  /** Milliseconds before a version is answered. */
  static volatile long delayMillis;
  /** When set, only the documents whose URI ends so wait {@link #delayMillis}; the others are answered at once. */
  static volatile String slowSuffix;
  /** When not negative: the delay of AfterGit's virtual documents (".aftergit-"), instead of {@link #delayMillis}. */
  static volatile long sideDelayMillis = -1;
  /** When true, a document whose file is not on disk is never answered (a server that rejects virtual documents). */
  static volatile boolean rejectMissingFiles;
  /** When true, {@code initialize} is never answered (a server that does not start). */
  static volatile boolean silentInitialize;
  /** Milliseconds before {@code initialize} is answered (a server that is slow to start). */
  static volatile long initializeDelayMillis;
  /**
   * When true, a document is answered only once the client has answered "validate: on" for the settings section
   * "agdiag" (workspace/configuration) and 1 for the request "standin/confirmExecution", as ESLint's server asks
   * its client, and it asks the client to create a watcher ("standin/watcher/create"), which any success answers;
 * the client of the definition ({@link StandInLanguageClient}) answers so.
   */
  static volatile boolean needsClientSettings;
  /** The answers to those two requests that let a document be answered. */
  static final AtomicInteger SETTINGS_ANSWERED = new AtomicInteger();
  /** What the client answered when it did not let a document be answered, for the messages of the tests. */
  static final List<String> SETTINGS_REFUSED = new CopyOnWriteArrayList<>();
  /** When true, {@code shutdown} is never answered (a server that is slow to stop). */
  static volatile boolean silentShutdown;
  /** Text the server inserts at the start of a document before it is saved (willSaveWaitUntil), or null for none. */
  static volatile String saveEdit;
  /** willSaveWaitUntil requests of all instances. */
  static final AtomicInteger SAVE_REQUESTS = new AtomicInteger();
  /** didSave notifications of all instances. */
  static final AtomicInteger SAVES = new AtomicInteger();

  /** True for {@link StandInMuteServerConnection}'s instances. */
  boolean muteVirtual() {
    return muteVirtual;
  }

  /** Each URI opened here, and whether its file was on disk when it was opened. */
  final Map<String, Boolean> opened = new ConcurrentHashMap<>();
  /** The text of each URI as it was last opened here (didOpen). */
  final Map<String, String> openedText = new ConcurrentHashMap<>();
  private final Map<String, Integer> versions = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "stand-in language server");
    t.setDaemon(true);
    return t;
  });
  private volatile LanguageClient client;
  /** True for the server that never answers AfterGit's virtual documents ({@link StandInMuteServerConnection}). */
  private final boolean muteVirtual;
  /** The client's name in {@code initialize} ("AfterGit" for AfterGit's own instance), null before it. */
  volatile String clientName;
  volatile boolean stopped;

  StandInLanguageServer() {
    this(false);
  }

  StandInLanguageServer(boolean muteVirtual) {
    this.muteVirtual = muteVirtual;
    INSTANCES.add(this);
    EVER.add(this);
  }

  /** Back to the defaults; the instances of earlier tests are forgotten. */
  static void reset() {
    delayMillis = 0;
    sideDelayMillis = -1;
    slowSuffix = null;
    rejectMissingFiles = false;
    silentInitialize = false;
    initializeDelayMillis = 0;
    needsClientSettings = false;
    SETTINGS_ANSWERED.set(0);
    SETTINGS_REFUSED.clear();
    StandInLanguageClient.WATCHERS.set(0);
    silentShutdown = false;
    saveEdit = null;
    SAVE_REQUESTS.set(0);
    SAVES.set(0);
    INSTANCES.clear();
    net.aftergit.merge.lsp.diagnostics.SideServers.forgetSilent(); // as a restart of Eclipse
  }

  /** The URIs any instance opened that name AfterGit's virtual documents. */
  static List<String> virtualUris() {
    List<String> out = new ArrayList<>();
    for (StandInLanguageServer s : INSTANCES) {
      s.opened.keySet().stream().filter(u -> u.contains(".aftergit-")).forEach(out::add);
    }
    return out;
  }

  @Override
  public void connect(LanguageClient c) {
    client = c;
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    clientName = params.getClientInfo() == null ? "" : params.getClientInfo().getName();
    if (silentInitialize) {
      return new CompletableFuture<>();
    }
    long delay = initializeDelayMillis;
    if (delay > 0) {
      return CompletableFuture.supplyAsync(this::initializeResult,
          CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));
    }
    return CompletableFuture.completedFuture(initializeResult());
  }

  private InitializeResult initializeResult() {
    ServerCapabilities caps = new ServerCapabilities();
    TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
    sync.setOpenClose(true);
    sync.setChange(TextDocumentSyncKind.Full);
    sync.setWillSaveWaitUntil(true); // a server with "save actions": lsp4e asks it before each save of a buffer
    sync.setSave(new SaveOptions(false)); // and tells it about each save (didSave)
    caps.setTextDocumentSync(sync);
    return new InitializeResult(caps);
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    stopped = true;
    if (silentShutdown) {
      return new CompletableFuture<>();
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    stopped = true;
    timer.shutdownNow();
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return this;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return this;
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    opened.put(uri, onDisk(uri));
    openedText.put(uri, params.getTextDocument().getText());
    answerLater(uri, params.getTextDocument().getVersion(), params.getTextDocument().getText());
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    var changes = params.getContentChanges();
    answerLater(params.getTextDocument().getUri(), params.getTextDocument().getVersion(),
        changes.get(changes.size() - 1).getText()); // full sync: the last change is the whole text
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    versions.remove(uri);
    LanguageClient c = client;
    if (c != null) {
      c.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    }
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    SAVES.incrementAndGet();
  }

  @Override
  public CompletableFuture<List<TextEdit>> willSaveWaitUntil(WillSaveTextDocumentParams params) {
    SAVE_REQUESTS.incrementAndGet();
    String edit = saveEdit;
    return CompletableFuture.completedFuture(edit == null ? List.of()
        : List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), edit)));
  }

  @Override
  public void didChangeConfiguration(DidChangeConfigurationParams params) {}

  @Override
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

  private void answerLater(String uri, int version, String text) {
    versions.put(uri, version);
    boolean virtual = uri.contains(".aftergit-");
    if (stopped || rejectMissingFiles && !onDisk(uri) || muteVirtual && virtual) {
      return;
    }
    try {
      timer.schedule(() -> {
        LanguageClient c = client;
        if (c != null && !stopped && Integer.valueOf(version).equals(versions.get(uri)) && settingsAllow(c)) {
          c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnose(uri, text), version));
        }
      }, delayOf(uri, virtual), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // stopped meanwhile: its timer is gone, and nothing is answered any more
    }
  }

  private static long delayOf(String uri, boolean virtual) {
    if (virtual && sideDelayMillis >= 0) {
      return sideDelayMillis;
    }
    String slow = slowSuffix;
    return slow == null || uri.endsWith(slow) ? delayMillis : 0;
  }

  /** Without {@link #needsClientSettings} true; else whether the client answered both requests as it should. */
  private boolean settingsAllow(LanguageClient c) {
    if (!needsClientSettings) {
      return true;
    }
    try {
      ConfigurationItem item = new ConfigurationItem();
      item.setSection("agdiag");
      List<Object> settings = c.configuration(new ConfigurationParams(List.of(item))).get(5, TimeUnit.SECONDS);
      boolean validate = settings != null && settings.size() == 1
          && String.valueOf(settings.get(0)).matches("(?s).*validate\\W+on\\b.*");
      Object confirmed = c instanceof StandInClientApi api
          ? api.confirmExecution(Map.of()).get(5, TimeUnit.SECONDS) : null;
      if (c instanceof StandInClientApi api) {
        api.createWatcher(Map.of("glob", "**/*.agdiag")).get(5, TimeUnit.SECONDS); // any success will do
      }
      boolean confirm = confirmed != null && String.valueOf(confirmed).matches("1(\\.0)?");
      if (validate && confirm) {
        SETTINGS_ANSWERED.incrementAndGet();
        return true;
      }
      SETTINGS_REFUSED.add("settings " + settings + ", confirmation " + confirmed);
      return false;
    } catch (Exception e) {
      SETTINGS_REFUSED.add(String.valueOf(e));
      return false; // no answer, MethodNotFound, or none in time: like ESLint, the document is not validated
    }
  }

  static List<Diagnostic> diagnose(String uri, String text) {
    String[] lines = text.split("\n", -1);
    Set<String> declared = new HashSet<>();
    for (String l : lines) {
      Matcher m = DEF.matcher(l);
      if (m.find()) {
        declared.add(m.group(1));
      }
    }
    List<Diagnostic> out = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String l = lines[i];
      Matcher use = USE.matcher(l);
      while (use.find()) {
        if (!declared.contains(use.group(1))) {
          out.add(diagnostic(i, use.start(1), use.end(1), "Unknown name " + use.group(1), DiagnosticSeverity.Error));
        }
      }
      Matcher imp = IMPORT.matcher(l);
      if (imp.find() && !existsNextTo(uri, imp.group(1))) {
        out.add(diagnostic(i, imp.start(1), imp.end(1), "Cannot find " + imp.group(1), DiagnosticSeverity.Error));
      }
      int k = l.indexOf("keyed");
      if (k >= 0 && !uri.contains(".aftergit-")) {
        out.add(diagnostic(i, k, k + 5, "Not in the schema of " + uri.substring(uri.lastIndexOf('/') + 1),
            DiagnosticSeverity.Error));
      }
      int amb = l.indexOf("ambient");
      if (amb >= 0 && l.contains("declare") && uri.contains(".aftergit-")) {
        out.add(diagnostic(i, amb, amb + 7, "Ambient only", DiagnosticSeverity.Error));
      }
      int w = l.indexOf("warn");
      if (w >= 0) {
        out.add(diagnostic(i, w, w + 4, "A warning", DiagnosticSeverity.Warning));
      }
    }
    return out;
  }

  private static Diagnostic diagnostic(int line, int start, int end, String message, DiagnosticSeverity severity) {
    return new Diagnostic(new Range(new Position(line, start), new Position(line, end)), message, severity,
        "stand-in");
  }

  private static Path path(String uri) {
    try {
      return Path.of(URI.create(uri));
    } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
      return null; // not a file URI: the document is not on disk
    }
  }

  private static boolean existsNextTo(String uri, String name) {
    Path p = path(uri);
    return p != null && Files.exists(p.resolveSibling(name).normalize());
  }

  /** The document exists on disk; a URI that is no file is not. */
  private static boolean onDisk(String uri) {
    Path p = path(uri);
    return p != null && Files.exists(p);
  }
}
