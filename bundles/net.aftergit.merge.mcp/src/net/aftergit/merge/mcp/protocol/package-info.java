/**
 * A small MCP server (agents design §2): JSON-RPC 2.0 over HTTP POST on {@code /mcp} of 127.0.0.1, plain JSON
 * replies, sessions, a bearer token, an Origin check and size limits. Depends on the JDK and Gson only, so it also
 * builds and is tested on a plain classpath (releng/net.aftergit.merge.mcp.conformance).
 */
package net.aftergit.merge.mcp.protocol;
