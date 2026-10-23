package net.aftergit.merge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Agents design §4: a ready configuration for the usual MCP clients (URL and the bearer header). */
class McpConfigSnippetTest {
  static JsonObject server(McpConfigSnippet.Client client, String key) {
    return JsonParser.parseString(McpConfigSnippet.json(client, "http://127.0.0.1:4711/mcp", "tok"))
        .getAsJsonObject().getAsJsonObject(key).getAsJsonObject("aftergit");
  }

  @Test
  void claudeCodeUsesMcpServersWithTypeHttp() {
    JsonObject s = server(McpConfigSnippet.Client.CLAUDE_CODE, "mcpServers");
    assertEquals("http", s.get("type").getAsString());
    assertEquals("http://127.0.0.1:4711/mcp", s.get("url").getAsString());
    assertEquals("Bearer tok", s.getAsJsonObject("headers").get("Authorization").getAsString());
  }

  @Test
  void cursorUsesMcpServersWithUrlAndHeaders() {
    JsonObject s = server(McpConfigSnippet.Client.CURSOR, "mcpServers");
    assertNull(s.get("type"));
    assertEquals("Bearer tok", s.getAsJsonObject("headers").get("Authorization").getAsString());
  }

  @Test
  void vsCodeUsesServersWithTypeHttp() {
    JsonObject s = server(McpConfigSnippet.Client.VS_CODE, "servers");
    assertEquals("http", s.get("type").getAsString());
    assertEquals("http://127.0.0.1:4711/mcp", s.get("url").getAsString());
  }

  @Test
  void everyClientHasALabel() {
    for (McpConfigSnippet.Client c : McpConfigSnippet.Client.values()) {
      assertFalse(c.label().isBlank());
    }
  }
}
