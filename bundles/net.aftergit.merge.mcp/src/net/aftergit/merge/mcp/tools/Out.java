package net.aftergit.merge.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.mcp.service.ConflictFiles;

/** JSON pieces the tools share. Lines are 1-based for agents. */
final class Out {
  private Out() {}

  /** TAKEN_LEFT → "taken-left". */
  static String name(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  static JsonElement problems(List<LineProblem> list) {
    if (list == null) {
      return JsonNull.INSTANCE;
    }
    JsonArray a = new JsonArray();
    for (LineProblem p : list) {
      JsonObject o = new JsonObject();
      o.addProperty("line", p.line() + 1);
      o.addProperty("message", p.message());
      a.add(o);
    }
    return a;
  }

  static JsonObject proposal(Proposal p) {
    JsonObject o = new JsonObject();
    o.addProperty("text", p.text());
    o.addProperty("rationale", p.rationale());
    o.addProperty("source", p.source());
    o.addProperty("time", p.time().toString());
    return o;
  }

  static JsonObject file(ConflictFiles.ConflictFile f) {
    JsonObject o = new JsonObject();
    o.addProperty("path", f.absolutePath());
    o.addProperty("repositoryPath", f.repoFile().path());
    o.addProperty("repository", f.repositoryName());
    return o;
  }
}
