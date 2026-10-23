package net.aftergit.merge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequest;

/**
 * The Anthropic Messages API (design §4, Ruling 16 of plan 6b): model, max_tokens, system and one user message; no
 * parameter that only some models take, as the model is the user's choice.
 */
final class AnthropicApi {
  static final String VERSION = "2023-06-01";
  static final int MAX_TOKENS = 16_000;
  static final String DECLINED = "The model declined to resolve this conflict";
  static final String CUT_OFF = "The model's answer was cut off before it ended";

  private AnthropicApi() {}

  static Map<String, String> headers(String key) {
    return Map.of("content-type", "application/json", "x-api-key", key, "anthropic-version", VERSION);
  }

  static String body(String model, AiRequest r) {
    JsonObject user = new JsonObject();
    user.addProperty("role", "user");
    user.addProperty("content", Prompt.user(r));
    JsonArray messages = new JsonArray();
    messages.add(user);
    JsonObject o = new JsonObject();
    o.addProperty("model", model);
    o.addProperty("max_tokens", MAX_TOKENS);
    o.addProperty("system", Prompt.SYSTEM);
    o.add("messages", messages);
    return o.toString();
  }

  /** The text of the answer: its "text" blocks joined; thinking and other blocks are skipped. */
  static String answerText(ModelCall.Response r, String host) throws AiException {
    if (r.status() != 200) {
      throw Json.httpError(r, host);
    }
    JsonObject o = Json.object(r.body());
    String stop = Json.string(o, "stop_reason");
    if ("refusal".equals(stop)) {
      throw new AiException(DECLINED);
    }
    if ("max_tokens".equals(stop) || "model_context_window_exceeded".equals(stop)) {
      throw new AiException(CUT_OFF);
    }
    StringBuilder text = new StringBuilder();
    if (o.get("content") instanceof JsonArray content) {
      for (JsonElement e : content) {
        if (e instanceof JsonObject block && "text".equals(Json.string(block, "type"))) {
          String t = Json.string(block, "text");
          text.append(t == null ? "" : t);
        }
      }
    }
    if (text.isEmpty()) {
      throw new AiException(AnswerParser.UNREADABLE);
    }
    return text.toString();
  }
}
