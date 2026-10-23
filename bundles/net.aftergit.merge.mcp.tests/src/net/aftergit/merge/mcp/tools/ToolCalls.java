package net.aftergit.merge.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;

/** Calls tools the way the protocol does, with JSON arguments. */
final class ToolCalls {
  private ToolCalls() {}

  static JsonObject call(Tool tool, String args) throws ToolException {
    return tool.call(JsonParser.parseString(args).getAsJsonObject(), new CallContext("test-agent"));
  }

  static String refused(Tool tool, String args) {
    return assertThrows(ToolException.class, () -> call(tool, args)).getMessage();
  }

  /** A JSON object of string arguments: name, value, name, value … */
  static String args(String... nameValue) {
    JsonObject o = new JsonObject();
    for (int i = 0; i < nameValue.length; i += 2) {
      o.addProperty(nameValue[i], nameValue[i + 1]);
    }
    return o.toString();
  }
}
