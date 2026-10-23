package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §1, §4, §5: ready, remote, the key and the limits of the built-in resolver. */
class ModelResolverTest {
  FakeModelServer server;
  KeyStore keys;

  @BeforeEach
  void start() throws Exception {
    AiPreferences.reset();
    server = new FakeModelServer();
    server.body = AnthropicApiTest.answer("end_turn", "text", "{\"resolution\": \"x\"}");
    keys = KeyStore.memory();
    keys.set(Provider.ANTHROPIC, "test-key");
  }

  @AfterEach
  void stop() {
    server.close();
    AiPreferences.reset();
  }

  Settings anthropic(boolean enabled, boolean keySet) {
    return new Settings(enabled, Provider.ANTHROPIC, server.uri("/v1/messages").toString(), "test-model", keySet,
        40, 10);
  }

  /** A key store that must not be touched. */
  static final KeyStore UNTOUCHABLE = new KeyStore() {
    @Override
    public String get(Provider p) {
      throw new AssertionError("secure storage was opened");
    }

    @Override
    public void set(Provider p, String key) {
      throw new AssertionError("secure storage was opened");
    }

    @Override
    public void remove(Provider p) {
      throw new AssertionError("secure storage was opened");
    }
  };

  @Test
  void theDefaultResolverReadsThePreferencesAndIsOff() {
    ModelResolver r = new ModelResolver();
    assertFalse(r.ready());
    assertEquals("Anthropic claude-sonnet-5-5", r.name());
    assertTrue(r.remote());
    assertEquals(40, r.contextLines());
  }

  @Test
  void switchedOffItIsNotReadyAndSendsNothing() {
    ModelResolver r = new ModelResolver(() -> anthropic(false, true), UNTOUCHABLE, new ModelCall());
    assertFalse(r.ready());
    AiException e = assertThrows(AiException.class, () -> r.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(ModelResolver.OFF, e.getMessage());
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void anAnthropicResolverWhoseKeyIsGoneFailsWithoutARequest() throws AiException {
    keys.remove(Provider.ANTHROPIC);
    ModelResolver r = new ModelResolver(() -> anthropic(true, true), keys, new ModelCall());
    assertTrue(r.ready(), "the flag says a key is set");
    AiException e = assertThrows(AiException.class, () -> r.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(ModelResolver.NO_KEY, e.getMessage());
    assertFalse(e.sent());
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void aLocalServerNeedsNoKeyAndOpensNoStorage() throws AiException {
    server.body = OpenAiApiTest.answer("{\"resolution\": \"x\"}", "stop", null);
    Settings local = new Settings(true, Provider.OPENAI_COMPATIBLE, server.uri("/v1/chat/completions").toString(),
        "test-model", false, 40, 10);
    ModelResolver r = new ModelResolver(() -> local, UNTOUCHABLE, new ModelCall());
    assertTrue(r.ready());
    assertFalse(r.remote());
    assertEquals("x\n", r.resolve(PromptTest.REQUEST, () -> false).resolution());
  }

  @Test
  void remoteIsFalseOnlyForLoopback() {
    for (String[] c : new String[][] {{"http://localhost:11434/v1/chat/completions", "false"},
        {"http://127.0.0.1:1234/v1/chat/completions", "false"}, {"http://[::1]:8080/v1", "false"},
        {"https://api.anthropic.com/v1/messages", "true"}, {"http://192.168.1.5:11434/v1", "true"},
        {"not a url", "true"}}) {
      Settings s = new Settings(true, Provider.OPENAI_COMPATIBLE, c[0], "m", false, 40, 10);
      assertEquals(Boolean.parseBoolean(c[1]), new ModelResolver(() -> s, UNTOUCHABLE, new ModelCall()).remote(),
          c[0]);
    }
  }

  @Test
  void theDestinationIsTheSchemeHostAndPortOfTheEndpoint() {
    for (String[] c : new String[][] {{"https://api.anthropic.com/v1/messages", "https://api.anthropic.com:443"},
        {"http://LocalHost/v1/chat/completions", "http://localhost:80"},
        {"http://192.168.1.5:11434/v1/chat/completions", "http://192.168.1.5:11434"},
        {"HTTPS://Models.Example.COM:8443/v1", "https://models.example.com:8443"},
        {"http://[::1]:8080/v1", "http://[::1]:8080"}, {"not a url", ""}}) {
      Settings s = new Settings(true, Provider.OPENAI_COMPATIBLE, c[0], "m", false, 40, 10);
      assertEquals(c[1], new ModelResolver(() -> s, UNTOUCHABLE, new ModelCall()).destination(), c[0]);
    }
  }

  @Test
  void theDefaultAnthropicEndpointIsItsDestination() {
    Settings s = new Settings(true, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "m", true, 40, 10);
    assertEquals("https://api.anthropic.com:443",
        new ModelResolver(() -> s, UNTOUCHABLE, new ModelCall()).destination());
  }

  @Test
  void aRequestOverTheLimitIsRefusedBeforeSending() {
    AiRequest big = new AiRequest("a.txt", "txt", "", "x".repeat(AiRequest.MAX_CHARS + 1), "", "", "", "", null,
        null, null, "", "");
    ModelResolver r = new ModelResolver(() -> anthropic(true, true), keys, new ModelCall());
    AiException e = assertThrows(AiException.class, () -> r.resolve(big, () -> false));
    assertTrue(e.getMessage().contains("200 000"), e.getMessage());
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void theKeyNeverAppearsInAMessage() {
    server.status = 400;
    server.body = "{\"error\":{\"message\":\"the key test-key is not valid for test-key\"}}";
    ModelResolver r = new ModelResolver(() -> anthropic(true, true), keys, new ModelCall());
    AiException e = assertThrows(AiException.class, () -> r.resolve(PromptTest.REQUEST, () -> false));
    assertFalse(e.getMessage().contains("test-key"), e.getMessage());
    assertTrue(e.getMessage().contains("***"), e.getMessage());
  }

  @Test
  void theNameAndTheContextLinesFollowTheSettings() {
    Settings s = new Settings(true, Provider.ANTHROPIC, "https://api.anthropic.com/v1/messages", "other-model", true,
        7, 30);
    ModelResolver r = new ModelResolver(() -> s, UNTOUCHABLE, new ModelCall());
    assertEquals("Anthropic other-model", r.name());
    assertEquals(7, r.contextLines());
  }

  @Test
  void testConnectionUsesTheValuesOnThePageAndTheTypedKey() throws AiException {
    ModelResolver r = new ModelResolver(() -> anthropic(false, false), UNTOUCHABLE, new ModelCall());
    AiAnswer a = r.test(anthropic(false, false), "typed-key", () -> false);
    assertEquals("x\n", a.resolution());
    assertEquals("typed-key", server.requests.get(0).header("x-api-key"));
    assertTrue(server.requests.get(0).body().contains("connection-test.txt"));
    assertEquals(List.of(), ModelResolver.TEST_REQUEST.resultErrors());
    AiException e = assertThrows(AiException.class, () -> r.test(anthropic(true, false), "", () -> false));
    assertEquals(ModelResolver.NO_KEY, e.getMessage());
    Settings badEndpoint = new Settings(true, Provider.ANTHROPIC, "nowhere", "m", true, 40, 10);
    assertThrows(AiException.class, () -> r.test(badEndpoint, "k", () -> false));
    assertEquals(1, server.requests.size());
  }
}
