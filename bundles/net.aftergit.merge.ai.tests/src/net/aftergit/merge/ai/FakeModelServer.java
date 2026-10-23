package net.aftergit.merge.ai;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A model server on 127.0.0.1 that records every request and answers what a test says. */
final class FakeModelServer implements AutoCloseable {
  record Request(String method, String path, Headers headers, String body) {
    String header(String name) {
      return headers.getFirst(name);
    }
  }

  final List<Request> requests = new CopyOnWriteArrayList<>();
  volatile int status = 200;
  volatile String body = "{}";
  volatile long delayMs;
  volatile Map<String, String> responseHeaders = Map.of();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  FakeModelServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(executor);
    server.createContext("/", ex -> {
      try {
        String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(),
            new Headers(ex.getRequestHeaders()), in));
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        responseHeaders.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
          ex.getResponseBody().write(out);
        }
      } catch (IOException | InterruptedException e) {
        // the client went away or the server stops
      } finally {
        ex.close();
      }
    });
    server.start();
  }

  URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
