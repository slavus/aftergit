package net.aftergit.merge.lsp.diagnostics;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.lsp4e.client.DefaultLanguageClient;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethodProvider;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The client side of AfterGit's own server instance: it takes the diagnostics of the documents being checked and
 * says no to everything that would reach the user or the workspace (messages, edits, documents to show). A check
 * runs in the background and nobody watches it, so nothing of it may pop up. The settings the server asks for
 * (workspace/configuration) and the requests of its own (ESLint's "eslint/confirmESLintExecution", for one) go to the
 * client of the server's lsp4e definition, the one lsp4e gives the user's instance, so that both instances run with
 * the same settings; when that client fails or takes longer than {@value #DELEGATE_MILLIS} ms, the answer is the
 * server's defaults. Requests that would register something lasting (file watchers, capabilities) are answered with
 * an empty success and never reach that client.
 */
@SuppressWarnings("restriction") // DefaultLanguageClient, of lsp4e's definitions
public final class SideClient implements LanguageClient {
  /** How long the definition's client may take for an answer. */
  static final long DELEGATE_MILLIS = 2000;
  private final WorkspaceFolder folder;
  private static final Pattern DRIVE = Pattern.compile("/[A-Za-z]:");
  private final Map<Object, Answer> waiting = new ConcurrentHashMap<>();
  /** The client of the server's lsp4e definition, or null. */
  private final LanguageClient delegate;
  private final java.util.concurrent.atomic.AtomicBoolean disposed = new java.util.concurrent.atomic.AtomicBoolean();

  public SideClient(String folderUri, String folderName) {
    this(folderUri, folderName, null);
  }

  /** {@code delegate}: the client of the server's definition, for its settings and its own requests, or null. */
  public SideClient(String folderUri, String folderName, LanguageClient delegate) {
    this.folder = new WorkspaceFolder(folderUri, folderName);
    this.delegate = delegate;
  }

  /**
   * The local services of the launcher: this client, then one that hands the definition's client every request
   * and notification of its own (lsp4j asks a local service that is an Endpoint for what no other one has).
   */
  List<Object> localServices() {
    List<Object> out = new ArrayList<>();
    out.add(this);
    Endpoint extras = delegate == null ? null : extras(delegate);
    if (extras != null) {
      out.add(extras);
    }
    return out;
  }

  /** The requests and notifications of {@code client}'s class that LanguageClient does not have, or null. */
  private static Endpoint extras(LanguageClient client) {
    try {
      Map<String, JsonRpcMethod> own = new LinkedHashMap<>(ServiceEndpoints.getSupportedMethods(client.getClass()));
      own.keySet().removeAll(ServiceEndpoints.getSupportedMethods(SideClient.class).keySet());
      if (own.isEmpty()) {
        return null;
      }
      return new Extras(new GenericEndpoint(client), Map.copyOf(own));
    } catch (RuntimeException | LinkageError e) {
      return null; // a client lsp4j cannot read: the server's own requests go unanswered
    }
  }

  /**
   * True for a request of the definition's client that registers something that outlives the request, a watcher
   * of files or a capability (Wild Web Developer's "markdown/fs/watcher/create", for one): the client has no server
   * of its own here and nothing would ever remove it, so such a request is answered with an empty success instead.
   */
  public static boolean registers(String method) {
    String m = method.toLowerCase(java.util.Locale.ROOT);
    return m.contains("watch") || m.contains("register") || m.contains("subscribe") || m.contains("listen");
  }

  /** Hands the definition's client the requests of its own; never one of LanguageClient's, which SideClient has. */
  private static final class Extras implements Endpoint, JsonRpcMethodProvider {
    private final Endpoint client;
    private final Map<String, JsonRpcMethod> methods;

    Extras(Endpoint client, Map<String, JsonRpcMethod> methods) {
      this.client = client;
      this.methods = methods;
    }

    @Override
    public Map<String, JsonRpcMethod> supportedMethods() {
      return methods;
    }

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
      if (!methods.containsKey(method)) {
        return CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(
            ResponseErrorCode.MethodNotFound, "Unsupported request method: " + method, null)));
      }
      if (registers(method)) {
        return CompletableFuture.completedFuture(null); // nothing is registered for a check
      }
      try {
        return client.request(method, parameter);
      } catch (RuntimeException | LinkageError e) {
        return CompletableFuture.failedFuture(e);
      }
    }

    @Override
    public void notify(String method, Object parameter) {
      if (methods.containsKey(method) && !registers(method)) {
        try {
          client.notify(method, parameter);
        } catch (RuntimeException | LinkageError e) {
          // the definition's client failed; the check goes on
        }
      }
    }
  }

  /** Ends the definition's client, as lsp4e does when its instance stops. */
  void dispose() {
    if (delegate instanceof DefaultLanguageClient d && disposed.compareAndSet(false, true)) {
      try {
        d.dispose();
      } catch (RuntimeException | LinkageError e) {
        // nothing of it is shown
      }
    }
  }

  /** Starts collecting the diagnostics the server publishes for {@code uri} at {@code version}. */
  Answer expect(String uri, int version) {
    Answer a = new Answer(version);
    waiting.put(key(uri), a);
    return a;
  }

  void forget(String uri) {
    waiting.remove(key(uri));
  }

  /** Ends every wait at once with no answer: the instance stops. */
  void abandonAll() {
    waiting.values().forEach(Answer::abandon);
  }

  /**
   * {@code uri} in one spelling, as a normalised {@link Path} (on Windows its equality ignores case): servers write
   * "file:///a" or "file:/a", encode characters their own way, and some (vscode-uri) send "file:///c%3A/p" back for
   * "file:///C:/p", so a drive letter is lower-cased on every platform. A URI that is no file URI stays as it is.
   */
  public static Object key(String uri) {
    try {
      URI u = URI.create(uri);
      String path = u.getPath();
      if (!"file".equalsIgnoreCase(u.getScheme()) || path == null) {
        return uri;
      }
      if (DRIVE.matcher(path).lookingAt()) {
        path = "/" + Character.toLowerCase(path.charAt(1)) + path.substring(2);
      }
      return Path.of(new URI("file", null, path, null)).normalize();
    } catch (URISyntaxException | RuntimeException e) {
      return uri;
    }
  }

  @Override
  public void publishDiagnostics(PublishDiagnosticsParams params) {
    Answer a = params.getUri() == null ? null : waiting.get(key(params.getUri()));
    if (a != null) {
      a.accept(params);
    }
  }

  @Override
  public void telemetryEvent(Object object) {
    // nobody watches a check
  }

  @Override
  public void showMessage(MessageParams params) {
    // never a pop-up for a document the user does not see
  }

  @Override
  public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void logMessage(MessageParams params) {
    // the server's own log stays with the server
  }

  @Override
  public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
    return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false)); // a check never edits the workspace
  }

  @Override
  public CompletableFuture<Void> registerCapability(RegistrationParams params) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
    return CompletableFuture.completedFuture(new ShowDocumentResult(false));
  }

  @Override
  public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
    return CompletableFuture.completedFuture(List.of(folder));
  }

  @Override
  public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
    // the settings of the definition's client, as the user's instance gets them; without them every section is
    // unset, so the server uses its defaults
    int n = params.getItems() == null ? 0 : params.getItems().size();
    List<Object> defaults = new ArrayList<>(Collections.nCopies(n, (Object) null));
    if (delegate == null) {
      return CompletableFuture.completedFuture(defaults);
    }
    try {
      CompletableFuture<List<Object>> answer = delegate.configuration(params);
      if (answer == null) {
        return CompletableFuture.completedFuture(defaults);
      }
      return answer.completeOnTimeout(defaults, DELEGATE_MILLIS, TimeUnit.MILLISECONDS)
          .handle((list, failure) -> failure != null || list == null ? defaults : list);
    } catch (RuntimeException | LinkageError e) {
      return CompletableFuture.completedFuture(defaults);
    }
  }

  @Override
  public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void notifyProgress(ProgressParams params) {
    // nobody watches a check
  }

  /** The diagnostics published for one opening of a document. */
  static final class Answer {
    private final int version;
    private List<Diagnostic> latest;
    private long at;
    private boolean abandoned;

    Answer(int version) {
      this.version = version;
    }

    synchronized void abandon() {
      abandoned = true;
      notifyAll();
    }

    synchronized void accept(PublishDiagnosticsParams p) {
      if (p.getVersion() != null && p.getVersion() != version) {
        return; // an earlier opening of the same URI
      }
      latest = p.getDiagnostics() == null ? List.of() : new ArrayList<>(p.getDiagnostics());
      at = System.nanoTime();
      notifyAll();
    }

    /**
     * The last diagnostics once none followed for {@code quietMillis} (a server may publish twice: syntax first,
     * then the rest), or what arrived within {@code timeoutMillis}: null when nothing did or {@code monitor} was
     * cancelled or the instance stopped.
     */
    synchronized List<Diagnostic> await(long timeoutMillis, long quietMillis, IProgressMonitor monitor)
        throws InterruptedException {
      long end = System.nanoTime() + timeoutMillis * 1_000_000;
      long quiet = quietMillis * 1_000_000;
      while (true) {
        if (abandoned || monitor != null && monitor.isCanceled()) {
          return null;
        }
        long now = System.nanoTime();
        if (latest != null && now - at >= quiet) {
          return latest;
        }
        if (now - end >= 0) {
          return latest;
        }
        long next = latest != null ? Math.min(at + quiet, end) : end;
        wait(Math.max(1, Math.min(100, (next - now) / 1_000_000))); // short, so a cancel is seen soon
      }
    }
  }
}
