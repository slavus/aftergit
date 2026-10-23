package net.aftergit.merge.mcp.protocol;

import com.google.gson.JsonObject;
import java.util.List;

/** Small tools for the protocol tests: echo (read only), fail (a tool error), boom (a bug). */
final class EchoTools {
  private EchoTools() {}

  static List<Tool> all() {
    return List.of(echo(), fail());
  }

  static Tool echo() {
    return tool("echo", true, (args, ctx) -> {
      Args.only(args, "text", "path");
      JsonObject out = new JsonObject();
      out.addProperty("echo", Args.string(args, "text"));
      out.addProperty("client", ctx.client());
      return out;
    }, new String[][] {{"text", "What to echo"}, {"path", "A path for the log"}}, "text");
  }

  static Tool fail() {
    return tool("fail", false, (args, ctx) -> {
      throw new ToolException("it failed on purpose");
    }, new String[][] {{"path", "A path for the log"}});
  }

  static Tool boom() {
    return tool("boom", false, (args, ctx) -> {
      throw new IllegalStateException("a bug");
    }, new String[][] {});
  }

  interface Body {
    JsonObject run(JsonObject args, CallContext ctx) throws ToolException;
  }

  static Tool tool(String name, boolean readOnly, Body body, String[][] properties, String... required) {
    return new Tool() {
      @Override public String name() { return name; }
      @Override public String description() { return "Test tool " + name; }
      @Override public JsonObject inputSchema() { return Args.schema(properties, required); }
      @Override public boolean readOnly() { return readOnly; }
      @Override public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
        return body.run(arguments, context);
      }
    };
  }
}
