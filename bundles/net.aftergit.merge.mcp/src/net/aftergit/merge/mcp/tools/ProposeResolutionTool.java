package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonObject;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** propose_resolution (agents design §2, §3): a proposal for one block; the user reviews it in AfterGit. */
public final class ProposeResolutionTool implements Tool {
  private final ConflictResolverService service;

  public ProposeResolutionTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "propose_resolution";
  }

  @Override
  public String description() {
    return "Proposes the resolved text of one conflict block (the id from get_conflict) with a short rationale. "
        + "The user reviews every proposal in AfterGit: in an open editor it is applied at once to an untouched "
        + "conflict as an AI-resolved block to review (status \"applied\"), or waits in the editor (for the user, "
        + "or until a dialog closes) (\"pending\"); for a file that is not open it is stored and applied when the "
        + "user opens it (\"recorded\"). If the open editor cannot take it now, the proposal stays stored and the "
        + "error says so; calling again offers it again. Nothing is saved or staged. Returns the compile check of "
        + "the block.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {
        {"path", "Absolute path, or path inside its repository, from list_conflicts"},
        {"block", "Block id from get_conflict, e.g. \"2:5d41402abc4b\""},
        {"text", "The resolved text of the block: whole lines, at most 200000 characters (empty removes the lines)"},
        {"rationale", "Why this resolution, shown to the user next to the block"}}, "path", "block", "text",
        "rationale");
  }

  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "path", "block", "text", "rationale");
    String path = Args.string(arguments, "path");
    ConflictResolverService.Proposed p;
    try {
      p = service.propose(path, Args.string(arguments, "block"), Args.string(arguments, "text"),
          Args.string(arguments, "rationale"), context.client());
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    JsonObject out = new JsonObject();
    out.addProperty("path", path);
    out.addProperty("block", p.block().text());
    out.addProperty("status", p.status());
    out.addProperty("staleDiscarded", p.staleDiscarded());
    out.add("validation", ValidateTool.json(p.validation()));
    return out;
  }
}
