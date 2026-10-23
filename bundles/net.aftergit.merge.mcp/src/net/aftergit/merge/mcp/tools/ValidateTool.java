package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** validate (agents design §2): the compile result of the file with all proposals applied, errors per block. */
public final class ValidateTool implements Tool {
  private final ConflictResolverService service;

  public ValidateTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "validate";
  }

  @Override
  public String description() {
    return "Compiles the result of one conflicted file with every stored proposal in place (Java files with the "
        + "Eclipse Java tools; other files are not checked: checked=false). Returns the errors of the result "
        + "(lines from 1) and, per block, the errors it brings in that neither ours nor theirs has.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {{"path", "Absolute path, or path inside its repository, from list_conflicts"}},
        "path");
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "path");
    ConflictResolverService.Validation v;
    try {
      v = service.validate(Args.string(arguments, "path"));
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    return json(v);
  }

  static JsonObject json(ConflictResolverService.Validation v) {
    JsonObject out = new JsonObject();
    out.addProperty("checked", v.checked());
    if (v.checked()) {
      out.addProperty("compiles", v.errors().isEmpty());
    } else {
      out.add("compiles", JsonNull.INSTANCE);
    }
    out.add("errors", Out.problems(v.errors()));
    JsonArray blocks = new JsonArray();
    for (Map.Entry<BlockId, List<LineProblem>> e : v.newErrors().entrySet()) {
      JsonObject b = new JsonObject();
      b.addProperty("id", e.getKey().text());
      b.add("newErrors", Out.problems(e.getValue()));
      blocks.add(b);
    }
    out.add("blocks", blocks);
    return out;
  }
}
