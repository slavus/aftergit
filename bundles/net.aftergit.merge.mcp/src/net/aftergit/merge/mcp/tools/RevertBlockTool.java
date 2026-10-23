package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonObject;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** revert_block (agents design §2): takes the agent's proposal for a block back. */
public final class RevertBlockTool implements Tool {
  private final ConflictResolverService service;

  public RevertBlockTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "revert_block";
  }

  @Override
  public String description() {
    return "Removes your own stored proposal of one block. In an open AfterGit editor a waiting proposal disappears "
        + "and an applied one that the user has neither changed nor reviewed becomes the conflict again. "
        + "removed=false when the block has no proposal of yours (proposals of other agents and AfterGit's own "
        + "\"auto\" ones stay). When the editor is busy nothing changes and the error asks you to try again.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {
        {"path", "Absolute path, or path inside its repository, from list_conflicts"},
        {"block", "Block id from get_conflict"}}, "path", "block");
  }

  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "path", "block");
    String path = Args.string(arguments, "path");
    ConflictResolverService.Reverted r;
    try {
      r = service.revert(path, Args.string(arguments, "block"), context.client());
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    JsonObject out = new JsonObject();
    out.addProperty("path", path);
    out.addProperty("block", r.block().text());
    out.addProperty("removed", r.removed());
    out.addProperty("reverted", r.reverted());
    return out;
  }
}
