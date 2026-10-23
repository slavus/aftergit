package net.aftergit.merge.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static net.aftergit.merge.mcp.tools.ToolCalls.args;
import static net.aftergit.merge.mcp.tools.ToolCalls.call;
import static net.aftergit.merge.mcp.tools.ToolCalls.refused;

import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.mcp.McpRepos;
import net.aftergit.merge.mcp.protocol.CallContext;
import net.aftergit.merge.mcp.protocol.Tool;
import net.aftergit.merge.mcp.service.ConflictFiles;
import net.aftergit.merge.mcp.service.ConflictResolverService;
import net.aftergit.merge.mcp.service.FakeJavaChecks;
import net.aftergit.merge.mcp.service.FakeOpenModels;
import net.aftergit.merge.mcp.service.Fingerprint;
import net.aftergit.merge.mcp.service.ProposalStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Agents design §2–§5: proposals only; the agent never saves or stages. */
class WriteToolsTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
  @TempDir
  File root;
  File dir;
  SystemReader original;
  Repository repo;
  IProject project;
  ProposalStore store = new ProposalStore();
  final FakeOpenModels openModels = new FakeOpenModels();
  ConflictResolverService service;
  Tool propose;
  Tool revert;
  Tool auto;
  Tool get;

  @BeforeEach
  void setUp() throws Exception {
    dir = new File(root, "repo");
    original = McpRepos.isolateUserConfig(root);
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("src/a.txt", new String[] {McpRepos.BASE, McpRepos.OURS, McpRepos.THEIRS});
    files.put("src/b.txt", McpRepos.WORDS);
    files.put("src/m.properties", new String[] {"a=1\nb=2\n", "a=1\nb=2\nc=3\n", "a=1\nb=2\nd=4\n"});
    repo = McpRepos.conflicts(dir, files);
    project = McpRepos.project("write-tools", dir);
    newService();
  }

  void newService() {
    service = new ConflictResolverService(store, () -> openModels, Clock.fixed(T, ZoneOffset.UTC));
    propose = new ProposeResolutionTool(service);
    revert = new RevertBlockTool(service);
    auto = new AutoResolveTool(service);
    get = new GetConflictTool(service);
  }

  @AfterEach
  void tearDown() throws Exception {
    McpRepos.cleanUp(original, root, repo, project);
  }

  String path(String rel) {
    return new File(dir, rel).getAbsolutePath();
  }

  String id(String rel, int block) throws Exception {
    return call(get, args("path", path(rel))).getAsJsonArray("blocks").get(block).getAsJsonObject().get("id")
        .getAsString();
  }

  JsonObject proposeA(int block, String text) throws Exception {
    return call(propose, args("path", path("src/a.txt"), "block", id("src/a.txt", block), "text", text,
        "rationale", "keeps both"));
  }

  @Test
  void aProposalForAClosedFileIsRecordedAndSurvivesARestart() throws Exception {
    JsonObject out = proposeA(0, "L1\nR1");
    assertEquals("recorded", out.get("status").getAsString());
    assertFalse(out.get("staleDiscarded").getAsBoolean());
    assertEquals(id("src/a.txt", 0), out.get("block").getAsString());
    store = new ProposalStore();
    newService();
    JsonObject p = call(get, args("path", path("src/a.txt"))).getAsJsonArray("blocks").get(0).getAsJsonObject()
        .getAsJsonObject("proposal");
    assertEquals("L1\nR1\n", p.get("text").getAsString());
    assertEquals("keeps both", p.get("rationale").getAsString());
    assertEquals("test-agent", p.get("source").getAsString());
  }

  @Test
  void aProposalForAnUntouchedBlockOfAnOpenFileIsApplied() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    assertEquals("applied", proposeA(0, "L1\nR1\n").get("status").getAsString());
    assertEquals(BlockState.AI_RESOLVED, open.model.blocks().get(0).state());
    assertEquals("ai:test-agent", open.model.blocks().get(0).resolvedBy());
    assertTrue(open.model.blocks().get(0).needsReview());
  }

  @Test
  void aProposalTheOpenEditorTookCountsAsShownAndOneForAClosedFileDoesNot() throws Exception {
    proposeA(0, "L1\nR1\n");
    var file = ConflictFiles.resolve(path("src/a.txt"));
    assertEquals(java.util.Set.of(), store.load(file.repoFile(), Fingerprint.of(file.repoFile()), false).shown());
    openModels.open(file.file(), FakeOpenModels.modelOf(file));
    proposeA(1, "L3\nR3\n");
    assertEquals(java.util.Set.of(BlockId.parse(id("src/a.txt", 1))),
        new ProposalStore().load(file.repoFile(), Fingerprint.of(file.repoFile()), true).shown());
  }

  @Test
  void aProposalForATouchedBlockOfAnOpenFileWaits() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    MergeModel m = FakeOpenModels.modelOf(file);
    m.take(0, ThreeSide.LEFT);
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), m);
    assertEquals("pending", proposeA(0, "L1\nR1\n").get("status").getAsString());
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    assertEquals(1, open.pending.size());
  }

  @Test
  void badBlocksAndArgumentsAreRefused() throws Exception {
    assertTrue(refused(propose, args("path", path("src/a.txt"), "block", "0:000000000000", "text", "x",
        "rationale", "")).contains("get_conflict"));
    assertTrue(refused(propose, args("path", path("src/a.txt"), "block", "zero", "text", "x", "rationale", ""))
        .contains("2:5d41"));
    assertTrue(refused(propose, args("path", path("src/a.txt"), "block", id("src/a.txt", 0), "rationale", ""))
        .contains("\"text\" is required"));
    assertTrue(refused(propose, args("path", path("README"), "block", "0:000000000000", "text", "x",
        "rationale", "")).contains("not a conflicted file"));
  }

  @Test
  void aTooLongProposalIsRefused() throws Exception {
    String text = "x".repeat(ConflictResolverService.MAX_PROPOSAL_CHARS + 1);
    String message = refused(propose, args("path", path("src/a.txt"), "block", id("src/a.txt", 0), "text", text,
        "rationale", ""));
    assertTrue(message.contains("200000"), message);
    assertFalse(message.contains("xxxx"), "the text itself is never echoed");
  }

  @Test
  void theSourceAutoIsReservedForAfterGit() throws Exception {
    String block = id("src/a.txt", 0);
    assertThrows(net.aftergit.merge.mcp.service.AgentException.class,
        () -> service.propose(path("src/a.txt"), block, "L1\n", "", Proposal.AUTO));
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    assertTrue(store.load(rf, Fingerprint.of(rf), false).proposals().isEmpty());
  }

  @Test
  void theValidationOfTheBlockComesWithTheProposal() throws Exception {
    openModels.java = new FakeJavaChecks("BROKEN");
    JsonObject v = proposeA(1, "BROKEN\n").getAsJsonObject("validation");
    assertTrue(v.get("checked").getAsBoolean());
    assertEquals(1, v.getAsJsonArray("blocks").size());
    assertEquals(4, v.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonArray("newErrors").get(0)
        .getAsJsonObject().get("line").getAsInt());
    openModels.java = null;
    assertFalse(proposeA(0, "fine\n").getAsJsonObject("validation").get("checked").getAsBoolean());
  }

  @Test
  void revertRemovesTheProposalAndRevertsAnUnreviewedAppliedOne() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    String block = id("src/a.txt", 0);
    proposeA(0, "L1\nR1\n");
    JsonObject out = call(revert, args("path", path("src/a.txt"), "block", block));
    assertTrue(out.get("removed").getAsBoolean());
    assertTrue(out.get("reverted").getAsBoolean());
    assertEquals(BlockState.UNRESOLVED, open.model.blocks().get(0).state());
    JsonObject again = call(revert, args("path", path("src/a.txt"), "block", block));
    assertFalse(again.get("removed").getAsBoolean());
    assertFalse(again.get("reverted").getAsBoolean());
  }

  @Test
  void autoResolveRecordsAfterGitsOwnResolutionsAsAutoProposals() throws Exception {
    JsonObject out = call(auto, args("path", path("src/b.txt")));
    assertEquals(1, out.getAsJsonArray("proposed").size());
    assertEquals(0, out.get("remaining").getAsInt());
    JsonObject p = call(get, args("path", path("src/b.txt"))).getAsJsonArray("blocks").get(0).getAsJsonObject()
        .getAsJsonObject("proposal");
    assertEquals("x = 1; y = 2;\n", p.get("text").getAsString());
    assertEquals(Proposal.AUTO, p.get("source").getAsString());
    JsonObject none = call(auto, args("path", path("src/a.txt")));
    assertEquals(0, none.getAsJsonArray("proposed").size());
    assertEquals(2, none.get("remaining").getAsInt());
  }

  @Test
  void autoResolveRunsTheResolverOfTheFilesFormat() throws Exception {
    JsonObject out = call(auto, args("path", path("src/m.properties")));
    assertEquals(1, out.getAsJsonArray("proposed").size());
    Proposal p = stored("src/m.properties").get(0);
    assertEquals("c=3\nd=4\n", p.text());
    assertEquals("AfterGit's properties resolver", p.rationale());
  }

  @Test
  void autoResolveRunsOnlyTheResolversTheResolverHasOn() throws Exception {
    openModels.pipeline = ResolverPipeline.defaults(false, id -> !id.equals("properties"), List.of());
    JsonObject out = call(auto, args("path", path("src/m.properties")));
    assertEquals(0, out.getAsJsonArray("proposed").size());
    assertEquals(1, out.get("remaining").getAsInt());
  }

  @Test
  void autoResolveOfAnOpenFileOffersTheResolutionsToTheEditor() throws Exception {
    var file = ConflictFiles.resolve(path("src/b.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    call(auto, args("path", path("src/b.txt")));
    assertEquals(BlockState.AUTO_RESOLVED, open.model.blocks().get(0).state());
    assertEquals("auto", open.model.blocks().get(0).resolvedBy());
  }

  @Test
  void aProposalAfterTheConflictChangedDropsTheOldOnes() throws Exception {
    String second = id("src/a.txt", 1); // read before the change: get_conflict would report the stale ones itself
    proposeA(0, "old\n");
    // saved elsewhere with the first conflict resolved: the second is still a conflict, the fingerprint differs
    String marked = Files.readString(new File(dir, "src/a.txt").toPath());
    int end = marked.indexOf('\n', marked.indexOf(">>>>>>>")) + 1;
    McpRepos.write(dir, "src/a.txt", marked.substring(0, marked.indexOf("<<<<<<<")) + "L1\n" + marked.substring(end));
    JsonObject out = call(propose, args("path", path("src/a.txt"), "block", second, "text", "new\n",
        "rationale", ""));
    assertTrue(out.get("staleDiscarded").getAsBoolean());
    var rf = McpRepos.repoFile(repo, "src/a.txt");
    assertEquals(1, store.load(rf, Fingerprint.of(rf), false).proposals().size());
  }

  @Test
  void theToolsNeverSaveOrStage() throws Exception {
    byte[] a = Files.readAllBytes(new File(dir, "src/a.txt").toPath());
    byte[] b = Files.readAllBytes(new File(dir, "src/b.txt").toPath());
    byte[] index = Files.readAllBytes(new File(repo.getDirectory(), "index").toPath());
    proposeA(0, "L1\nR1\n");
    call(auto, args("path", path("src/b.txt")));
    call(revert, args("path", path("src/a.txt"), "block", id("src/a.txt", 0)));
    // the same with both files open in the editor
    var fa = ConflictFiles.resolve(path("src/a.txt"));
    var fb = ConflictFiles.resolve(path("src/b.txt"));
    openModels.open(fa.file(), FakeOpenModels.modelOf(fa));
    openModels.open(fb.file(), FakeOpenModels.modelOf(fb));
    proposeA(1, "L3\nR3\n");
    call(auto, args("path", path("src/b.txt")));
    call(revert, args("path", path("src/a.txt"), "block", id("src/a.txt", 1)));
    assertArrayEquals(a, Files.readAllBytes(new File(dir, "src/a.txt").toPath()));
    assertArrayEquals(b, Files.readAllBytes(new File(dir, "src/b.txt").toPath()));
    assertArrayEquals(index, Files.readAllBytes(new File(repo.getDirectory(), "index").toPath()));
    assertTrue(ConflictState.isConflicted(repo, "src/a.txt"));
    assertTrue(ConflictState.isConflicted(repo, "src/b.txt"));
    assertTrue(Git.wrap(repo).status().call().getConflicting().containsAll(List.of("src/a.txt", "src/b.txt")));
  }

  @Test
  void thereAreSixToolsAndNoneSavesStagesOrWrites() {
    List<String> names = ToolSet.create(service).stream().map(Tool::name).toList();
    assertEquals(List.of("list_conflicts", "get_conflict", "validate", "auto_resolve", "propose_resolution",
        "revert_block"), names);
    for (Tool t : ToolSet.create(service)) {
      assertFalse(t.name().matches(".*(save|stage|write|add|commit|read_file).*"), t.name());
    }
  }

  @Test
  void theIdOfABlockFromABuiltModelMatchesTheTools() throws Exception {
    MergeModel m = FakeOpenModels.modelOf(ConflictFiles.resolve(path("src/a.txt")));
    assertEquals(BlockId.of(m, 1).text(), id("src/a.txt", 1));
  }

  JsonObject proposeAs(String client, String rel, String block, String text) throws Exception {
    return propose.call(com.google.gson.JsonParser.parseString(args("path", path(rel), "block", block, "text", text,
        "rationale", "")).getAsJsonObject(), new CallContext(client));
  }

  List<Proposal> stored(String rel) throws Exception {
    var rf = McpRepos.repoFile(repo, rel);
    return store.load(rf, Fingerprint.of(rf), false).proposals();
  }

  @Test
  void theResolverGetsTheProposalsWhenTheirShownMarkCannotBeWritten() throws Exception {
    proposeA(0, "L1\nR1");
    File proposals = new File(repo.getDirectory(), "aftergit/proposals");
    List<File> dirs = new java.util.ArrayList<>();
    try (var walk = Files.walk(proposals.toPath())) {
      walk.map(java.nio.file.Path::toFile).filter(File::isDirectory).forEach(dirs::add);
    }
    try {
      dirs.forEach(d -> assertTrue(d.setWritable(false, false)));
      var source = new net.aftergit.merge.mcp.service.StoreProposalSource(store);
      var got = source.stored(ConflictFiles.resolve(path("src/a.txt")).file());
      assertEquals(List.of("L1\nR1\n"), got.proposals().stream().map(Proposal::text).toList());
    } finally {
      dirs.forEach(d -> d.setWritable(true, true));
    }
  }

  @Test
  void aRevertTheBusyEditorRefusesChangesNothingAndTheRetryWorks() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    MergeModel m = FakeOpenModels.modelOf(file);
    m.take(0, ThreeSide.LEFT);
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), m);
    String block = id("src/a.txt", 0);
    assertEquals("pending", proposeA(0, "L1\nR1\n").get("status").getAsString());
    open.busy = true;
    String message = refused(revert, args("path", path("src/a.txt"), "block", block));
    assertTrue(message.contains("revert_block again"), message);
    assertEquals(1, stored("src/a.txt").size());
    assertEquals(1, open.pending.size());
    open.busy = false;
    JsonObject out = call(revert, args("path", path("src/a.txt"), "block", block));
    assertTrue(out.get("removed").getAsBoolean());
    assertTrue(out.get("reverted").getAsBoolean());
    assertTrue(open.pending.isEmpty(), "the editor no longer holds it, so it is never applied");
    assertTrue(stored("src/a.txt").isEmpty());
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
  }

  @Test
  void revertTakesBackOnlyTheCallersOwnProposal() throws Exception {
    call(auto, args("path", path("src/b.txt")));
    String autoBlock = id("src/b.txt", 0);
    JsonObject none = call(revert, args("path", path("src/b.txt"), "block", autoBlock));
    assertFalse(none.get("removed").getAsBoolean());
    assertEquals(Proposal.AUTO, stored("src/b.txt").get(0).source());
    proposeAs("other-agent", "src/a.txt", id("src/a.txt", 0), "theirs\n");
    assertFalse(call(revert, args("path", path("src/a.txt"), "block", id("src/a.txt", 0))).get("removed")
        .getAsBoolean());
    assertEquals("other-agent", stored("src/a.txt").get(0).source());
  }

  @Test
  void aProposalWhoseEditorClosedMeanwhileIsRecorded() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    String block = id("src/a.txt", 0);
    open.beforeOffer = () -> {
      openModels.open.remove(file.file());
      open.gone = true;
    };
    JsonObject out = call(propose, args("path", path("src/a.txt"), "block", block, "text", "L1\n", "rationale", ""));
    assertEquals("recorded", out.get("status").getAsString());
    assertEquals(1, stored("src/a.txt").size());
  }

  @Test
  void aBusyEditorKeepsTheProposalStoredAndSaysSo() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    String block = id("src/a.txt", 0);
    open.busy = true;
    String message = refused(propose, args("path", path("src/a.txt"), "block", block, "text", "L1\n",
        "rationale", ""));
    assertTrue(message.contains("is stored"), message);
    assertEquals(1, stored("src/a.txt").size());
    open.busy = false;
    assertEquals("applied", call(propose, args("path", path("src/a.txt"), "block", block, "text", "L1\n",
        "rationale", "")).get("status").getAsString());
  }

  @Test
  void aBlockThatChangedInTheEditorRemovesTheJustStoredProposal() throws Exception {
    var file = ConflictFiles.resolve(path("src/a.txt"));
    FakeOpenModels.FakeOpenModel open = openModels.open(file.file(), FakeOpenModels.modelOf(file));
    String block = id("src/a.txt", 0);
    open.blockChanged = true;
    assertTrue(refused(propose, args("path", path("src/a.txt"), "block", block, "text", "L1\n", "rationale", ""))
        .contains("get_conflict"));
    assertTrue(stored("src/a.txt").isEmpty());
    var fb = ConflictFiles.resolve(path("src/b.txt"));
    FakeOpenModels.FakeOpenModel openB = openModels.open(fb.file(), FakeOpenModels.modelOf(fb));
    openB.blockChanged = true;
    assertEquals(0, call(auto, args("path", path("src/b.txt"))).getAsJsonArray("proposed").size());
    assertTrue(stored("src/b.txt").isEmpty());
  }

  @Test
  void autoResolveKeepsAnAgentsProposalOfAClosedFile() throws Exception {
    proposeAs("test-agent", "src/b.txt", id("src/b.txt", 0), "x = 9;\n");
    JsonObject out = call(auto, args("path", path("src/b.txt")));
    assertEquals(0, out.getAsJsonArray("proposed").size());
    assertEquals(0, out.get("remaining").getAsInt());
    assertEquals(List.of("test-agent"), stored("src/b.txt").stream().map(Proposal::source).toList());
    assertEquals("x = 9;\n", stored("src/b.txt").get(0).text());
  }

  @Test
  void revertAndAutoResolveRefuseBadArguments() throws Exception {
    assertTrue(refused(revert, args("path", path("src/a.txt"), "block", "zero")).contains("2:5d41"));
    assertTrue(refused(revert, args("path", path("README"), "block", "0:000000000000"))
        .contains("not a conflicted file"));
    assertTrue(refused(revert, args("path", path("src/a.txt"))).contains("\"block\" is required"));
    assertTrue(refused(auto, args("path", path("README"))).contains("not a conflicted file"));
    assertTrue(refused(auto, args("path", path("src/a.txt"), "block", "0:000000000000"))
        .contains("Unknown argument"));
  }

  @Test
  void autoResolveAsksForTheSettingsOfTheFile() throws Exception {
    List<IFile> asked = new ArrayList<>();
    openModels.pipelineFor = f -> {
      asked.add(f);
      return ResolverPipeline.defaults(false, id -> !id.equals("properties"), List.of());
    };
    openModels.policyFor = f -> {
      asked.add(f);
      return ComparisonPolicy.DEFAULT;
    };
    JsonObject out = call(auto, args("path", path("src/m.properties")));
    assertEquals(0, out.getAsJsonArray("proposed").size(), "the file's own switches apply");
    IFile file = ConflictFiles.resolve(path("src/m.properties")).file();
    assertTrue(asked.size() >= 2 && asked.stream().allMatch(file::equals), asked.toString());
  }
}
