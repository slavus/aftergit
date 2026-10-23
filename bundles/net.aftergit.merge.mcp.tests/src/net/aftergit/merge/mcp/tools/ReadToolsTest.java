package net.aftergit.merge.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static net.aftergit.merge.mcp.tools.ToolCalls.args;
import static net.aftergit.merge.mcp.tools.ToolCalls.call;
import static net.aftergit.merge.mcp.tools.ToolCalls.refused;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.mcp.McpRepos;
import net.aftergit.merge.mcp.service.ConflictFiles;
import net.aftergit.merge.mcp.service.ConflictResolverService;
import net.aftergit.merge.mcp.service.FakeJavaChecks;
import net.aftergit.merge.mcp.service.FakeOpenModels;
import net.aftergit.merge.mcp.service.Fingerprint;
import net.aftergit.merge.mcp.service.ProposalStore;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §2, §5: list_conflicts, get_conflict, validate. */
class ReadToolsTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
  @TempDir
  File root;
  File dir;
  SystemReader original;
  Repository repo;
  IProject project;
  final ProposalStore store = new ProposalStore();
  final FakeOpenModels openModels = new FakeOpenModels();
  ConflictResolverService service;

  @BeforeEach
  void setUp() throws Exception {
    dir = new File(root, "repo");
    original = McpRepos.isolateUserConfig(root);
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("src/a.txt", new String[] {McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS});
    files.put("src/b.txt", McpRepos.WORDS);
    files.put("plain.txt", new String[] {"p\n", "p\n", "p\n"});
    repo = McpRepos.conflicts(dir, files);
    project = McpRepos.project("read-tools", dir);
    service = new ConflictResolverService(store, () -> openModels, Clock.fixed(T, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() throws Exception {
    McpRepos.cleanUp(original, root, repo, project);
  }

  String path(String rel) {
    return new File(dir, rel).getAbsolutePath();
  }

  JsonObject block(JsonObject conflict, int i) {
    return conflict.getAsJsonArray("blocks").get(i).getAsJsonObject();
  }

  @Test
  void listConflictsShowsTheConflictedFilesOfWorkspaceRepositories() throws Exception {
    JsonObject out = call(new ListConflictsTool(service), "{}");
    JsonArray repos = out.getAsJsonArray("repositories");
    assertEquals(1, repos.size());
    JsonObject r = repos.get(0).getAsJsonObject();
    assertEquals("repo", r.get("name").getAsString());
    assertEquals(dir.getAbsolutePath(), r.get("path").getAsString());
    JsonArray files = r.getAsJsonArray("files");
    assertEquals(2, files.size());
    JsonObject a = files.get(0).getAsJsonObject();
    assertEquals(path("src/a.txt"), a.get("path").getAsString());
    assertEquals("src/a.txt", a.get("repositoryPath").getAsString());
    assertEquals("text", a.get("kind").getAsString());
    assertEquals(2, a.get("openBlocks").getAsInt());
    assertFalse(a.get("open").getAsBoolean());
    assertEquals(0, a.get("proposals").getAsInt());
    assertFalse(a.get("stale").getAsBoolean());
    assertFalse(a.get("tooLarge").getAsBoolean());
  }

  @Test
  void listConflictsFiltersByRepositoryAndRefusesAnUnknownOne() throws Exception {
    assertEquals(1, call(new ListConflictsTool(service), args("repository", "repo"))
        .getAsJsonArray("repositories").size());
    assertEquals(1, call(new ListConflictsTool(service), args("repository", dir.getAbsolutePath()))
        .getAsJsonArray("repositories").size());
    assertTrue(refused(new ListConflictsTool(service), args("repository", "nope")).contains("nope"));
  }

  @Test
  void getConflictReturnsTheThreeSidesTheResultAndContext() throws Exception {
    JsonObject out = call(new GetConflictTool(service), args("path", path("src/a.txt")));
    assertEquals("text", out.get("kind").getAsString());
    assertFalse(out.get("open").getAsBoolean());
    assertEquals(2, out.get("unresolved").getAsInt());
    assertEquals("UTF-8", out.get("encoding").getAsString());
    assertFalse(out.get("encodingFallback").getAsBoolean());
    assertEquals("lf", out.get("lineEnds").getAsString());
    JsonObject b = block(out, 0);
    assertTrue(b.get("id").getAsString().matches("0:[0-9a-f]{12}"));
    assertEquals("unresolved", b.get("state").getAsString());
    assertEquals("L1\n", b.get("ours").getAsString());
    assertEquals("b\n", b.get("base").getAsString());
    assertEquals("R1\n", b.get("theirs").getAsString());
    assertEquals("b\n", b.get("result").getAsString());
    assertEquals(2, b.getAsJsonObject("resultLines").get("start").getAsInt());
    assertEquals(2, b.getAsJsonObject("resultLines").get("end").getAsInt());
    assertEquals("a\n", b.get("before").getAsString());
    assertEquals("c\nd\ne\n", b.get("after").getAsString());
    assertTrue(b.get("proposal").isJsonNull());
    assertTrue(out.get("errors").isJsonNull(), "a text file has no compile check");
  }

  @Test
  void getConflictOfOneBlockAndOfARepositoryRelativePath() throws Exception {
    JsonObject all = call(new GetConflictTool(service), args("path", "src/a.txt"));
    String id = block(all, 1).get("id").getAsString();
    JsonObject one = call(new GetConflictTool(service), args("path", "src/a.txt", "block", id));
    assertEquals(1, one.getAsJsonArray("blocks").size());
    assertEquals(id, block(one, 0).get("id").getAsString());
    assertTrue(refused(new GetConflictTool(service), args("path", "src/a.txt", "block", "1:000000000000"))
        .contains("get_conflict"));
    assertTrue(refused(new GetConflictTool(service), args("path", "src/a.txt", "block", "x")).contains("2:5d41"));
  }

  @Test
  void aFileThatIsNotConflictedIsRefused() {
    for (String p : new String[] {path("plain.txt"), "/etc/passwd", "../repo/src/a.txt"}) {
      assertTrue(refused(new GetConflictTool(service), args("path", p)).contains("not a conflicted file"), p);
      assertTrue(refused(new ValidateTool(service), args("path", p)).contains("not a conflicted file"), p);
    }
    assertTrue(refused(new GetConflictTool(service), "{}").contains("\"path\" is required"));
    assertTrue(refused(new GetConflictTool(service), "{\"path\":3}").contains("must be a string"));
  }

  @Test
  void aModifyDeleteConflictIsRefusedWithItsKind() throws Exception {
    File other = new File(root, "md");
    Repository md = McpRepos.modifyDelete(other, "gone.txt", "x\n", "y\n");
    IProject p = McpRepos.project("read-tools-md", other);
    try {
      assertTrue(refused(new GetConflictTool(service), args("path", new File(other, "gone.txt").getAbsolutePath()))
          .contains("modify-delete"));
    } finally {
      p.delete(false, true, null);
      md.close();
    }
  }

  @Test
  void aFileOverTheLimitIsRefused() throws Exception {
    String big = "line of a big file\n".repeat(ConflictResolverService.MAX_FILE_BYTES / 19 + 10);
    McpRepos.write(dir, "src/a.txt", big + McpRepos.OURS);
    String message = refused(new GetConflictTool(service), args("path", path("src/a.txt")));
    assertTrue(message.contains("2 MiB"), message);
    JsonObject list = call(new ListConflictsTool(service), "{}");
    JsonObject a = list.getAsJsonArray("repositories").get(0).getAsJsonObject().getAsJsonArray("files").get(0)
        .getAsJsonObject();
    assertTrue(a.get("openBlocks").isJsonNull());
    assertTrue(a.get("tooLarge").getAsBoolean());
  }

  @Test
  void errorsArePerSideAndResultWhenTheFileHasJavaChecks() throws Exception {
    openModels.java = new FakeJavaChecks("L1");
    JsonObject errors = call(new GetConflictTool(service), args("path", path("src/a.txt"))).getAsJsonObject("errors");
    assertEquals(2, errors.getAsJsonArray("ours").get(0).getAsJsonObject().get("line").getAsInt());
    assertEquals("bad L1", errors.getAsJsonArray("ours").get(0).getAsJsonObject().get("message").getAsString());
    assertEquals(0, errors.getAsJsonArray("theirs").size());
    assertEquals(0, errors.getAsJsonArray("result").size());
  }

  @Test
  void validateWithoutACompileCheckSaysSo() throws Exception {
    JsonObject out = call(new ValidateTool(service), args("path", path("src/a.txt")));
    assertFalse(out.get("checked").getAsBoolean());
    assertTrue(out.get("compiles").isJsonNull());
  }

  @Test
  void validateAppliesTheStoredProposalsAndNamesTheBlockWithNewErrors() throws Exception {
    openModels.java = new FakeJavaChecks("BROKEN");
    MergeModel m = FakeOpenModels.modelOf(ConflictFiles.resolve(path("src/a.txt")));
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    store.put(rf, Fingerprint.of(rf), new Proposal(BlockId.of(m, 0), "BROKEN\n", "", "test-agent", T), false);
    JsonObject out = call(new ValidateTool(service), args("path", path("src/a.txt")));
    assertTrue(out.get("checked").getAsBoolean());
    assertFalse(out.get("compiles").getAsBoolean());
    JsonArray blocks = out.getAsJsonArray("blocks");
    assertEquals(BlockId.of(m, 0).text(), blocks.get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(2, blocks.get(0).getAsJsonObject().getAsJsonArray("newErrors").get(0).getAsJsonObject()
        .get("line").getAsInt());
    assertEquals(0, blocks.get(1).getAsJsonObject().getAsJsonArray("newErrors").size());
  }

  @Test
  void anOpenFileIsReadFromTheResolversModel() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    MergeModel m = FakeOpenModels.modelOf(file);
    m.take(0, ThreeSide.LEFT);
    openModels.open(file.file(), m);
    JsonObject out = call(new GetConflictTool(service), args("path", path("src/a.txt")));
    assertTrue(out.get("open").getAsBoolean());
    assertEquals("taken-left", block(out, 0).get("state").getAsString());
    assertEquals("L1\n", block(out, 0).get("result").getAsString());
    assertEquals(1, out.get("unresolved").getAsInt());
    JsonObject list = call(new ListConflictsTool(service), "{}");
    JsonObject a = list.getAsJsonArray("repositories").get(0).getAsJsonObject().getAsJsonArray("files").get(0)
        .getAsJsonObject();
    assertTrue(a.get("open").getAsBoolean());
    assertEquals(1, a.get("openBlocks").getAsInt());
  }

  @Test
  void storedProposalsAreShownAndStaleOnesReportedOnce() throws Exception {
    MergeModel m = FakeOpenModels.modelOf(ConflictFiles.resolve(path("src/a.txt")));
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    store.put(rf, Fingerprint.of(rf), new Proposal(BlockId.of(m, 1), "L3\nR3\n", "both", "test-agent", T), false);
    JsonObject p = block(call(new GetConflictTool(service), args("path", path("src/a.txt"))), 1)
        .getAsJsonObject("proposal");
    assertEquals("L3\nR3\n", p.get("text").getAsString());
    assertEquals("both", p.get("rationale").getAsString());
    assertEquals("test-agent", p.get("source").getAsString());
    assertEquals(T.toString(), p.get("time").getAsString());
    McpRepos.write(dir, "src/a.txt", "changed outside AfterGit\n" + McpRepos.OURS);
    assertTrue(call(new GetConflictTool(service), args("path", path("src/a.txt"))).get("stale").getAsBoolean());
    assertFalse(call(new GetConflictTool(service), args("path", path("src/a.txt"))).get("stale").getAsBoolean());
  }

  @Test
  void readingNeverWritesUnderTheGitDirectory() throws Exception {
    call(new ListConflictsTool(service), "{}");
    call(new GetConflictTool(service), args("path", path("src/a.txt")));
    call(new ValidateTool(service), args("path", path("src/b.txt")));
    assertFalse(Files.exists(new File(repo.getDirectory(), "aftergit").toPath()));
  }

  @Test
  void theReadToolsAreReadOnlyAndListed() {
    var tools = ToolSet.create(service);
    assertTrue(tools.stream().map(t -> t.name()).toList().containsAll(java.util.List.of("list_conflicts",
        "get_conflict", "validate")));
    for (var t : tools) {
      if (java.util.List.of("list_conflicts", "get_conflict", "validate").contains(t.name())) {
        assertTrue(t.readOnly(), t.name());
      }
      assertEquals("object", t.inputSchema().get("type").getAsString());
    }
  }

  /** A second repository with one conflicted file, as a project, for the time of {@code body}. */
  JsonObject inOther(String name, String path, String[] versions, ThrowingFunction<File, JsonObject> body)
      throws Exception {
    File other = new File(root, name);
    Repository r = McpRepos.conflict(other, path, versions[0], versions[1], versions[2]);
    IProject p = McpRepos.project("read-tools-" + name, other);
    try {
      return body.apply(new File(other, path));
    } finally {
      p.delete(false, true, null);
      r.close();
    }
  }

  interface ThrowingFunction<A, B> {
    B apply(A a) throws Exception;
  }

  /** The id of a file's first conflict block, as the resolver gives it. */
  static String firstBlock(File f) throws Exception {
    return BlockId.of(FakeOpenModels.modelOf(ConflictFiles.resolve(f.getAbsolutePath())), 0).text();
  }

  @Test
  void anOpenResolverThatDoesNotAnswerFailsOnlyItsOwnFile() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    openModels.open(file.file(), FakeOpenModels.modelOf(file)).gone = true;
    JsonArray files = call(new ListConflictsTool(service), "{}").getAsJsonArray("repositories").get(0)
        .getAsJsonObject().getAsJsonArray("files");
    JsonObject a = files.get(0).getAsJsonObject();
    assertTrue(a.get("open").getAsBoolean());
    assertTrue(a.get("openBlocks").isJsonNull());
    assertEquals(1, files.get(1).getAsJsonObject().get("openBlocks").getAsInt(), "src/b.txt is still counted");
    assertTrue(refused(new GetConflictTool(service), args("path", path("src/a.txt"))).contains("was closed"));
  }

  @Test
  void aStageOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
    String big = "x".repeat(ConflictResolverService.MAX_FILE_BYTES) + "\n";
    inOther("big", "big.txt", new String[] {"b\n", big, "R\n"}, f -> {
      McpRepos.write(f.getParentFile(), "big.txt", "small again\n");
      String message = refused(new GetConflictTool(service), args("path", f.getAbsolutePath()));
      assertTrue(message.contains("2 MiB"), message);
      assertTrue(refused(new ValidateTool(service), args("path", f.getAbsolutePath())).contains("2 MiB"));
      JsonObject listed = call(new ListConflictsTool(service), args("repository", "big"))
          .getAsJsonArray("repositories").get(0).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject();
      assertTrue(listed.get("tooLarge").getAsBoolean());
      assertTrue(listed.get("openBlocks").isJsonNull());
      return null;
    });
  }

  @Test
  void anAnswerOverTheLimitIsRefusedButOneBlockAtATimeIsRead() throws Exception {
    int n = 300_000;
    String base = "a\n" + "b".repeat(n) + "\nc\n" + "d".repeat(n) + "\ne\n";
    String ours = "a\n" + "L".repeat(n) + "\nc\n" + "M".repeat(n) + "\ne\n";
    String theirs = "a\n" + "R".repeat(n) + "\nc\n" + "S".repeat(n) + "\ne\n";
    inOther("wide", "wide.txt", new String[] {base, ours, theirs}, f -> {
      String message = refused(new GetConflictTool(service), args("path", f.getAbsolutePath()));
      assertTrue(message.contains("one block at a time"), message);
      JsonObject first = block(call(new GetConflictTool(service), args("path", f.getAbsolutePath(), "block",
          firstBlock(f))), 0);
      assertEquals("L".repeat(n) + "\n", first.get("ours").getAsString());
      return null;
    });
  }

  @Test
  void aCrlfFileIsReadWithLfAndSaysSo() throws Exception {
    JsonObject out = inOther("crlf", "w.txt", new String[] {"a\r\nb\r\nc\r\n", "a\r\nL\r\nc\r\n",
        "a\r\nR\r\nc\r\n"}, f -> call(new GetConflictTool(service), args("path", f.getAbsolutePath())));
    assertEquals("crlf", out.get("lineEnds").getAsString());
    assertEquals("L\n", block(out, 0).get("ours").getAsString());
    assertEquals("a\n", block(out, 0).get("before").getAsString());
  }

  @Test
  void bytesNotValidInTheFilesCharsetAreReadAsLatin1AndSaySo() throws Exception {
    JsonObject out = inOther("latin", "l.txt", new String[] {"a\nb\nc\n", "a\n\u00e9\nc\n", "a\nR\nc\n"}, f -> {
      ResourcesPlugin.getWorkspace().getRoot().getProject("read-tools-latin").getFile("l.txt")
          .setCharset("US-ASCII", null);
      return call(new GetConflictTool(service), args("path", f.getAbsolutePath()));
    });
    assertEquals("ISO-8859-1", out.get("encoding").getAsString());
    assertTrue(out.get("encodingFallback").getAsBoolean());
    assertEquals("\u00c3\u00a9\n", block(out, 0).get("ours").getAsString(), "each UTF-8 byte is one character");
  }

  @Test
  void theContextStopsAtTheEdgesOfTheFileAndAnEmptyResultHasAnEmptyRange() throws Exception {
    JsonObject edges = inOther("edges", "e.txt", new String[] {"a\nb\nc\n", "X\nb\nY\n", "Z\nb\nW\n"},
        f -> call(new GetConflictTool(service), args("path", f.getAbsolutePath())));
    assertEquals(2, edges.getAsJsonArray("blocks").size());
    assertEquals("", block(edges, 0).get("before").getAsString());
    assertEquals("b\nc\n", block(edges, 0).get("after").getAsString());
    assertEquals("a\nb\n", block(edges, 1).get("before").getAsString());
    assertEquals("", block(edges, 1).get("after").getAsString());
    assertEquals(1, block(edges, 0).getAsJsonObject("resultLines").get("start").getAsInt());
    assertEquals(1, block(edges, 0).getAsJsonObject("resultLines").get("end").getAsInt());
    JsonObject added = inOther("added", "n.txt", new String[] {"a\nc\n", "a\nL\nc\n", "a\nR\nc\n"},
        f -> call(new GetConflictTool(service), args("path", f.getAbsolutePath())));
    JsonObject b = block(added, 0);
    assertEquals("", b.get("result").getAsString());
    assertEquals(2, b.getAsJsonObject("resultLines").get("start").getAsInt());
    assertEquals(1, b.getAsJsonObject("resultLines").get("end").getAsInt());
    assertEquals("a\n", b.get("before").getAsString());
    assertEquals("c\n", b.get("after").getAsString());
  }

  @Test
  void aSymbolicLinkInTheWorkingTreeIsNotFollowed() throws Exception {
    File a = new File(dir, "src/a.txt");
    assertTrue(a.delete());
    try {
      Files.createSymbolicLink(a.toPath(), new File(dir, "plain.txt").toPath());
    } catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
    }
    String message = refused(new GetConflictTool(service), args("path", "src/a.txt"));
    assertTrue(message.contains("symbolic link"), message);
    assertTrue(refused(new ValidateTool(service), args("path", "src/a.txt")).contains("symbolic link"));
    JsonObject listed = call(new ListConflictsTool(service), "{}").getAsJsonArray("repositories").get(0)
        .getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject();
    assertTrue(listed.get("openBlocks").isJsonNull());
  }
}
