package net.aftergit.merge.mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The MCP endpoint on 127.0.0.1 (agents design §2, §4): POST {@code /mcp} with one JSON-RPC message, plain JSON
 * replies, {@code Mcp-Session-Id}, a bearer token, an Origin check and a 1 MiB body limit.
 *
 * <p>Requests are read on a small pool of daemon threads, so a connection that sends nothing or stops halfway holds
 * one thread, not the server. The JDK server has no per-server read deadline (only the JVM-wide
 * {@code sun.net.httpserver.maxReqTime}, which AfterGit leaves alone inside Eclipse): such a connection keeps its
 * thread until the client goes away or the server stops, so {@value #HTTP_THREADS} of them at once are needed to
 * hold up other clients, and only local processes can connect. Tool calls (initialize and every dispatched request)
 * still run one at a time.
 */
public final class McpHttpServer {
  public static final String PATH = "/mcp";
  public static final int MAX_BODY = 1024 * 1024;
  static final int MAX_SESSIONS = 32;
  /** An announced body over the limit is read away up to this much, so the client gets its 413. */
  static final long MAX_DRAIN = 16L * 1024 * 1024;
  /** Threads that read requests and write replies. */
  static final int HTTP_THREADS = 4;

  private final McpProtocol protocol;
  private final Supplier<String> token;
  private final int requestedPort;
  private final Sessions sessions = new Sessions(MAX_SESSIONS);
  /** Held while the protocol runs, so tool calls run one at a time; {@link #stop()} never takes it. */
  private final Object calls = new Object();
  private HttpServer server;
  private ExecutorService executor;
  private volatile int port = -1;

  /** {@code token}: asked on every request (a regenerated token takes effect at once); {@code port}: 0 = any. */
  public McpHttpServer(McpProtocol protocol, Supplier<String> token, int port) {
    this.protocol = protocol;
    this.token = token;
    this.requestedPort = port;
  }

  public synchronized void start() throws IOException {
    if (server != null) {
      return;
    }
    InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    HttpServer s = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 0);
    AtomicInteger count = new AtomicInteger();
    ExecutorService e = Executors.newFixedThreadPool(HTTP_THREADS, r -> {
      Thread t = new Thread(r, "AfterGit MCP server " + count.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    s.setExecutor(e);
    s.createContext("/", this::handle);
    s.start();
    server = s;
    executor = e;
    port = s.getAddress().getPort();
  }

  /** Stops at once: no new connections, running calls are interrupted, sessions end. */
  public synchronized void stop() {
    if (server == null) {
      return;
    }
    server.stop(0);
    executor.shutdownNow();
    server = null;
    executor = null;
    port = -1;
    sessions.clear();
  }

  public int port() {
    return port;
  }

  public synchronized InetSocketAddress address() {
    return server == null ? null : server.getAddress();
  }

  /** Ends every session (after a new token): clients initialize again. */
  public void endSessions() {
    sessions.clear();
  }

  public int sessionCount() {
    return sessions.size();
  }

  private void handle(HttpExchange ex) throws IOException {
    try {
      Headers h = ex.getRequestHeaders();
      if (!PATH.equals(ex.getRequestURI().getPath())) {
        empty(ex, 404);
      } else if (!originAllowed(h.getFirst("Origin"), port)) {
        empty(ex, 403);
      } else if (!authorized(h.getFirst("Authorization"), token.get())) {
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        empty(ex, 401);
      } else if ("POST".equals(ex.getRequestMethod())) {
        post(ex);
      } else if ("DELETE".equals(ex.getRequestMethod())) {
        empty(ex, sessions.remove(h.getFirst("Mcp-Session-Id")) ? 200 : 404);
      } else {
        ex.getResponseHeaders().set("Allow", "POST, DELETE");
        empty(ex, 405);
      }
    } catch (RuntimeException e) {
      try {
        json(ex, 500, McpProtocol.error(null, McpProtocol.INTERNAL_ERROR, "Internal error"));
      } catch (IOException | RuntimeException late) {
        // the reply had begun: nothing more to send
      }
    } finally {
      ex.close();
    }
  }

  private void post(HttpExchange ex) throws IOException {
    Headers h = ex.getRequestHeaders();
    if (announcedTooLong(h.getFirst("Content-Length"))) {
      drain(ex.getRequestBody());
      empty(ex, 413);
      return;
    }
    byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      drain(ex.getRequestBody());
      empty(ex, 413);
      return;
    }
    JsonElement parsed = parse(new String(body, StandardCharsets.UTF_8));
    if (parsed == null) {
      json(ex, 400, McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Parse error"));
      return;
    }
    if (!parsed.isJsonObject()) {
      json(ex, 400, McpProtocol.error(null, McpProtocol.INVALID_REQUEST,
          "Send one JSON-RPC message per request; batches are not supported"));
      return;
    }
    JsonObject message = parsed.getAsJsonObject();
    boolean request = message.has("method"); // else a response of the client
    JsonElement id = message.get("id");
    if (request) {
      String problem = McpProtocol.invalid(message);
      if (problem != null) {
        json(ex, 400, McpProtocol.error(id, McpProtocol.INVALID_REQUEST, problem));
        return;
      }
    }
    JsonElement replyId = request ? id : null;
    if (request && id != null && "initialize".equals(message.get("method").getAsString())) {
      McpProtocol.Initialized init;
      synchronized (calls) {
        init = protocol.initialize(message);
      }
      if (init.ok()) {
        ex.getResponseHeaders().set("Mcp-Session-Id", sessions.create(init.client(), init.protocolVersion()).id());
      }
      json(ex, 200, init.reply());
      return;
    }
    String sessionId = h.getFirst("Mcp-Session-Id");
    if (sessionId == null) {
      json(ex, 400, McpProtocol.error(replyId, McpProtocol.INVALID_REQUEST,
          "Missing Mcp-Session-Id header; send initialize first"));
      return;
    }
    Sessions.Session session = sessions.get(sessionId);
    if (session == null) {
      json(ex, 404, McpProtocol.error(replyId, McpProtocol.INVALID_REQUEST,
          "Unknown or ended session; initialize again"));
      return;
    }
    String version = h.getFirst("MCP-Protocol-Version");
    if (version != null && !McpProtocol.VERSIONS.contains(version)) {
      json(ex, 400, McpProtocol.error(replyId, McpProtocol.INVALID_REQUEST,
          "Unsupported MCP-Protocol-Version " + McpProtocol.loggable(version, 40)));
      return;
    }
    if (!request || id == null) {
      empty(ex, 202); // a response of the client or a notification (e.g. notifications/initialized): nothing to run
      return;
    }
    JsonObject reply;
    synchronized (calls) {
      reply = protocol.dispatch(message, session.client());
    }
    json(ex, 200, reply);
  }

  /** One strict JSON value and nothing after it, or null when {@code text} is not that (an empty body included). */
  static JsonElement parse(String text) {
    try {
      JsonReader reader = new JsonReader(new StringReader(text));
      reader.setStrictness(Strictness.STRICT);
      if (reader.peek() == JsonToken.END_DOCUMENT) {
        return null;
      }
      JsonElement e = JsonParser.parseReader(reader);
      return reader.peek() == JsonToken.END_DOCUMENT ? e : null;
    } catch (JsonParseException | IOException | IllegalStateException | StackOverflowError e) {
      // StackOverflowError: deep nesting that the nesting limit of Gson did not stop
      return null;
    }
  }

  static boolean originAllowed(String origin, int port) {
    return origin == null || origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port);
  }

  static boolean authorized(String header, String token) {
    // the scheme is case-insensitive (RFC 7235); an empty token never matches
    if (token == null || token.isEmpty() || header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return false;
    }
    return MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8));
  }

  static boolean announcedTooLong(String contentLength) {
    try {
      return contentLength != null && Long.parseLong(contentLength.trim()) > MAX_BODY;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static void drain(InputStream in) throws IOException {
    long left = MAX_DRAIN;
    byte[] buffer = new byte[64 * 1024];
    int n;
    while (left > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0) {
      left -= n;
    }
  }

  private static void json(HttpExchange ex, int status, JsonObject body) throws IOException {
    byte[] bytes = McpProtocol.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void empty(HttpExchange ex, int status) throws IOException {
    ex.sendResponseHeaders(status, -1);
  }
}
