package net.aftergit.merge.mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Reading and describing tool arguments; mistakes are tool errors, so the agent can correct itself. */
public final class Args {
  private Args() {}

  public static String string(JsonObject args, String name) throws ToolException {
    String v = optionalString(args, name);
    if (v == null) {
      throw new ToolException("Argument \"" + name + "\" is required");
    }
    return v;
  }

  public static String optionalString(JsonObject args, String name) throws ToolException {
    JsonElement e = args.get(name);
    if (e == null || e.isJsonNull()) {
      return null;
    }
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
      throw new ToolException("Argument \"" + name + "\" must be a string");
    }
    return e.getAsString();
  }

  /** Refuses arguments the tool does not know. */
  public static void only(JsonObject args, String... names) throws ToolException {
    List<String> known = List.of(names);
    for (String k : args.keySet()) {
      if (!known.contains(k)) {
        throw new ToolException("Unknown argument \"" + k + "\"; this tool takes " + known);
      }
    }
  }

  /** Schema of an arguments object with string properties ({name, description} pairs) and the required ones. */
  public static JsonObject schema(String[][] properties, String... required) {
    JsonObject s = new JsonObject();
    s.addProperty("type", "object");
    JsonObject props = new JsonObject();
    for (String[] p : properties) {
      JsonObject o = new JsonObject();
      o.addProperty("type", "string");
      o.addProperty("description", p[1]);
      props.add(p[0], o);
    }
    s.add("properties", props);
    JsonArray req = new JsonArray();
    for (String r : required) {
      req.add(r);
    }
    s.add("required", req);
    s.addProperty("additionalProperties", false);
    return s;
  }
}
