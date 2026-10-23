package net.aftergit.merge.mcp.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP methods AfterGit answers (initialize, ping, tools/list, tools/call) on JSON-RPC 2.0 messages; knows nothing
 * of HTTP. Tool failures become results with isError, protocol mistakes JSON-RPC errors (agents design §2).
 */
public final class McpProtocol {
  /**
   * Supported protocol revisions, newest first. AfterGit serves a subset of each: one JSON-RPC message per request
   * (no batches, which 2025-03-26 allowed), plain JSON replies (no SSE streams), tools only.
   */
  public static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;
  /** Writes replies: explicit nulls stay, '<' and '&' are not escaped. */
  public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  static final int MAX_CLIENT_NAME = 64;
  static final int MAX_LOGGED_PATH = 300;
  static final int MAX_LOGGED_MESSAGE = 500;

  private final String name;
  private final String version;
  private final String instructions;
  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final CallLog log;

  public McpProtocol(String name, String version, String instructions, List<Tool> tools, CallLog log) {
    this.name = name;
    this.version = version;
    this.instructions = instructions;
    this.log = log;
    for (Tool t : tools) {
      this.tools.put(t.name(), t);
    }
  }

  /** The reply to initialize and, when it succeeded, the client name and the protocol version of the session. */
  public record Initialized(JsonObject reply, String client, String protocolVersion) {
    public boolean ok() {
      return client != null;
    }
  }

  public Initialized initialize(JsonObject request) {
    JsonElement id = request.get("id");
    JsonObject params = object(request.get("params"));
    if (params == null) {
      return new Initialized(error(id, INVALID_PARAMS, "initialize needs params"), null, null);
    }
    String requested = string(params.get("protocolVersion"));
    String chosen = requested != null && VERSIONS.contains(requested) ? requested : VERSIONS.get(0);
    JsonObject info = object(params.get("clientInfo"));
    String client = clientName(info == null ? null : string(info.get("name")));
    JsonObject result = new JsonObject();
    result.addProperty("protocolVersion", chosen);
    JsonObject toolCaps = new JsonObject();
    toolCaps.addProperty("listChanged", false);
    JsonObject caps = new JsonObject();
    caps.add("tools", toolCaps);
    result.add("capabilities", caps);
    JsonObject server = new JsonObject();
    server.addProperty("name", name);
    server.addProperty("version", version);
    result.add("serverInfo", server);
    result.addProperty("instructions", instructions);
    return new Initialized(success(id, result), client, chosen);
  }

  /** The reply to a request of an initialized session of {@code client}. */
  public JsonObject dispatch(JsonObject request, String client) {
    JsonElement id = request.get("id");
    String method = string(request.get("method"));
    if (method == null) {
      return error(id, INVALID_REQUEST, "method must be a string");
    }
    return switch (method) {
      case "ping" -> success(id, new JsonObject());
      case "tools/list" -> success(id, toolList());
      case "tools/call" -> call(id, object(request.get("params")), client);
      default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
    };
  }

  /** Why {@code message} is no JSON-RPC 2.0 request or notification, or null when it is one. */
  public static String invalid(JsonObject message) {
    if (!"2.0".equals(string(message.get("jsonrpc")))) {
      return "jsonrpc must be \"2.0\"";
    }
    if (string(message.get("method")) == null) {
      return "method must be a string";
    }
    JsonElement id = message.get("id");
    if (id != null && !(id.isJsonPrimitive()
        && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber()))) {
      return "id must be a string or a number";
    }
    return null;
  }

  private JsonObject toolList() {
    JsonArray list = new JsonArray();
    for (Tool t : tools.values()) {
      JsonObject o = new JsonObject();
      o.addProperty("name", t.name());
      o.addProperty("description", t.description());
      o.add("inputSchema", t.inputSchema());
      JsonObject hints = new JsonObject();
      hints.addProperty("readOnlyHint", t.readOnly());
      hints.addProperty("destructiveHint", false);
      hints.addProperty("openWorldHint", false);
      o.add("annotations", hints);
      list.add(o);
    }
    JsonObject r = new JsonObject();
    r.add("tools", list);
    return r;
  }

  private JsonObject call(JsonElement id, JsonObject params, String client) {
    if (params == null) {
      return error(id, INVALID_PARAMS, "tools/call needs params");
    }
    String toolName = string(params.get("name"));
    Tool tool = toolName == null ? null : tools.get(toolName);
    if (tool == null) {
      return error(id, INVALID_PARAMS, "Unknown tool: " + toolName);
    }
    JsonElement a = params.get("arguments");
    if (a != null && !a.isJsonNull() && !a.isJsonObject()) {
      return error(id, INVALID_PARAMS, "arguments must be an object");
    }
    JsonObject args = a == null || a.isJsonNull() ? new JsonObject() : a.getAsJsonObject();
    String path = loggedPath(args);
    try {
      JsonObject out = tool.call(args, new CallContext(client));
      log.call(toolName, path, "ok");
      return success(id, toolResult(out));
    } catch (ToolException e) {
      log.call(toolName, path, "refused: " + loggable(e.getMessage(), MAX_LOGGED_MESSAGE));
      return success(id, errorResult(e.getMessage()));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      log.call(toolName, path, "failed");
      log.failure(toolName, e);
      return success(id, errorResult("AfterGit could not run " + toolName + " (" + e.getClass().getSimpleName()
          + "); see the Eclipse error log"));
    }
  }

  private static String loggedPath(JsonObject args) {
    JsonElement p = args.get("path");
    String s = p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isString() ? p.getAsString() : "-";
    return loggable(s, MAX_LOGGED_PATH);
  }

  /**
   * Text that came from the agent, fit for one log line: control characters and line or paragraph separators become
   * spaces, and at most {@code max} characters are kept.
   */
  static String loggable(String s, int max) {
    if (s == null) {
      return "-";
    }
    String cut = s.length() > max ? s.substring(0, max) + "..." : s;
    StringBuilder b = new StringBuilder(cut.length());
    for (int i = 0; i < cut.length(); i++) {
      char c = cut.charAt(i);
      b.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
    }
    return b.toString();
  }

  static JsonObject toolResult(JsonObject structured) {
    JsonObject r = new JsonObject();
    r.add("content", text(GSON.toJson(structured)));
    r.add("structuredContent", structured);
    r.addProperty("isError", false);
    return r;
  }

  static JsonObject errorResult(String message) {
    JsonObject r = new JsonObject();
    r.add("content", text(message));
    r.addProperty("isError", true);
    return r;
  }

  private static JsonArray text(String s) {
    JsonObject t = new JsonObject();
    t.addProperty("type", "text");
    t.addProperty("text", s);
    JsonArray a = new JsonArray();
    a.add(t);
    return a;
  }

  public static JsonObject success(JsonElement id, JsonElement result) {
    JsonObject r = new JsonObject();
    r.addProperty("jsonrpc", "2.0");
    r.add("id", id == null ? JsonNull.INSTANCE : id);
    r.add("result", result);
    return r;
  }

  public static JsonObject error(JsonElement id, int code, String message) {
    JsonObject e = new JsonObject();
    e.addProperty("code", code);
    e.addProperty("message", message);
    JsonObject r = new JsonObject();
    r.addProperty("jsonrpc", "2.0");
    r.add("id", id == null ? JsonNull.INSTANCE : id);
    r.add("error", e);
    return r;
  }

  /**
   * The client's name for logs and proposals: letters, digits, space, '.', '_', '-'; at most 64; else "agent".
   * "auto" (any case) is the source of AfterGit's own auto-resolution, so a client of that name becomes
   * "auto-agent".
   */
  public static String clientName(String raw) {
    if (raw == null || raw.isBlank()) {
      return "agent";
    }
    String s = raw.trim().replaceAll("[^A-Za-z0-9 ._-]", "_");
    s = s.length() > MAX_CLIENT_NAME ? s.substring(0, MAX_CLIENT_NAME) : s;
    return s.equalsIgnoreCase(RESERVED_SOURCE) ? RESERVED_SOURCE + "-agent" : s;
  }

  /** The proposal source of AfterGit's own auto-resolution; no client may use it. */
  static final String RESERVED_SOURCE = "auto";

  private static String string(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
  }

  private static JsonObject object(JsonElement e) {
    return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
  }
}
