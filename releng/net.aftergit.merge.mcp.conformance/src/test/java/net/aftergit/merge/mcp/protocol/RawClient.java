package net.aftergit.merge.mcp.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** A hand-written MCP client: plain HTTP requests, so tests can send what the SDK never would. */
final class RawClient {
  record Reply(int status, HttpHeaders headers, String body) {
    JsonObject json() {
      return JsonParser.parseString(body).getAsJsonObject();
    }
  }

  private final HttpClient http = HttpClient.newHttpClient();
  private final URI uri;
  final Map<String, String> headers = new LinkedHashMap<>();

  RawClient(int port, String token) {
    uri = URI.create("http://127.0.0.1:" + port + McpHttpServer.PATH);
    if (token != null) {
      headers.put("Authorization", "Bearer " + token);
    }
  }

  Reply post(String body) throws Exception {
    return send("POST", uri, body);
  }

  Reply send(String method, URI target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(target)
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream");
    headers.forEach(b::header);
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(r.statusCode(), r.headers(), r.body());
  }

  Reply send(String method) throws Exception {
    return send(method, uri, null);
  }

  /** Initializes and keeps the session id for the next requests. */
  JsonObject initialize(String clientName) throws Exception {
    Reply r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"" + clientName + "\",\"version\":\"1\"}}}");
    if (r.status() != 200) {
      throw new AssertionError("initialize answered " + r.status() + ": " + r.body());
    }
    headers.put("Mcp-Session-Id", r.headers().firstValue("Mcp-Session-Id").orElseThrow());
    return r.json();
  }

  Reply call(int id, String method, String params) throws Exception {
    return post("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
        + (params == null ? "" : ",\"params\":" + params) + "}");
  }
}
