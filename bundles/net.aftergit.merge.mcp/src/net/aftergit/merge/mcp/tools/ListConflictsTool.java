package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;

/** list_conflicts (agents design §2): the conflicted files of the workspace repositories. */
public final class ListConflictsTool implements Tool {
  private final ConflictResolverService service;

  public ListConflictsTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "list_conflicts";
  }

  @Override
  public String description() {
    return "Lists the conflicted files of the git repositories that have projects in the Eclipse workspace, per "
        + "repository: absolute path, path in the repository, kind of conflict (text, modify-delete, "
        + "delete-modify, binary, submodule, symlink, rename), whether the file is open in AfterGit, its open "
        + "conflict blocks (null when unknown) and its stored proposals. Only text conflicts take proposals; a "
        + "file over 2 MiB (tooLarge) is listed but not read.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {{"repository",
        "Name or absolute path of one repository; all repositories when absent"}});
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "repository");
    String repository = Args.optionalString(arguments, "repository");
    Map<String, JsonObject> repos = new LinkedHashMap<>();
    try {
      for (ConflictResolverService.FileSummary s : service.listConflicts(repository)) {
        JsonObject r = repos.computeIfAbsent(s.file().repositoryPath(), k -> {
          JsonObject o = new JsonObject();
          o.addProperty("name", s.file().repositoryName());
          o.addProperty("path", k);
          o.add("files", new JsonArray());
          return o;
        });
        JsonObject f = Out.file(s.file());
        f.remove("repository");
        f.addProperty("kind", Out.name(s.file().kind()));
        f.addProperty("open", s.open());
        if (s.openBlocks() == null) {
          f.add("openBlocks", JsonNull.INSTANCE);
        } else {
          f.addProperty("openBlocks", s.openBlocks());
        }
        f.addProperty("proposals", s.proposals());
        f.addProperty("stale", s.stale());
        f.addProperty("tooLarge", s.tooLarge());
        r.getAsJsonArray("files").add(f);
      }
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    JsonArray list = new JsonArray();
    repos.values().forEach(list::add);
    JsonObject out = new JsonObject();
    out.add("repositories", list);
    return out;
  }
}
