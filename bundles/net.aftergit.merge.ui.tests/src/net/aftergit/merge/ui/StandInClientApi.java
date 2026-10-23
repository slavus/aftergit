package net.aftergit.merge.ui;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;

/** What the stand-in server may ask its client: LanguageClient and a request of its own, as ESLint's server has. */
public interface StandInClientApi extends LanguageClient {
  @JsonRequest("standin/confirmExecution")
  CompletableFuture<Integer> confirmExecution(Object params);

  /** A request that registers something lasting in the client, as Wild Web Developer's markdown/fs/watcher/create. */
  @JsonRequest("standin/watcher/create")
  CompletableFuture<Void> createWatcher(Object params);
}
