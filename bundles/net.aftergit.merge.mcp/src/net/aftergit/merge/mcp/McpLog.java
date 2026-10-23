package net.aftergit.merge.mcp;

import net.aftergit.merge.mcp.protocol.CallLog;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/** Every tool call in the AfterGit log at INFO: tool, path, outcome; never contents or the token (agents §4). */
public final class McpLog implements CallLog {
  static final String PLUGIN_ID = "net.aftergit.merge.mcp";

  @Override
  public void call(String tool, String path, String outcome) {
    ILog.of(McpLog.class).log(Status.info("MCP " + tool + " " + path + ": " + outcome));
  }

  @Override
  public void failure(String tool, Throwable e) {
    ILog.of(McpLog.class).log(new Status(IStatus.ERROR, PLUGIN_ID, "The MCP tool " + tool + " failed", loggable(e)));
  }

  /**
   * {@code e} for a logged status, like the resolver's JavaBridge.loggable (this bundle cannot reach it): a
   * VirtualMachineError goes in as the cause of a wrapper, because the workbench treats a logged one as fatal.
   */
  public static Throwable loggable(Throwable e) {
    return e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e;
  }
}
