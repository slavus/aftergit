package net.aftergit.merge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequest;

/**
 * OpenAI-compatible Chat Completions (design §4): OpenAI, Ollama, LM Studio and others. No max_tokens: servers and
 * models differ in what they accept (Ruling 16 of plan 6b); the key goes as Bearer only when there is one.
 */
final class OpenAiApi {
  private OpenAiApi() {}

  static Map<String, String> headers(String key) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("content-type", "application/json");
    if (key != null && !key.isEmpty()) {
      h.put("Authorization", "Bearer " + key);
    }
    return h;
  }

  static String body(String model, AiRequest r) {
    JsonArray messages = new JsonArray();
    messages.add(message("system", Prompt.SYSTEM));
    messages.add(message("user", Prompt.user(r)));
    JsonObject o = new JsonObject();
    o.addProperty("model", model);
    o.add("messages", messages);
    return o.toString();
  }

  private static JsonObject message(String role, String content) {
    JsonObject m = new JsonObject();
    m.addProperty("role", role);
    m.addProperty("content", content);
    return m;
  }

  static String answerText(ModelCall.Response r, String host) throws AiException {
    if (r.status() != 200) {
      throw Json.httpError(r, host);
    }
    JsonObject o = Json.object(r.body());
    JsonObject choice = o.get("choices") instanceof JsonArray a && !a.isEmpty() && a.get(0) instanceof JsonObject c
        ? c : null;
    if (choice == null) {
      throw new AiException(AnswerParser.UNREADABLE);
    }
    JsonObject message = Json.child(choice, "message");
    String refusal = Json.string(message, "refusal");
    String finish = Json.string(choice, "finish_reason");
    if (refusal != null && !refusal.isBlank() || "content_filter".equals(finish)) {
      throw new AiException(AnthropicApi.DECLINED);
    }
    if ("length".equals(finish)) {
      throw new AiException(AnthropicApi.CUT_OFF);
    }
    String content = Json.string(message, "content");
    if (content == null || content.isBlank()) {
      throw new AiException(AnswerParser.UNREADABLE);
    }
    return content;
  }
}
