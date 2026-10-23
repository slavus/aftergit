package net.aftergit.merge.mcp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agents design §2: the MCP methods without HTTP. */
class McpProtocolTest {
  final List<String> calls = new ArrayList<>();
  final List<Throwable> failures = new ArrayList<>();
  final CallLog log = new CallLog() {
    @Override public void call(String tool, String path, String outcome) { calls.add(tool + "|" + path + "|" + outcome); }
    @Override public void failure(String tool, Throwable e) { failures.add(e); }
  };
  final McpProtocol protocol = new McpProtocol("AfterGit", "0.0.1", "How to use", List.of(EchoTools.echo(),
      EchoTools.fail(), EchoTools.boom()), log);

  static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  JsonObject call(String tool, String args) {
    return protocol.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool + "\"" + (args == null ? "" : ",\"arguments\":" + args) + "}}"), "test-agent");
  }

  @Test
  void initializeTakesTheClientsVersionWhenSupportedElseTheNewest() {
    McpProtocol.Initialized a = protocol.initialize(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{\"name\":\"test-agent\"}}}"));
    assertTrue(a.ok());
    assertEquals("2025-03-26", a.protocolVersion());
    JsonObject result = a.reply().getAsJsonObject("result");
    assertEquals("AfterGit", result.getAsJsonObject("serverInfo").get("name").getAsString());
    assertFalse(result.getAsJsonObject("capabilities").getAsJsonObject("tools").get("listChanged").getAsBoolean());
    assertEquals("How to use", result.get("instructions").getAsString());
    McpProtocol.Initialized b = protocol.initialize(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}"));
    assertEquals(McpProtocol.VERSIONS.get(0), b.protocolVersion());
    assertEquals("agent", b.client());
  }

  @Test
  void initializeWithoutParamsIsInvalidParams() {
    McpProtocol.Initialized i = protocol.initialize(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"));
    assertFalse(i.ok());
    assertEquals(McpProtocol.INVALID_PARAMS, i.reply().getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void theClientNameIsCleanedAndShort() {
    assertEquals("a_b_c", McpProtocol.clientName("a<b>c"));
    assertEquals(64, McpProtocol.clientName("x".repeat(200)).length());
    assertEquals("agent", McpProtocol.clientName("  "));
    assertEquals("Test Agent 1.2", McpProtocol.clientName("Test Agent 1.2"));
  }

  @Test
  void aClientCannotTakeTheReservedSourceOfAfterGitsOwnResolution() {
    assertEquals("auto-agent", McpProtocol.clientName("auto"));
    assertEquals("auto-agent", McpProtocol.clientName(" AUTO "));
    assertEquals("auto-agent", McpProtocol.clientName("Auto"));
    assertEquals("autopilot", McpProtocol.clientName("autopilot"));
    McpProtocol.Initialized i = protocol.initialize(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"auto\"}}}"));
    assertEquals("auto-agent", i.client());
  }

  @Test
  void toolsListNamesEveryToolWithItsSchemaAndHints() {
    JsonObject r = protocol.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"), "test-agent");
    var tools = r.getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(3, tools.size());
    JsonObject echo = tools.get(0).getAsJsonObject();
    assertEquals("echo", echo.get("name").getAsString());
    assertEquals("object", echo.getAsJsonObject("inputSchema").get("type").getAsString());
    assertEquals("text", echo.getAsJsonObject("inputSchema").getAsJsonArray("required").get(0).getAsString());
    assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
    assertFalse(echo.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
  }

  @Test
  void aToolCallGivesTextAndStructuredContentAndIsLogged() {
    JsonObject r = call("echo", "{\"text\":\"secret content\",\"path\":\"src/A.java\"}").getAsJsonObject("result");
    assertFalse(r.get("isError").getAsBoolean());
    assertEquals("secret content", r.getAsJsonObject("structuredContent").get("echo").getAsString());
    assertEquals("test-agent", r.getAsJsonObject("structuredContent").get("client").getAsString());
    assertEquals(McpProtocol.GSON.toJson(r.getAsJsonObject("structuredContent")),
        r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    assertEquals(List.of("echo|src/A.java|ok"), calls);
    assertFalse(calls.get(0).contains("secret"), "contents never reach the log");
  }

  @Test
  void aToolErrorIsAResultWithIsError() {
    JsonObject r = call("fail", "{}").getAsJsonObject("result");
    assertTrue(r.get("isError").getAsBoolean());
    assertEquals("it failed on purpose", r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    assertEquals(List.of("fail|-|refused: it failed on purpose"), calls);
  }

  @Test
  void aBugInAToolIsAnErrorResultAndReported() {
    JsonObject r = call("boom", null).getAsJsonObject("result");
    assertTrue(r.get("isError").getAsBoolean());
    assertTrue(r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
        .contains("IllegalStateException"));
    assertEquals(1, failures.size());
    assertEquals(List.of("boom|-|failed"), calls);
  }

  @Test
  void badArgumentsAreToolErrorsTheAgentCanFix() {
    JsonObject missing = call("echo", "{}").getAsJsonObject("result");
    assertTrue(missing.get("isError").getAsBoolean());
    JsonObject wrongType = call("echo", "{\"text\":3}").getAsJsonObject("result");
    assertTrue(wrongType.get("isError").getAsBoolean());
    JsonObject unknown = call("echo", "{\"text\":\"a\",\"extra\":1}").getAsJsonObject("result");
    assertTrue(unknown.get("isError").getAsBoolean());
  }

  @Test
  void protocolMistakesAreJsonRpcErrors() {
    assertEquals(McpProtocol.INVALID_PARAMS, call("nope", "{}").getAsJsonObject("error").get("code").getAsInt());
    assertEquals(McpProtocol.INVALID_PARAMS, call("echo", "[1]").getAsJsonObject("error").get("code").getAsInt());
    assertEquals(McpProtocol.METHOD_NOT_FOUND, protocol.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":3,"
        + "\"method\":\"resources/list\"}"), "a").getAsJsonObject("error").get("code").getAsInt());
    assertEquals("{}", protocol.dispatch(json("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}"), "a")
        .getAsJsonObject("result").toString());
  }

  @Test
  void invalidMessagesAreNamed() {
    assertNotNull(McpProtocol.invalid(json("{\"id\":1,\"method\":\"ping\"}")));
    assertNotNull(McpProtocol.invalid(json("{\"jsonrpc\":\"2.0\",\"id\":1}")));
    assertNotNull(McpProtocol.invalid(json("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}")));
    assertNull(McpProtocol.invalid(json("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"ping\"}")));
    assertNull(McpProtocol.invalid(json("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
  }

  @Test
  void logEntriesHaveNoControlCharactersAndAreShort() {
    call("echo", "{\"text\":\"a\",\"path\":\"src/A.java\\nINFO forged entry\\u0000\"}");
    call("echo", "{\"text\":\"a\",\"x\\r\\nINFO forged\":1}");
    call("echo", "{\"text\":\"a\",\"" + "k".repeat(100_000) + "\":1}");
    call("echo", "{\"text\":\"a\",\"path\":\"" + "p".repeat(5000) + "\"}");
    assertEquals(4, calls.size());
    for (String entry : calls) {
      assertTrue(entry.chars().noneMatch(ch -> Character.isISOControl(ch) || ch == 0x2028 || ch == 0x2029), entry);
      assertTrue(entry.length() <= 1000, "length " + entry.length());
    }
    assertTrue(calls.get(0).startsWith("echo|src/A.java INFO forged entry"), calls.get(0));
    assertTrue(calls.get(1).contains("refused: Unknown argument"), calls.get(1));
  }
}
