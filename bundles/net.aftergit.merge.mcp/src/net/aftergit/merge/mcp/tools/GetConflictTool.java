package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.mcp.protocol.Args;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.protocol.ToolException;
import net.aftergit.merge.mcp.service.AgentException;
import net.aftergit.merge.mcp.service.ConflictResolverService;
import net.aftergit.merge.mcp.service.ModelView;

/** get_conflict (agents design §2): the blocks of one text conflict with their sides, result and context. */
public final class GetConflictTool implements Tool {
  static final int CONTEXT = 5;
  private final ConflictResolverService service;

  public GetConflictTool(ConflictResolverService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "get_conflict";
  }

  @Override
  public String description() {
    return "Reads the conflict blocks of one conflicted text file: for each block its id (pass it to "
        + "propose_resolution), state, ours, base and theirs text, the current result text and its lines (from 1, "
        + "end inclusive; end = start - 1 when the result of the block is empty), "
        + CONTEXT + " lines of context before and after, and a stored proposal. With a Java compile check, also the "
        + "compile errors of ours, theirs and the result (lines from 1). An open file is read from the AfterGit "
        + "editor, so it includes the user's edits. Texts always use \\n; lineEnds tells the file's own line "
        + "ends (lf, crlf, mixed) and encoding the charset the texts were read with (encodingFallback: some "
        + "version is not valid in the file's charset, so every byte was read as one ISO-8859-1 character). An "
        + "answer is at most 2 MiB; ask for one block at a time when the whole file is larger.";
  }

  @Override
  public JsonObject inputSchema() {
    return Args.schema(new String[][] {
        {"path", "Absolute path, or path inside its repository, from list_conflicts"},
        {"block", "Id of one block (\"2:5d41402abc4b\"); all blocks when absent"}}, "path");
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public JsonObject call(JsonObject arguments, CallContext context) throws ToolException {
    Args.only(arguments, "path", "block");
    String path = Args.string(arguments, "path");
    String block = Args.optionalString(arguments, "block");
    ConflictResolverService.FileView v;
    try {
      v = service.getConflict(path);
    } catch (AgentException e) {
      throw new ToolException(e.getMessage());
    }
    ModelView.Block only = null;
    if (block != null) {
      BlockId id;
      try {
        id = BlockId.parse(block);
      } catch (IllegalArgumentException e) {
        throw new ToolException(e.getMessage());
      }
      only = v.view().block(id).orElseThrow(() -> new ToolException("Block " + block + " is not a conflict of "
          + path + " (any more); call get_conflict without a block for the current ones"));
    }
    JsonObject out = Out.file(v.file());
    out.addProperty("kind", Out.name(v.file().kind()));
    out.addProperty("open", v.open());
    out.addProperty("stale", v.stale());
    out.addProperty("unresolved", v.view().unresolved());
    out.addProperty("encoding", v.format().encoding());
    out.addProperty("encodingFallback", v.format().encodingFallback());
    out.addProperty("lineEnds", v.format().lineEnds());
    List<String> lines = LineText.chunkLines(v.view().result());
    JsonArray blocks = new JsonArray();
    for (ModelView.Block b : v.view().blocks()) {
      if (only != null && !b.id().equals(only.id())) {
        continue;
      }
      JsonObject o = new JsonObject();
      o.addProperty("id", b.id().text());
      o.addProperty("state", Out.name(b.state()));
      o.addProperty("needsReview", b.needsReview());
      o.addProperty("ours", b.ours());
      o.addProperty("base", b.base());
      o.addProperty("theirs", b.theirs());
      o.addProperty("result", b.result());
      JsonObject range = new JsonObject();
      range.addProperty("start", b.resultStart() + 1);
      range.addProperty("end", b.resultEnd());
      o.add("resultLines", range);
      o.addProperty("before", join(lines, Math.max(0, b.resultStart() - CONTEXT), b.resultStart()));
      o.addProperty("after", join(lines, b.resultEnd(), Math.min(lines.size(), b.resultEnd() + CONTEXT)));
      o.add("proposal", JsonNull.INSTANCE);
      for (Proposal p : v.proposals()) {
        if (v.view().block(p.block()).map(x -> x.id().equals(b.id())).orElse(false)) {
          o.add("proposal", Out.proposal(p));
        }
      }
      blocks.add(o);
    }
    out.add("blocks", blocks);
    if (v.errors().known()) {
      JsonObject errors = new JsonObject();
      errors.add("ours", Out.problems(v.errors().ours()));
      errors.add("theirs", Out.problems(v.errors().theirs()));
      errors.add("result", Out.problems(v.errors().result()));
      out.add("errors", errors);
    } else {
      out.add("errors", JsonNull.INSTANCE);
    }
    if (ConflictResolverService.utf8Length(out.toString()) > ConflictResolverService.MAX_FILE_BYTES) {
      throw new ToolException(only == null
          ? "The conflict blocks of " + path + " are more than 2 MiB together; ask for one block at a time"
          : "Block " + block + " of " + path + " is more than 2 MiB; resolve it in AfterGit itself");
    }
    return out;
  }

  private static String join(List<String> lines, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }
}
