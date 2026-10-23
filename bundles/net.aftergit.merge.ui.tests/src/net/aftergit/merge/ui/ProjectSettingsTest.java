package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preferences design §6: project (while it has its own settings) → workspace → default. */
class ProjectSettingsTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
    MergePreferences.reset();
  }

  private IFile resolvable(String project) throws Exception {
    repos.conflict("a.txt", "x a z b y\n", "x A z b y\n", "x a z B y\n");
    return repos.plainProject(project, "a.txt");
  }

  @Test
  void withoutOwnSettingsAProjectFollowsTheWorkspace() throws Exception {
    IProject p = resolvable("ps-follow").getProject();
    assertFalse(ProjectSettings.enabled(p));
    assertFalse(MergePreferences.greedy(p));
    MergePreferences.setGreedy(true);
    assertTrue(MergePreferences.greedy(p));
    assertFalse(MergePreferences.ignoreWhitespace(p), "off by default");
    assertEquals(ComparisonPolicy.DEFAULT, MergePreferences.policy(p));
    MergePreferences.setIgnoreWhitespace(true);
    assertEquals(ComparisonPolicy.IGNORE_WHITESPACES, MergePreferences.policy(p));
  }

  @Test
  void aProjectsOwnValueWins() throws Exception {
    IProject p = resolvable("ps-own").getProject();
    MergePreferences.setGreedy(true);
    ProjectSettings.store(p, Map.of(MergePreferences.GREEDY, false));
    assertTrue(ProjectSettings.enabled(p));
    assertFalse(MergePreferences.greedy(p));
    assertTrue(MergePreferences.greedy(), "the workspace keeps its own value");
  }

  @Test
  void aKeyTheProjectDoesNotSetFallsBackToTheWorkspace() throws Exception {
    IProject p = resolvable("ps-fallback").getProject();
    MergePreferences.setResolverEnabled("x.later", false);
    ProjectSettings.store(p, Map.of(MergePreferences.GREEDY, true));
    assertFalse(MergePreferences.resolverEnabled(p, "x.later"), "the workspace value, not the default");
    assertTrue(MergePreferences.resolverEnabled(p, PropertiesResolver.ID), "the default where nobody set it");
  }

  @Test
  void personalSettingsStayInTheWorkspace() throws Exception {
    IProject p = resolvable("ps-personal").getProject();
    assertFalse(ProjectSettings.shapesResults(MergePreferences.INNER_LINE));
    assertFalse(ProjectSettings.shapesResults(MergePreferences.LAYOUT));
    ProjectSettings.store(p, Map.of(MergePreferences.INNER_LINE, false));
    assertFalse(ProjectSettings.enabled(p), "a personal key is never stored in the project");
    assertTrue(ProjectSettings.bool(p, MergePreferences.INNER_LINE, true));
  }

  @Test
  void theProjectsValuesLiveInItsSettingsFileAndStoringNothingRemovesThem() throws Exception {
    IProject p = resolvable("ps-file").getProject();
    ProjectSettings.store(p, Map.of(MergePreferences.GREEDY, true,
        MergePreferences.RESOLVER_PREFIX + ImportsResolver.ID, false));
    File prefs = p.getFile(".settings/net.aftergit.merge.ui.prefs").getLocation().toFile();
    String text = Files.readString(prefs.toPath(), StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("greedy=true"), text);
    assertTrue(text.contains("resolver.imports=false"), text);
    ProjectSettings.store(p, Map.of());
    assertFalse(ProjectSettings.enabled(p));
    assertTrue(!prefs.exists() || !Files.readString(prefs.toPath(), StandardCharsets.ISO_8859_1).contains("greedy"));
  }

  @Test
  void theEditorUsesTheSettingsOfTheFilesProject() throws Exception {
    IFile file = resolvable("ps-editor");
    ProjectSettings.store(file.getProject(), Map.of(MergePreferences.AUTO_RESOLVE_ON_OPEN, false));
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.CANCEL, false));
    assertEquals(1, input.session().model().unresolvedConflicts(), "the project resolves nothing on open");
    assertEquals(file.getProject(), input.viewer().project());
  }

  @Test
  void resolveAllTakesTheOptionsOfEachFilesProject() throws Exception {
    IFile file = resolvable("ps-resolve-all");
    MergePreferences.setIgnoreWhitespace(true);
    ProjectSettings.store(file.getProject(), Map.of(MergePreferences.GREEDY, true,
        MergePreferences.IGNORE_WHITESPACE, false));
    var options = ResolveAllJob.options(file);
    assertTrue(options.greedy());
    assertEquals(ComparisonPolicy.DEFAULT, options.policy());
    assertTrue(options.pipeline().forFile("a.txt").ids().contains("greedy"));
  }

  @Test
  void theAgentsBuildModelsWithTheSettingsOfTheFilesProject() throws Exception {
    repos.conflict("m.properties", "a=1\n", "a=2\n", "a=3\n");
    IFile file = repos.plainProject("ps-agents", "m.properties");
    ProjectSettings.store(file.getProject(), Map.of(MergePreferences.RESOLVER_PREFIX + PropertiesResolver.ID, false,
        MergePreferences.IGNORE_WHITESPACE, true));
    AgentBridge bridge = new AgentBridge();
    assertEquals(ComparisonPolicy.IGNORE_WHITESPACES, bridge.policy(file));
    assertEquals(ComparisonPolicy.DEFAULT, bridge.policy(), "the workspace");
    assertFalse(bridge.pipeline(file).forFile(file.getName()).ids().contains(PropertiesResolver.ID));
    assertTrue(bridge.pipeline().forFile(file.getName()).ids().contains(PropertiesResolver.ID), "the workspace");
  }
}
