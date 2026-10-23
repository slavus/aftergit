package net.aftergit.merge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import net.aftergit.merge.mcp.protocol.CallLog;
import net.aftergit.merge.mcp.protocol.McpHttpServer;
import net.aftergit.merge.mcp.protocol.McpProtocol;
import org.junit.jupiter.api.Test;

/** The JDK HTTP server and Gson resolve inside the OSGi runtime, not only on the plain classpath. */
class ProtocolInOsgiTest {
  @Test
  void theServerAnswersInsideOsgi() throws Exception {
    McpHttpServer server = new McpHttpServer(new McpProtocol("AfterGit", "0", "", List.of(), CallLog.NONE),
        () -> "t", 0);
    server.start();
    try {
      HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + server.port() + McpHttpServer.PATH))
          .header("Authorization", "Bearer t")
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
              + "\"params\":{}}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, r.statusCode());
      JsonObject reply = McpProtocol.GSON.fromJson(r.body(), JsonObject.class);
      assertEquals("AfterGit", reply.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());
    } finally {
      server.stop();
    }
  }
}
