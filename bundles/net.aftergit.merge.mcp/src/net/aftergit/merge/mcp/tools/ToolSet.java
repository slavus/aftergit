package net.aftergit.merge.mcp.tools;

import java.util.List;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** The tools of the AfterGit MCP server (agents design §2). There is no tool that saves, stages or writes files. */
public final class ToolSet {
  private ToolSet() {}

  public static List<Tool> create(ConflictResolverService service) {
    return List.of(new ListConflictsTool(service), new GetConflictTool(service), new ValidateTool(service),
        new AutoResolveTool(service), new ProposeResolutionTool(service), new RevertBlockTool(service));
  }
}
