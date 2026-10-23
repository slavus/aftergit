package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.ai.AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §3, §5, §6: HTTP with a timeout, cancellation, a size limit and no redirects. */
class ModelCallTest {
  FakeModelServer server;
  final ModelCall call = new ModelCall();

  @BeforeEach
  void start() throws Exception {
    server = new FakeModelServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  static long seconds(long startNanos) {
    return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
  }

  @Test
  void theBodyAndTheHeadersAreSentAsGiven() throws AiException {
    server.body = "{\"ok\": true}";
    ModelCall.Response r = call.post(server.uri("/v1/x"), Map.of("x-test", "1"), "{\"a\": 1}", Duration.ofSeconds(5),
        () -> false);
    assertEquals(200, r.status());
    assertEquals("{\"ok\": true}", r.body());
    FakeModelServer.Request in = server.requests.get(0);
    assertEquals("POST", in.method());
    assertEquals("/v1/x", in.path());
    assertEquals("1", in.header("x-test"));
    assertEquals("{\"a\": 1}", in.body());
  }

  @Test
  void aSlowServerTimesOut() {
    server.delayMs = 10_000;
    long start = System.nanoTime();
    AiException e = assertThrows(AiException.class,
        () -> call.post(server.uri("/"), Map.of(), "{}", Duration.ofSeconds(1), () -> false));
    assertEquals("No answer within 1 s", e.getMessage());
    assertTrue(seconds(start) < 5);
  }

  @Test
  void cancellingStopsAWaitingRequest() {
    server.delayMs = 10_000;
    AtomicBoolean cancelled = new AtomicBoolean();
    java.util.concurrent.CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)
        .execute(() -> cancelled.set(true));
    long start = System.nanoTime();
    AiException e = assertThrows(AiException.class,
        () -> call.post(server.uri("/"), Map.of(), "{}", Duration.ofSeconds(60), cancelled::get));
    assertEquals(ModelCall.CANCELLED, e.getMessage());
    assertTrue(seconds(start) < 5);
  }

  @Test
  void aResponseOverTheLimitIsRefused() {
    server.body = "x".repeat(ModelCall.MAX_RESPONSE_BYTES + 1);
    AiException e = assertThrows(AiException.class,
        () -> call.post(server.uri("/"), Map.of(), "{}", Duration.ofSeconds(30), () -> false));
    assertEquals(ModelCall.TOO_LARGE, e.getMessage());
  }

  @Test
  void aRedirectIsNotFollowed() throws AiException {
    server.status = 302;
    server.body = "";
    server.responseHeaders = Map.of("Location", server.uri("/elsewhere").toString());
    ModelCall.Response r = call.post(server.uri("/"), Map.of("x-api-key", "test-key"), "{}", Duration.ofSeconds(5),
        () -> false);
    assertEquals(302, r.status());
    assertEquals(1, server.requests.size(), "the key goes nowhere else");
  }

  @Test
  void aHeaderTheClientRefusesIsAMessageWithoutItsValue() {
    AiException e = assertThrows(AiException.class, () -> call.post(server.uri("/"),
        Map.of("x-api-key", "test-key\r\nx-other: 1"), "{}", Duration.ofSeconds(5), () -> false));
    assertEquals(ModelCall.INVALID_REQUEST, e.getMessage());
    assertFalse(e.getMessage().contains("test-key"), e.getMessage());
    assertNull(e.getCause(), "the cause may hold the value");
    assertTrue(server.requests.isEmpty());
    e = assertThrows(AiException.class, () -> call.post(server.uri("/"), Map.of("connection", "test-key"), "{}",
        Duration.ofSeconds(5), () -> false));
    assertEquals(ModelCall.INVALID_REQUEST, e.getMessage());
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void aClosedPortIsAClearMessage() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    URI uri = URI.create("http://127.0.0.1:" + port + "/v1/messages");
    AiException e = assertThrows(AiException.class,
        () -> call.post(uri, Map.of(), "{}", Duration.ofSeconds(5), () -> false));
    assertEquals("Could not connect to 127.0.0.1:" + port, e.getMessage());
  }

  @Test
  void nothingIsSentWhenCancelledBeforeTheStart() throws Exception {
    AiException e = assertThrows(AiException.class,
        () -> call.post(server.uri("/"), Map.of(), "{\"a\": 1}", Duration.ofSeconds(5), () -> true));
    assertEquals(ModelCall.CANCELLED, e.getMessage());
    Thread.sleep(300);
    assertTrue(server.requests.isEmpty(), "the conflict never leaves");
  }

  @Test
  void aTimeoutThatIsNotPositiveIsAMessageNotACrash() {
    for (Duration d : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
      AiException e = assertThrows(AiException.class,
          () -> call.post(server.uri("/"), Map.of(), "{}", d, () -> false), d.toString());
      assertEquals(ModelCall.INVALID_REQUEST, e.getMessage());
    }
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void aPlainHttpServerIsAskedWithHttp11WithoutAnUpgrade() throws AiException {
    call.post(server.uri("/"), Map.of(), "{}", Duration.ofSeconds(5), () -> false);
    FakeModelServer.Request in = server.requests.get(0);
    assertNull(in.header("upgrade"), "no h2c upgrade to a local server");
    assertNull(in.header("http2-settings"));
  }
}
