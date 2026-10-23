package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aftergit.merge.core.ai.AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §4, §6: an OpenAI-compatible Chat Completions server against a fake server. */
class OpenAiApiTest {
  FakeModelServer server;
  KeyStore keys;

  @BeforeEach
  void start() throws Exception {
    server = new FakeModelServer();
    keys = KeyStore.memory();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  ModelResolver resolver(boolean keySet) {
    Settings s = new Settings(true, Provider.OPENAI_COMPATIBLE, server.uri("/v1/chat/completions").toString(),
        "test-model", keySet, 40, 10);
    return new ModelResolver(() -> s, keys, new ModelCall());
  }

  static String answer(String content, String finishReason, String refusal) {
    JsonObject message = new JsonObject();
    message.addProperty("role", "assistant");
    message.addProperty("content", content);
    if (refusal != null) {
      message.addProperty("refusal", refusal);
    }
    JsonObject choice = new JsonObject();
    choice.add("message", message);
    choice.addProperty("finish_reason", finishReason);
    JsonArray choices = new JsonArray();
    choices.add(choice);
    JsonObject o = new JsonObject();
    o.add("choices", choices);
    return o.toString();
  }

  @Test
  void theRequestHasTheModelTheSystemAndTheUserPromptAndNoKeyWithoutOne() throws AiException {
    server.body = answer("{\"resolution\": \"x\"}", "stop", null);
    resolver(false).resolve(PromptTest.REQUEST, () -> false);
    FakeModelServer.Request r = server.requests.get(0);
    assertEquals("/v1/chat/completions", r.path());
    assertNull(r.header("Authorization"));
    assertNull(r.header("x-api-key"));
    JsonObject b = JsonParser.parseString(r.body()).getAsJsonObject();
    assertEquals("test-model", b.get("model").getAsString());
    assertFalse(b.has("max_tokens"));
    JsonArray messages = b.getAsJsonArray("messages");
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    assertEquals(Prompt.SYSTEM, messages.get(0).getAsJsonObject().get("content").getAsString());
    assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    assertEquals(Prompt.user(PromptTest.REQUEST), messages.get(1).getAsJsonObject().get("content").getAsString());
  }

  @Test
  void aKeyGoesAsBearer() throws AiException {
    keys.set(Provider.OPENAI_COMPATIBLE, "test-key");
    server.body = answer("{\"resolution\": \"x\"}", "stop", null);
    resolver(true).resolve(PromptTest.REQUEST, () -> false);
    assertEquals("Bearer test-key", server.requests.get(0).header("Authorization"));
  }

  @Test
  void aPlainAnswerIsRead() throws AiException {
    server.body = answer("{\"resolution\": \"z\", \"rationale\": \"r\"}", "stop", null);
    assertEquals("z\n", resolver(false).resolve(PromptTest.REQUEST, () -> false).resolution());
  }

  @Test
  void refusalsAndFiltersAreReported() {
    server.body = answer(null, "stop", "I can't help with that.");
    assertEquals(AnthropicApi.DECLINED, assertThrows(AiException.class,
        () -> resolver(false).resolve(PromptTest.REQUEST, () -> false)).getMessage());
    server.body = answer("", "content_filter", null);
    assertEquals(AnthropicApi.DECLINED, assertThrows(AiException.class,
        () -> resolver(false).resolve(PromptTest.REQUEST, () -> false)).getMessage());
  }

  @Test
  void aCutOffAnswerIsReported() {
    server.body = answer("{\"resolution\": \"x", "length", null);
    assertEquals(AnthropicApi.CUT_OFF, assertThrows(AiException.class,
        () -> resolver(false).resolve(PromptTest.REQUEST, () -> false)).getMessage());
  }

  @Test
  void anHttpErrorNamesStatusAndMessage() {
    server.status = 404;
    server.body = "{\"error\":{\"message\":\"model 'test-model' not found\"}}";
    AiException e = assertThrows(AiException.class, () -> resolver(false).resolve(PromptTest.REQUEST, () -> false));
    assertEquals("HTTP 404 from 127.0.0.1: model 'test-model' not found", e.getMessage());
    server.body = "{\"error\":\"overloaded\"}";
    e = assertThrows(AiException.class, () -> resolver(false).resolve(PromptTest.REQUEST, () -> false));
    assertEquals("HTTP 404 from 127.0.0.1: overloaded", e.getMessage());
    assertEquals("HTTP 404 from 127.0.0.1", e.logMessage());
  }

  @Test
  void noChoicesCouldNotBeRead() {
    server.body = "{\"choices\": []}";
    assertEquals(AnswerParser.UNREADABLE, assertThrows(AiException.class,
        () -> resolver(false).resolve(PromptTest.REQUEST, () -> false)).getMessage());
  }
}
