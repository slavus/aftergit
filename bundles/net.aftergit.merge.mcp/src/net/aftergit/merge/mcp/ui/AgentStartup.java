package net.aftergit.merge.mcp.ui;

import net.aftergit.merge.mcp.AgentServer;
import org.eclipse.ui.IStartup;

/** Starts the MCP server at startup when the user allowed it; while not, nothing runs (agents design §1). */
public final class AgentStartup implements IStartup {
  @Override
  public void earlyStartup() {
    AgentServer.instance().follow();
  }
}
