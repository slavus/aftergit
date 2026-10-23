package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §4, §6: the Anthropic Messages API against a fake server. */
class AnthropicApiTest {
  FakeModelServer server;
  ModelResolver resolver;

  @BeforeEach
  void start() throws Exception {
    server = new FakeModelServer();
    KeyStore keys = KeyStore.memory();
    keys.set(Provider.ANTHROPIC, "test-key");
    Settings s = new Settings(true, Provider.ANTHROPIC, server.uri("/v1/messages").toString(), "test-model", true,
        40, 10);
    resolver = new ModelResolver(() -> s, keys, new ModelCall());
  }

  @AfterEach
  void stop() {
    server.close();
  }

  /** A Messages API answer with these blocks ("type:text" pairs) and stop reason. */
  static String answer(String stopReason, String... typeAndText) {
    JsonArray content = new JsonArray();
    for (int i = 0; i < typeAndText.length; i += 2) {
      JsonObject block = new JsonObject();
      block.addProperty("type", typeAndText[i]);
      block.addProperty(typeAndText[i].equals("thinking") ? "thinking" : "text", typeAndText[i + 1]);
      content.add(block);
    }
    JsonObject o = new JsonObject();
    o.addProperty("type", "message");
    o.addProperty("role", "assistant");
    o.add("content", content);
    o.addProperty("stop_reason", stopReason);
    return o.toString();
  }

  @Test
  void theRequestHasTheKeyTheVersionTheModelAndThePromptWithTheSides() throws AiException {
    server.body = answer("end_turn", "text", "{\"resolution\": \"x\"}");
    resolver.resolve(PromptTest.REQUEST, () -> false);
    FakeModelServer.Request r = server.requests.get(0);
    assertEquals("POST", r.method());
    assertEquals("/v1/messages", r.path());
    assertEquals("test-key", r.header("x-api-key"));
    assertEquals(AnthropicApi.VERSION, r.header("anthropic-version"));
    assertEquals("application/json", r.header("content-type"));
    assertNull(r.header("Authorization"));
    JsonObject b = JsonParser.parseString(r.body()).getAsJsonObject();
    assertEquals("test-model", b.get("model").getAsString());
    assertEquals(AnthropicApi.MAX_TOKENS, b.get("max_tokens").getAsInt());
    assertEquals(Prompt.SYSTEM, b.get("system").getAsString());
    JsonArray messages = b.getAsJsonArray("messages");
    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
    String user = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertEquals(Prompt.user(PromptTest.REQUEST), user);
    assertTrue(user.contains("int a = 1;") && user.contains("int a = 2;") && user.contains("Calc.java"));
  }

  @Test
  void aPlainAnswerIsRead() throws AiException {
    server.body = answer("end_turn", "text", "{\"resolution\": \"int a = 3;\", \"rationale\": \"sum\"}");
    AiAnswer a = resolver.resolve(PromptTest.REQUEST, () -> false);
    assertEquals("int a = 3;\n", a.resolution());
    assertEquals("sum", a.rationale());
  }

  @Test
  void thinkingBlocksAreSkippedAndAFencedAnswerIsRead() throws AiException {
    server.body = answer("end_turn", "thinking", "", "text", "```json\n{\"resolution\": \"y\"}\n```");
    assertEquals("y\n", resolver.resolve(PromptTest.REQUEST, () -> false).resolution());
  }

  @Test
  void aRefusalIsReported() {
    server.body = answer("refusal");
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(AnthropicApi.DECLINED, e.getMessage());
  }

  @Test
  void aCutOffAnswerIsReported() {
    server.body = answer("max_tokens", "text", "{\"resolution\": \"int a");
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(AnthropicApi.CUT_OFF, e.getMessage());
  }

  @Test
  void anAnswerStoppedByTheContextWindowIsCutOff() {
    server.body = answer("model_context_window_exceeded", "text", "{\"resolution\": \"x\"}");
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(AnthropicApi.CUT_OFF, e.getMessage());
  }

  @Test
  void anHttpErrorNamesStatusAndMessageButNeverTheKey() {
    server.status = 401;
    server.body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
        + "\"message\":\"invalid x-api-key: test-key\"}}";
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertTrue(e.getMessage().startsWith("HTTP 401 from 127.0.0.1: invalid x-api-key"), e.getMessage());
    assertFalse(e.getMessage().contains("test-key"), e.getMessage());
    assertEquals("HTTP 401 from 127.0.0.1", e.logMessage(), "the server's words never go into the log");
    assertTrue(e.sent());
  }

  @Test
  void theServersMessageIsOneLineInTheStatus() {
    server.status = 400;
    server.body = "{\"error\":{\"message\":\"bad\\ncontent\\tint x = 1;\"}}";
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals("HTTP 400 from 127.0.0.1: bad content int x = 1;", e.getMessage());
    assertEquals("HTTP 400 from 127.0.0.1", e.logMessage());
  }

  @Test
  void anErrorWithoutJsonStillNamesTheStatus() {
    server.status = 502;
    server.body = "Bad gateway";
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals("HTTP 502 from 127.0.0.1", e.getMessage());
  }

  @Test
  void aBodyThatIsNoJsonCouldNotBeRead() {
    server.body = "<html>oops</html>";
    AiException e = assertThrows(AiException.class, () -> resolver.resolve(PromptTest.REQUEST, () -> false));
    assertEquals(AnswerParser.UNREADABLE, e.getMessage());
  }
}
