package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4e.client.DefaultLanguageClient;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

/**
 * The client of the stand-in's definition (fragment.xml), as Wild Web Developer's ESLintClientImpl is ESLint's: it
 * answers the settings section "agdiag" with "validate: on" and the server's own request
 * "standin/confirmExecution" with 1 (see {@link StandInLanguageServer#needsClientSettings}).
 */
public class StandInLanguageClient extends DefaultLanguageClient {
  @Override
  public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
    List<Object> out = new ArrayList<>();
    for (ConfigurationItem item : params.getItems()) {
      out.add("agdiag".equals(item.getSection()) ? Map.of("validate", "on") : null);
    }
    return CompletableFuture.completedFuture(out);
  }

  /** Watchers made by any client of the stand-in's definition. */
  static final java.util.concurrent.atomic.AtomicInteger WATCHERS = new java.util.concurrent.atomic.AtomicInteger();

  @JsonRequest("standin/watcher/create")
  public CompletableFuture<Void> createWatcher(Object params) {
    WATCHERS.incrementAndGet(); // a real client would add a workspace listener here
    return CompletableFuture.completedFuture(null);
  }

  @JsonRequest("standin/confirmExecution")
  public CompletableFuture<Integer> confirmExecution(Object params) {
    return CompletableFuture.completedFuture(1);
  }
}
