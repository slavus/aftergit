package net.aftergit.merge.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** A ready MCP client configuration for the clipboard (agents design §4); AfterGit never writes it to a file. */
public final class McpConfigSnippet {
  public enum Client {
    CLAUDE_CODE("Claude Code (.mcp.json)"),
    CURSOR("Cursor (.cursor/mcp.json)"),
    VS_CODE("VS Code (.vscode/mcp.json)");

    private final String label;

    Client(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private McpConfigSnippet() {}

  public static String json(Client client, String url, String token) {
    JsonObject server = new JsonObject();
    if (client != Client.CURSOR) {
      server.addProperty("type", "http");
    }
    server.addProperty("url", url);
    JsonObject headers = new JsonObject();
    headers.addProperty("Authorization", "Bearer " + token);
    server.add("headers", headers);
    JsonObject servers = new JsonObject();
    servers.add("aftergit", server);
    JsonObject root = new JsonObject();
    root.add(client == Client.VS_CODE ? "servers" : "mcpServers", servers);
    return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
  }
}
