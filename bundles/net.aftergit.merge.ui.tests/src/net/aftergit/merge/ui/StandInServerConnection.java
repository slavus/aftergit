package net.aftergit.merge.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.concurrent.Future;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;

/**
 * How lsp4e (and AfterGit's side checks) reach the stand-in: the JSON-RPC of a stdio server over two in-memory pipes,
 * with lsp4j's launcher on the server's end. A new instance per connection.
 */
public class StandInServerConnection implements StreamConnectionProvider {
  private InputStream fromServer;
  private OutputStream toServer;
  private Future<Void> listening;
  private StandInLanguageServer server;

  @Override
  public void start() throws IOException {
    Pipe up = Pipe.open();
    Pipe down = Pipe.open();
    toServer = Channels.newOutputStream(up.sink());
    fromServer = Channels.newInputStream(down.source());
    server = new StandInLanguageServer(muteVirtual());
    Launcher<StandInClientApi> l = new LSPLauncher.Builder<StandInClientApi>().setLocalService(server)
        .setRemoteInterface(StandInClientApi.class).setInput(Channels.newInputStream(up.source()))
        .setOutput(Channels.newOutputStream(down.sink())).create();
    server.connect(l.getRemoteProxy());
    listening = l.startListening();
  }

  /** True for a server that never answers AfterGit's virtual documents. */
  boolean muteVirtual() {
    return false;
  }

  @Override
  public InputStream getInputStream() {
    return fromServer;
  }

  @Override
  public OutputStream getOutputStream() {
    return toServer;
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public void stop() {
    if (server != null) {
      server.exit(); // stopped, and its timer thread ends
    }
    if (listening != null) {
      listening.cancel(true);
    }
    try {
      if (toServer != null) {
        toServer.close();
      }
      if (fromServer != null) {
        fromServer.close();
      }
    } catch (IOException e) {
      // closed already
    }
  }
}
