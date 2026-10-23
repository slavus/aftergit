package net.aftergit.merge.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;

/**
 * Reads a model's answer (design §4): the JSON object {"resolution": "...", "rationale": "..."} anywhere in the
 * text, so a code fence or a sentence around it does not matter, braces in it neither: the first object whose
 * "resolution" is a string, looked for at the first {@link #MAX_STARTS} braces; anything else could not be read.
 */
public final class AnswerParser {
  public static final String UNREADABLE = "The model's answer could not be read";
  public static final String TOO_LONG = "The model's answer is longer than 200 000 characters";
  public static final String MARKERS = "The model's answer still has conflict markers";
  /** The most places an object is looked for, so a long answer full of braces stays cheap. */
  public static final int MAX_STARTS = 16;

  private AnswerParser() {}

  public static AiAnswer parse(String text) throws AiException {
    if (text == null) {
      throw new AiException(UNREADABLE);
    }
    int open = -1;
    for (int tries = 0; tries < MAX_STARTS; tries++) {
      open = text.indexOf('{', open + 1);
      if (open < 0) {
        break;
      }
      JsonObject o = objectAt(text, open);
      String resolution = o == null ? null : string(o.get("resolution"));
      if (resolution == null) {
        continue;
      }
      if (resolution.length() > AiAnswer.MAX_CHARS) {
        throw new AiException(TOO_LONG);
      }
      if (hasMarkers(resolution)) {
        throw new AiException(MARKERS);
      }
      String rationale = string(o.get("rationale"));
      return new AiAnswer(resolution, rationale == null ? "" : rationale);
    }
    throw new AiException(UNREADABLE);
  }

  /** The one JSON value read leniently from {@code open} on, whatever follows it; null when it is no object. */
  private static JsonObject objectAt(String text, int open) {
    try (StringReader in = new StringReader(text)) {
      in.skip(open);
      JsonReader reader = new JsonReader(in);
      reader.setStrictness(Strictness.LENIENT);
      JsonElement e = JsonParser.parseReader(reader);
      return e.isJsonObject() ? e.getAsJsonObject() : null;
    } catch (IOException | JsonParseException | IllegalStateException | StackOverflowError e) {
      return null; // no cause kept: a VM error must never reach a log
    }
  }

  private static String string(JsonElement e) {
    return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
  }

  /** Git's start and end markers, each at the start of a line. */
  static boolean hasMarkers(String text) {
    boolean start = false;
    boolean end = false;
    for (String line : text.split("\r?\n|\r", -1)) {
      start |= line.startsWith("<<<<<<<");
      end |= line.startsWith(">>>>>>>");
    }
    return start && end;
  }
}
