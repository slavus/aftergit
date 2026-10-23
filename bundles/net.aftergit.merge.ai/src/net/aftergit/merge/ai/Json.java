package net.aftergit.merge.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.aftergit.merge.core.ai.AiException;

/** Small Gson helpers of the two APIs. */
final class Json {
  private static final int MAX_ERROR_CHARS = 300;

  private Json() {}

  static JsonObject object(String body) throws AiException {
    try {
      JsonElement e = JsonParser.parseString(body == null ? "" : body);
      if (e.isJsonObject()) {
        return e.getAsJsonObject();
      }
    } catch (JsonParseException | IllegalStateException | StackOverflowError e) {
      // not JSON
    }
    throw new AiException(AnswerParser.UNREADABLE);
  }

  static String string(JsonObject o, String name) {
    JsonElement e = o == null ? null : o.get(name);
    return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
  }

  static JsonObject child(JsonObject o, String name) {
    JsonElement e = o == null ? null : o.get(name);
    return e instanceof JsonObject j ? j : null;
  }

  /**
   * The failure of an HTTP status other than 200: for the user "HTTP 401 from host: message" with the server's
   * message on one line (cut at 300 characters) when its body says one; for the log only "HTTP 401 from host", as a
   * server may quote the request.
   */
  static AiException httpError(ModelCall.Response r, String host) {
    String status = "HTTP " + r.status() + " from " + host;
    return new AiException(httpMessage(r, status), status, true);
  }

  private static String httpMessage(ModelCall.Response r, String status) {
    String message = null;
    try {
      JsonObject o = object(r.body());
      JsonObject error = child(o, "error");
      message = error != null ? string(error, "message") : string(o, "error");
      if (message == null) {
        message = string(o, "message");
      }
    } catch (AiException e) {
      // a body without JSON: the status alone
    }
    String m = message == null ? "" : message.replaceAll("\\p{Cntrl}+", " ").strip();
    return m.isEmpty() ? status : status + ": " + cap(m);
  }

  private static String cap(String s) {
    return s.length() <= MAX_ERROR_CHARS ? s : s.substring(0, MAX_ERROR_CHARS) + "…";
  }
}
