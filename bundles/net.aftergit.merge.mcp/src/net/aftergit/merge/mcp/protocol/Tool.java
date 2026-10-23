package net.aftergit.merge.mcp.protocol;

import com.google.gson.JsonObject;

/** One MCP tool: name, description, the JSON schema of its arguments, and the call. */
public interface Tool {
  String name();

  String description();

  /** JSON schema of the arguments object. */
  JsonObject inputSchema();

  /** True when the tool changes nothing (annotation readOnlyHint of tools/list). */
  boolean readOnly();

  /**
   * Runs the tool. A {@link ToolException} becomes a result with isError that the agent reads; its message must not
   * contain file contents (it is logged).
   */
  JsonObject call(JsonObject arguments, CallContext context) throws ToolException;
}
