package net.aftergit.merge.mcp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Agents design §4: loopback only, token, Origin, size limits, sessions. */
class McpHttpServerTest {
  final AtomicReference<String> token = new AtomicReference<>("test-token");
  McpHttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = new McpHttpServer(new McpProtocol("AfterGit", "0.0.1", "", EchoTools.all(), CallLog.NONE), token::get, 0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  RawClient client() {
    return new RawClient(server.port(), token.get());
  }

  static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

  @Test
  void itListensOnLoopbackOnly() {
    assertTrue(server.address().getAddress().isLoopbackAddress());
    assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());
    assertTrue(server.port() > 0);
  }

  @Test
  void withoutTheTokenEveryRequestIsRefused() throws Exception {
    RawClient c = new RawClient(server.port(), null);
    RawClient.Reply r = c.post(PING);
    assertEquals(401, r.status());
    assertEquals("Bearer", r.headers().firstValue("WWW-Authenticate").orElse(null));
    assertEquals(401, new RawClient(server.port(), "wrong").post(PING).status());
  }

  @Test
  void aNewTokenTakesEffectAtOnce() throws Exception {
    RawClient old = client();
    old.initialize("test-agent");
    token.set("next-token");
    assertEquals(401, old.call(2, "ping", null).status());
  }

  @Test
  void aForeignOriginIsRefusedAndTheServersOwnIsAccepted() throws Exception {
    RawClient c = client();
    c.headers.put("Origin", "https://evil.example");
    assertEquals(403, c.post(PING).status());
    c.headers.put("Origin", "null");
    assertEquals(403, c.post(PING).status());
    int other = server.port() == 65535 ? 65534 : server.port() + 1;
    for (String nearMiss : List.of("http://127.0.0.1:" + other, "https://127.0.0.1:" + server.port(),
        "http://localhost:" + other, "http://127.0.0.1", "http://127.0.0.1:" + server.port() + "/",
        "http://127.0.0.1.evil.example:" + server.port(), "")) {
      c.headers.put("Origin", nearMiss);
      assertEquals(403, c.post(PING).status(), nearMiss);
    }
    for (String own : List.of("http://127.0.0.1:" + server.port(), "http://localhost:" + server.port())) {
      RawClient ok = client();
      ok.headers.put("Origin", own);
      assertEquals(200, ok.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").status(),
          own);
    }
  }

  @Test
  void aBodyOverTheLimitIs413() throws Exception {
    String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\""
        + "x".repeat(McpHttpServer.MAX_BODY) + "\"}";
    assertEquals(413, client().post(big).status());
  }

  @Test
  void aChunkedBodyOverTheLimitIs413() throws Exception {
    byte[] big = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\""
        + "x".repeat(McpHttpServer.MAX_BODY + 1000) + "\"}").getBytes(StandardCharsets.UTF_8);
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + McpHttpServer.PATH))
        .version(HttpClient.Version.HTTP_1_1)
        .header("Authorization", "Bearer " + token.get())
        .header("Content-Type", "application/json")
        // a stream of unknown length goes out chunked, without Content-Length
        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)))
        .build();
    assertTrue(request.bodyPublisher().orElseThrow().contentLength() < 0, "sent without a length");
    HttpResponse<String> r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(413, r.statusCode());
  }

  @Test
  void getIs405AndOtherPathsAre404() throws Exception {
    RawClient c = client();
    RawClient.Reply get = c.send("GET");
    assertEquals(405, get.status());
    assertEquals("POST, DELETE", get.headers().firstValue("Allow").orElse(null));
    assertEquals(404, c.send("POST", URI.create("http://127.0.0.1:" + server.port() + "/other"), PING).status());
  }

  @Test
  void requestsNeedASessionFromInitialize() throws Exception {
    RawClient c = client();
    assertEquals(400, c.post(PING).status());
    c.headers.put("Mcp-Session-Id", "made-up");
    assertEquals(404, c.post(PING).status());
    RawClient s = client();
    s.initialize("test-agent");
    RawClient.Reply r = s.call(2, "ping", null);
    assertEquals(200, r.status());
    assertEquals("application/json; charset=utf-8", r.headers().firstValue("Content-Type").orElse(null));
  }

  @Test
  void notificationsAndClientResponsesGet202WithoutABody() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    RawClient.Reply n = c.post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    assertEquals(202, n.status());
    assertEquals("", n.body());
    assertEquals(202, c.post("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}").status());
  }

  @Test
  void batchesAndBrokenJsonAreRefused() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    RawClient.Reply batch = c.post("[" + PING + "]");
    assertEquals(400, batch.status());
    assertEquals(McpProtocol.INVALID_REQUEST, batch.json().getAsJsonObject("error").get("code").getAsInt());
    RawClient.Reply broken = c.post("{\"jsonrpc\":");
    assertEquals(400, broken.status());
    assertEquals(McpProtocol.PARSE_ERROR, broken.json().getAsJsonObject("error").get("code").getAsInt());
    RawClient.Reply noVersion = c.post("{\"id\":1,\"method\":\"ping\"}");
    assertEquals(400, noVersion.status());
  }

  @Test
  void anUnsupportedProtocolVersionHeaderIsRefused() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    c.headers.put("MCP-Protocol-Version", "1999-01-01");
    assertEquals(400, c.call(2, "ping", null).status());
    c.headers.put("MCP-Protocol-Version", "2025-06-18");
    assertEquals(200, c.call(3, "ping", null).status());
  }

  @Test
  void deleteEndsTheSession() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    assertEquals(200, c.send("DELETE").status());
    assertEquals(404, c.call(2, "ping", null).status());
    assertEquals(404, c.send("DELETE").status());
  }

  @Test
  void sessionsAreLimited() throws Exception {
    for (int i = 0; i < 40; i++) {
      client().initialize("test-agent");
    }
    assertEquals(32, server.sessionCount());
  }

  @Test
  void aStoppedServerRefusesConnections() throws Exception {
    int port = server.port();
    server.stop();
    assertEquals(-1, server.port());
    assertThrows(ConnectException.class, () -> new RawClient(port, token.get()).post(PING));
  }

  @Test
  void endSessionsForgetsEverySession() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    server.endSessions();
    assertEquals(404, c.call(2, "ping", null).status());
  }

  @Test
  void idleAndStalledConnectionsDoNotHoldUpOtherClients() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    List<Socket> stalled = new ArrayList<>();
    try {
      stalled.add(new Socket("127.0.0.1", server.port())); // connected, sends nothing
      Socket partialHead = new Socket("127.0.0.1", server.port());
      partialHead.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      partialHead.getOutputStream().flush();
      stalled.add(partialHead);
      Socket partialBody = new Socket("127.0.0.1", server.port());
      OutputStream out = partialBody.getOutputStream();
      out.write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token.get()
          + "\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"jsonrpc\"")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      stalled.add(partialBody);
      Thread.sleep(300); // let the server take up all three connections

      HttpRequest ping = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + McpHttpServer.PATH))
          .timeout(Duration.ofSeconds(3))
          .header("Authorization", "Bearer " + token.get())
          .header("Mcp-Session-Id", c.headers.get("Mcp-Session-Id"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(PING))
          .build();
      HttpClient fresh = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      for (int i = 0; i < 3; i++) {
        assertEquals(200, fresh.send(ping, HttpResponse.BodyHandlers.ofString()).statusCode());
      }

      long t0 = System.nanoTime();
      server.stop();
      assertTrue(System.nanoTime() - t0 < Duration.ofSeconds(2).toNanos(), "stop() returns at once");
    } finally {
      for (Socket s : stalled) {
        s.close();
      }
    }
  }

  @Test
  void toolCallsRunOneAtATime() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    Tool slow = EchoTools.tool("slow", true, (args, ctx) -> {
      most.accumulateAndGet(running.incrementAndGet(), Math::max);
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        running.decrementAndGet();
      }
      return new com.google.gson.JsonObject();
    }, new String[][] {});
    server.stop();
    server = new McpHttpServer(new McpProtocol("AfterGit", "0.0.1", "", List.of(slow), CallLog.NONE), token::get, 0);
    server.start();
    List<Thread> threads = new ArrayList<>();
    List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < 4; i++) {
      RawClient c = client();
      c.initialize("test-agent");
      threads.add(Thread.ofPlatform().start(() -> {
        try {
          statuses.add(c.call(2, "tools/call", "{\"name\":\"slow\"}").status());
        } catch (Exception e) {
          statuses.add(-1);
        }
      }));
    }
    for (Thread t : threads) {
      t.join(10_000);
    }
    assertEquals(List.of(200, 200, 200, 200), statuses);
    assertEquals(1, most.get());
  }

  @Test
  void theBearerSchemeIsCaseInsensitive() throws Exception {
    RawClient c = new RawClient(server.port(), null);
    c.headers.put("Authorization", "bearer " + token.get());
    c.initialize("test-agent");
    assertEquals(200, c.call(2, "ping", null).status());
  }

  @Test
  void anEmptyTokenNeverAuthorizes() {
    assertFalse(McpHttpServer.authorized("Bearer ", ""));
    assertFalse(McpHttpServer.authorized("Bearer", ""));
    assertFalse(McpHttpServer.authorized("Bearer x", null));
    assertTrue(McpHttpServer.authorized("BEARER x", "x"));
    assertFalse(McpHttpServer.authorized("Bearerx", "x"));
  }

  @Test
  void onlyStrictJsonIsAccepted() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    for (String lenient : List.of("{jsonrpc:'2.0',id:1,method:'ping'}", PING + " x", PING + PING,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",}", "")) {
      RawClient.Reply r = c.post(lenient);
      assertEquals(400, r.status(), lenient);
      assertEquals(McpProtocol.PARSE_ERROR, r.json().getAsJsonObject("error").get("code").getAsInt(),
          "body: " + lenient);
    }
    assertEquals(200, c.post(" " + PING + "\n").status());
  }

  @Test
  void deepNestingIsAParseErrorNotACrash() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    String deep = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"p\":" + "[".repeat(400_000) + "}";
    RawClient.Reply r = c.post(deep);
    assertEquals(400, r.status());
    assertEquals(McpProtocol.PARSE_ERROR, r.json().getAsJsonObject("error").get("code").getAsInt());
    assertEquals(200, c.call(2, "ping", null).status());
  }

  @Test
  void notificationsAndClientResponsesNeedAKnownSessionToo() throws Exception {
    String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    String response = "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}";
    RawClient c = client();
    assertEquals(400, c.post(notification).status());
    assertEquals(400, c.post(response).status());
    c.headers.put("Mcp-Session-Id", "made-up");
    assertEquals(404, c.post(notification).status());
    assertEquals(404, c.post(response).status());
  }

  @Test
  void sessionsDoNotSurviveARestart() throws Exception {
    RawClient c = client();
    c.initialize("test-agent");
    String session = c.headers.get("Mcp-Session-Id");
    server.stop();
    server.start();
    assertEquals(0, server.sessionCount());
    RawClient again = client();
    again.headers.put("Mcp-Session-Id", session);
    assertEquals(404, again.call(2, "ping", null).status());
  }
}
