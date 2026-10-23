package net.aftergit.merge.mcp.ui;

import static org.junit.jupiter.api.Assertions.*;
import static net.aftergit.merge.mcp.ui.RawMcp.args;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.ConnectException;
import java.nio.file.Files;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.mcp.AgentServer;
import net.aftergit.merge.mcp.McpPreferences;
import net.aftergit.merge.mcp.TokenStore;
import net.aftergit.merge.mcp.service.ProposalStore;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.ui.MergeEditorInput;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.FrameworkUtil;

/** Agents design §3, §5: an agent's calls reach the real AfterGit editor through the real server. */
class AgentsEndToEndTest {
  @TempDir
  File root;
  File dir;
  Repository repo;
  IProject project;
  IFile file;
  AgentServer server;

  @BeforeEach
  void setUp() throws Exception {
    dir = new File(root, "repo");
    repo = UiRepos.conflict(dir, "a.txt", UiRepos.BASE, UiRepos.OURS, UiRepos.THEIRS);
    project = UiRepos.project("agents-end-to-end", dir);
    file = project.getFile("a.txt");
    McpPreferences.setEnabled(false);
    McpPreferences.setPort(0);
    server = new AgentServer(TokenStore.memory(), FrameworkUtil.getBundle(AgentsEndToEndTest.class)
        .getBundleContext(), McpPreferences.node());
    AgentServer.setInstanceForTests(server);
    server.follow();
  }

  @AfterEach
  void tearDown() throws Exception {
    McpPreferences.setEnabled(false);
    server.unfollow();
    AgentServer.setInstanceForTests(null);
    UiRepos.cleanUp(dir, repo, project);
  }

  RawMcp client() {
    McpPreferences.setEnabled(true);
    UiRepos.waitUntil(server::running, 10, "the server");
    RawMcp c = new RawMcp(server.url(), server.token());
    UiRepos.offUi(c::initialize);
    return c;
  }

  static JsonObject structured(JsonObject result) {
    assertFalse(result.get("isError").getAsBoolean(), result.toString());
    return result.getAsJsonObject("structuredContent");
  }

  String path() {
    return file.getLocation().toOSString();
  }

  String firstBlock(RawMcp c) {
    return structured(UiRepos.offUi(() -> c.tool("get_conflict", args("path", path())))).getAsJsonArray("blocks")
        .get(0).getAsJsonObject().get("id").getAsString();
  }

  JsonObject propose(RawMcp c, String block, String text) {
    return structured(UiRepos.offUi(() -> c.tool("propose_resolution", args("path", path(), "block", block,
        "text", text, "rationale", "both lines"))));
  }

  @Test
  void aProposalReachesTheOpenResolverAndRevertBlockTakesItBack() {
    MergeEditorInput input = UiRepos.open(file);
    RawMcp c = client();
    JsonObject got = structured(UiRepos.offUi(() -> c.tool("get_conflict", args("path", path()))));
    assertTrue(got.get("open").getAsBoolean());
    String block = got.getAsJsonArray("blocks").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals("applied", propose(c, block, "L1\nR1\n").get("status").getAsString());
    MergeBlock b = input.session().model().blocks().get(0);
    assertEquals(BlockState.AI_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals("both lines", b.rationale());
    assertEquals("ai:test-agent", b.resolvedBy());
    assertEquals("1 conflict \u00B7 1 AI-proposed (to review)", input.viewer().statusText());
    JsonObject reverted = structured(UiRepos.offUi(() -> c.tool("revert_block", args("path", path(), "block",
        block))));
    assertTrue(reverted.get("reverted").getAsBoolean());
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    input.viewer().takeBlock(0, ThreeSide.LEFT, false);
    assertEquals("pending", propose(c, block, "L1\nR1\n").get("status").getAsString());
    assertEquals(BlockState.TAKEN_LEFT, input.session().model().blocks().get(0).state());
  }

  @Test
  void aStoredProposalIsAppliedWhenTheFileOpensLater() {
    RawMcp c = client();
    String block = firstBlock(c);
    assertEquals("recorded", propose(c, block, "L1\nR1\n").get("status").getAsString());
    MergeEditorInput input = UiRepos.open(file);
    UiRepos.waitUntil(() -> input.session().model().blocks().get(0).state() == BlockState.AI_RESOLVED, 10,
        "the stored proposal");
  }

  @Test
  void aStoredProposalOfAnEarlierMergeIsNotApplied() throws Exception {
    RawMcp c = client();
    propose(c, firstBlock(c), "L1\nR1\n");
    RepoFile rf = new RepoFile(repo, "a.txt", new File(dir, "a.txt"));
    assertTrue(Files.exists(ProposalStore.path(rf)));
    UiRepos.remerge(repo, "a.txt", "a\nR9\nc\nR3\ne\n");
    file.refreshLocal(0, null);
    MergeEditorInput input = UiRepos.open(file);
    UiRepos.flush();
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    assertFalse(Files.exists(ProposalStore.path(rf)), "the stale proposals are deleted");
  }

  @Test
  void aBlockRevertedWhileAgentsAreOffStaysAConflictWhenTheyComeBack() {
    MergeEditorInput input = UiRepos.open(file);
    RawMcp c = client();
    String block = firstBlock(c);
    assertEquals("applied", propose(c, block, "L1\nR1\n").get("status").getAsString());
    RepoFile rf = new RepoFile(repo, "a.txt", new File(dir, "a.txt"));
    McpPreferences.setEnabled(false);
    UiRepos.flush(); // the resolver hears that agents went off
    assertTrue(Files.exists(ProposalStore.path(rf)), "the stored proposal stays while agents are off");
    input.viewer().revertBlock(0);
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    McpPreferences.setEnabled(true);
    UiRepos.waitUntil(server::running, 10, "the server");
    UiRepos.waitUntil(() -> !Files.exists(ProposalStore.path(rf)), 10, "the store to forget the reverted proposal");
    UiRepos.flush();
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state(), "the revert stands");
  }

  @Test
  void anUndoneProposalOnlyWaitsWhenTheFileOpensAgain() {
    MergeEditorInput input = UiRepos.open(file);
    RawMcp c = client();
    String block = firstBlock(c);
    assertEquals("applied", propose(c, block, "L1\nR1\n").get("status").getAsString());
    input.viewer().undo();
    assertEquals(BlockState.UNRESOLVED, input.session().model().blocks().get(0).state());
    UiRepos.page().closeAllEditors(false);
    UiRepos.flush();
    MergeEditorInput again = UiRepos.open(file);
    UiRepos.flush();
    assertEquals(BlockState.UNRESOLVED, again.session().model().blocks().get(0).state(), "the undo stands");
    RepoFile rf = new RepoFile(repo, "a.txt", new File(dir, "a.txt"));
    assertTrue(Files.exists(ProposalStore.path(rf)), "the proposal still waits for the user");
  }

  @Test
  void saveAndMarkResolvedDeletesTheStoredProposalsOfTheFile() {
    MergeEditorInput input = UiRepos.open(file);
    RawMcp c = client();
    assertEquals("applied", propose(c, firstBlock(c), "L1\nR1\n").get("status").getAsString());
    RepoFile rf = new RepoFile(repo, "a.txt", new File(dir, "a.txt"));
    assertTrue(Files.exists(ProposalStore.path(rf)));
    input.viewer().takeBlock(1, ThreeSide.RIGHT, false);
    assertEquals(MergeEditorInput.FinishOutcome.RESOLVED,
        input.saveAndMarkResolved(new org.eclipse.core.runtime.NullProgressMonitor()));
    assertFalse(Files.exists(ProposalStore.path(rf)));
  }

  @Test
  void switchingOffWithAnOpenResolverStopsTheServer() {
    MergeEditorInput input = UiRepos.open(file);
    RawMcp c = client();
    String url = server.url();
    String token = server.token();
    JsonObject got = structured(UiRepos.offUi(() -> c.tool("get_conflict", args("path", path()))));
    String second = got.getAsJsonArray("blocks").get(1).getAsJsonObject().get("id").getAsString();
    assertEquals("applied", propose(c, second, "L3\nR3\n").get("status").getAsString());
    McpPreferences.setEnabled(false);
    assertFalse(server.running());
    assertNull(server.token());
    assertInstanceOf(ConnectException.class, UiRepos.offUiOrException(() -> new RawMcp(url, token).post("{}")));
    assertEquals(BlockState.AI_RESOLVED, input.session().model().blocks().get(1).state(),
        "applied blocks stay; only the agents' traces go");
  }
}
