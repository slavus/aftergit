package net.aftergit.merge.mcp.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** A hand-written MCP client for the UI tests (fragments do not share classes, so this repeats the one of mcp.tests). */
public final class RawMcp {
  public record Reply(int status, String body, HttpResponse<String> response) {
    public JsonObject json() {
      return JsonParser.parseString(body).getAsJsonObject();
    }
  }

  private final HttpClient http = HttpClient.newHttpClient();
  private final URI uri;
  private final String token;
  private String session;
  private int id;

  public RawMcp(String url, String token) {
    this.uri = URI.create(url);
    this.token = token;
  }

  public Reply post(String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (session != null) {
      b.header("Mcp-Session-Id", session);
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(r.statusCode(), r.body(), r);
  }

  public JsonObject initialize() throws Exception {
    Reply r = post("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
        + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-agent\",\"version\":\"1\"}}}");
    if (r.status() != 200) {
      throw new AssertionError("initialize answered " + r.status() + ": " + r.body());
    }
    session = r.response().headers().firstValue("Mcp-Session-Id").orElseThrow();
    post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    return r.json().getAsJsonObject("result");
  }

  public JsonObject request(String method, JsonObject params) throws Exception {
    JsonObject m = new JsonObject();
    m.addProperty("jsonrpc", "2.0");
    m.addProperty("id", ++id);
    m.addProperty("method", method);
    if (params != null) {
      m.add("params", params);
    }
    Reply r = post(m.toString());
    if (r.status() != 200) {
      throw new AssertionError(method + " answered " + r.status() + ": " + r.body());
    }
    return r.json();
  }

  /** The result of tools/call (with isError, content, structuredContent). */
  public JsonObject tool(String name, JsonObject args) throws Exception {
    JsonObject p = new JsonObject();
    p.addProperty("name", name);
    p.add("arguments", args);
    return request("tools/call", p).getAsJsonObject("result");
  }

  public List<String> toolNames() throws Exception {
    List<String> out = new ArrayList<>();
    JsonArray tools = request("tools/list", null).getAsJsonObject("result").getAsJsonArray("tools");
    for (JsonElement t : tools) {
      out.add(t.getAsJsonObject().get("name").getAsString());
    }
    return out;
  }

  public static JsonObject args(String... nameValue) {
    JsonObject o = new JsonObject();
    for (int i = 0; i < nameValue.length; i += 2) {
      o.addProperty(nameValue[i], nameValue[i + 1]);
    }
    return o;
  }
}
