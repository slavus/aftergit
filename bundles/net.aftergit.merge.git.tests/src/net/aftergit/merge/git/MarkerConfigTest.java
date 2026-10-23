package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import net.aftergit.merge.core.model.MarkerStyle;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkerConfigTest {
  static final String DIFF3_W = "a\n<<<<<<< HEAD\nL\n||||||| 1234567\nb\n=======\nR\n>>>>>>> feature\nc\n";
  static final String MERGE_W = "a\n<<<<<<< HEAD\nL\n=======\nR\n>>>>>>> feature\nc\n";

  @TempDir
  File dir;
  Repository repo;

  SystemReader original;

  /** Hides the developer's user and system git configuration, which may set merge.conflictStyle. */
  @BeforeEach
  void isolateFromUserConfig() {
    original = TestRepos.isolateUserConfig(dir);
  }

  @AfterEach
  void closeRepository() {
    SystemReader.setInstance(original);
    if (repo != null) {
      repo.close();
    }
  }

  private void configure(String style) throws Exception {
    StoredConfig c = repo.getConfig();
    c.setString("merge", null, "conflictStyle", style);
    c.save();
  }

  @Test
  void styleFollowsTheConfigurationThenTheWorkingTree() {
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.style("diff3", false));
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.style("zdiff3", false));
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.style(" Diff3 ", false));
    assertEquals(MarkerStyle.MERGE, MarkerConfig.style("merge", true));
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.style(null, true));
    assertEquals(MarkerStyle.MERGE, MarkerConfig.style(null, false));
    assertEquals(MarkerStyle.MERGE, MarkerConfig.style("unknown", false));
  }

  @Test
  void labelsComeFromGitsMarkers() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MarkerConfig m = MarkerConfig.of(repo, DIFF3_W);
    assertEquals(new MarkerConfig(MarkerStyle.DIFF3, "HEAD", "1234567", "feature"), m);
  }

  @Test
  void configurationWinsOverTheWorkingTree() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    configure("merge");
    assertEquals(MarkerStyle.MERGE, MarkerConfig.of(repo, DIFF3_W).style());
    configure("diff3");
    MarkerConfig m = MarkerConfig.of(repo, MERGE_W);
    assertEquals(MarkerStyle.DIFF3, m.style());
    assertEquals("base", m.baseLabel(), "git's file has no base label to take");
  }

  @Test
  void zdiff3AndUnknownValuesThroughTheRepositoryConfiguration() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    configure("zdiff3");
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.of(repo, MERGE_W).style());
    configure("bogus");
    assertEquals(MarkerStyle.DIFF3, MarkerConfig.of(repo, DIFF3_W).style());
    assertEquals(MarkerStyle.MERGE, MarkerConfig.of(repo, MERGE_W).style());
  }

  @Test
  void crlfMarkerLinesGiveCleanLabels() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MarkerConfig m = MarkerConfig.of(repo, DIFF3_W.replace("\n", "\r\n"));
    assertEquals(new MarkerConfig(MarkerStyle.DIFF3, "HEAD", "1234567", "feature"), m);
  }

  @Test
  void withoutHunksTheDefaultLabelsApply() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    assertEquals(MarkerConfig.DEFAULT, MarkerConfig.of(repo, "a\nclean\n"));
    assertEquals(MarkerConfig.DEFAULT, MarkerConfig.of(repo, null));
  }

  @Test
  void bareMarkersGiveEmptyLabels() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    MarkerConfig m = MarkerConfig.of(repo, "<<<<<<<\nL\n|||||||\nb\n=======\nR\n>>>>>>>\n");
    assertEquals(new MarkerConfig(MarkerStyle.DIFF3, "", "", ""), m);
  }

  @Test
  void aBaseMarkerOutsideAHunkIsNoLabel() {
    assertNull(MarkerConfig.baseLabel(List.of("||||||| not a hunk", "x")));
    assertEquals("b1", MarkerConfig.baseLabel(List.of("<<<<<<< o", "x", "||||||| b1", "y", "=======", ">>>>>>> t")));
  }
}
