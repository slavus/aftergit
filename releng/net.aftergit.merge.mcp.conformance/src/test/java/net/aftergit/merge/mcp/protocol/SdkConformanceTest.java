package net.aftergit.merge.mcp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Agents design §5: protocol conformance with the official MCP Java SDK client (test scope only). */
class SdkConformanceTest {
  static final String TOKEN = "test-token";
  McpHttpServer server;
  McpSyncClient client;

  @BeforeEach
  void start() throws Exception {
    server = new McpHttpServer(new McpProtocol("AfterGit", "0.0.1", "Read, then propose.", EchoTools.all(),
        CallLog.NONE), () -> TOKEN, 0);
    server.start();
  }

  @AfterEach
  void stop() {
    if (client != null) {
      client.close();
    }
    server.stop();
  }

  McpSyncClient client(String token) {
    HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
        .builder("http://127.0.0.1:" + server.port())
        .endpoint(McpHttpServer.PATH)
        .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
        .build();
    return McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(10))
        .initializationTimeout(Duration.ofSeconds(10))
        // the schema validator of the SDK needs libraries that are not in the local repository; the tools of
        // AfterGit declare no output schema, so a validator that accepts everything changes nothing
        .jsonSchemaValidator((schema, content) -> JsonSchemaValidator.ValidationResponse.asValid(String.valueOf(content)))
        .build();
  }

  @Test
  void initializeNegotiatesAVersionAndOffersTools() {
    client = client(TOKEN);
    McpSchema.InitializeResult r = client.initialize();
    assertEquals("AfterGit", r.serverInfo().name());
    assertTrue(McpProtocol.VERSIONS.contains(r.protocolVersion()), r.protocolVersion());
    assertNotNull(r.capabilities().tools());
    assertEquals("Read, then propose.", r.instructions());
  }

  @Test
  void toolsListCarriesNamesSchemasAndHints() {
    client = client(TOKEN);
    client.initialize();
    List<McpSchema.Tool> tools = client.listTools().tools();
    assertEquals(List.of("echo", "fail"), tools.stream().map(McpSchema.Tool::name).toList());
    McpSchema.Tool echo = tools.get(0);
    assertEquals("object", echo.inputSchema().get("type"));
    assertEquals(List.of("text"), echo.inputSchema().get("required"));
    assertTrue(echo.annotations().readOnlyHint());
    assertFalse(tools.get(1).annotations().readOnlyHint());
  }

  @Test
  void aToolCallReturnsTextAndStructuredContent() {
    client = client(TOKEN);
    client.initialize();
    McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder("echo")
        .arguments(Map.of("text", "hi")).build());
    assertFalse(r.isError());
    assertEquals("hi", ((Map<?, ?>) r.structuredContent()).get("echo"));
    assertEquals("Java SDK MCP Client", ((Map<?, ?>) r.structuredContent()).get("client"));
    assertTrue(((McpSchema.TextContent) r.content().get(0)).text().contains("\"echo\":\"hi\""));
  }

  @Test
  void aToolErrorIsAResultWithIsError() {
    client = client(TOKEN);
    client.initialize();
    McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder("fail").arguments(Map.of()).build());
    assertTrue(r.isError());
    assertEquals("it failed on purpose", ((McpSchema.TextContent) r.content().get(0)).text());
  }

  @Test
  void anUnknownToolIsAProtocolError() {
    client = client(TOKEN);
    client.initialize();
    assertThrows(McpError.class, () -> client.callTool(McpSchema.CallToolRequest.builder("nope")
        .arguments(Map.of()).build()));
  }

  @Test
  void pingAnswers() {
    client = client(TOKEN);
    client.initialize();
    assertNotNull(client.ping());
  }

  @Test
  void aWrongTokenIsRefused() {
    client = client("wrong");
    assertThrows(RuntimeException.class, client::initialize);
  }
}
