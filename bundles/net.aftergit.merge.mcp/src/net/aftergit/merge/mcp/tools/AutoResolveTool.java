package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** auto_resolve (agents design §2): AfterGit's automatic passes, recorded as proposals of source "auto". */
public final class AutoResolveTool implements Tool {
  private final ConflictResolverService service;

  public AutoResolveTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "auto_resolve";
  }

  @Override
  public String description() {
    return "Runs AfterGit's own automatic resolution on one conflicted text file. Every open conflict it can "
        + "resolve and that has no proposal yet becomes a proposal of source \"auto\" for the user to review "
        + "(applied at once in an open editor). Returns the ids of those blocks and how many open conflicts have no "
        + "proposal yet. Nothing is saved.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {{"path", "Absolute path, or path inside its repository, from list_conflicts"}},
        "path");
  }

  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "path");
    String path = Args.string(arguments, "path");
    ConflictResolverService.AutoResolved r;
    try {
      r = service.autoResolve(path);
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    JsonObject out = new JsonObject();
    out.addProperty("path", path);
    JsonArray ids = new JsonArray();
    for (BlockId id : r.proposed()) {
      ids.add(id.text());
    }
    out.add("proposed", ids);
    out.addProperty("remaining", r.remaining());
    return out;
  }
}
