package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusRunTest {
  @TempDir
  Path tmp;

  private CorpusRun.Config config(Path corpusDir, Path list) {
    return new CorpusRun.Config(corpusDir, list, Path.of(System.getProperty("aftergit.corpus.calc")),
        tmp.resolve("out"), 50, 100, true);
  }

  @Test
  void readsTheRepositoryList() throws Exception {
    Path list = Files.writeString(tmp.resolve("repos.txt"),
        "# comment\n\njgit https://example.org/jgit.git full\nij https://example.org/ij.git blobless max=5 a/b c\n");
    List<CorpusRun.RepoSpec> specs = CorpusRun.readList(list, 200);
    assertEquals(2, specs.size());
    assertEquals(new CorpusRun.RepoSpec("jgit", "https://example.org/jgit.git", "full", List.of(), 200), specs.get(0));
    assertEquals(new CorpusRun.RepoSpec("ij", "https://example.org/ij.git", "blobless", List.of("a/b", "c"), 5),
        specs.get(1));
  }

  @Test
  void builtInScenariosRunWithoutACache() throws Exception {
    assumeTrue(GitCli.available(), "git not available");
    Path list = Files.writeString(tmp.resolve("repos.txt"), "jgit https://github.com/eclipse-jgit/jgit.git full\n");
    CorpusReport report = CorpusRun.run(config(Files.createDirectories(tmp.resolve("empty")), list), tmp);
    assertEquals(2, report.sections().size());
    CorpusReport.Section synthetic = report.sections().get(0);
    assertEquals("synthetic", synthetic.repo());
    CorpusStats s = synthetic.stats();
    assertEquals(2, s.merges);
    assertEquals(2, s.files);
    assertEquals(4, s.conflicts, "eseth 2 + Calc 2");
    assertEquals(3, s.autoResolved, "eseth 2 + Calc divide()");
    assertEquals(1, s.fullyResolved, "eseth");
    assertEquals(1, s.matchesCommit, "eseth keeps every change");
    assertEquals(0, s.deviations);
    CorpusReport.Section jgit = report.sections().get(1);
    assertEquals("jgit", jgit.repo());
    assertTrue(jgit.note().contains("releng/corpus.sh"), jgit.note());
    assertTrue(report.passed());
  }

  @Test
  void aCachedRepositoryIsReplayed() throws Exception {
    assumeTrue(GitCli.available(), "git not available");
    Path corpus = Files.createDirectories(tmp.resolve("corpus"));
    SyntheticRepos.create(corpus.resolve("demo.git"), List.of(new SyntheticRepos.Scenario("words", "src/W.java",
        "class W { int a = 0; int b = 0; }\n", "class W { int a = 1; int b = 0; }\n",
        "class W { int a = 0; int b = 2; }\n", "class W { int a = 1; int b = 2; }\n")));
    Path list = Files.writeString(tmp.resolve("repos.txt"), "demo file:///unused full\n");
    CorpusReport report = CorpusRun.run(config(corpus, list), tmp);
    CorpusStats s = report.sections().get(1).stats();
    assertEquals(1, s.files);
    assertEquals(1, s.fullyResolved);
    assertEquals(1, s.matchesCommit);
    if (javax.tools.ToolProvider.getSystemJavaCompiler() != null) {
      assertEquals(1, s.javaChecked);
      assertEquals(1, s.javaNoNewErrors);
    }
  }

  private static final String GIT_NEEDED = "git not available";

  @Test
  void aDeviationReachesTheReportAndFailsTheRun() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path repo = tmp.resolve("dev.git");
    SyntheticRepos.create(repo, List.of(SyntheticRepos.eseth()));
    CorpusRun.Evaluator fake = (r, m, p, b, o, t, w, c, j) -> new FileResult(r, m, p, 1, 1, 0, false, 3, "ours [x] vs git [y]",
        false, false, false, false, null);
    CorpusReport.Section s = CorpusRun.replay("dev", repo, List.of(), 10, config(tmp, tmp.resolve("l")), fake);
    assertEquals(1, s.deviating().size());
    assertEquals(3, s.stats().deviations);
    CorpusReport report = new CorpusReport();
    report.add(s);
    assertFalse(report.passed());
    assertTrue(report.markdown().contains("jabberwocky.txt: ours [x] vs git [y]"), report.markdown());
  }

  @Test
  void aFailingFileIsListedAndFailsTheRun() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path repo = tmp.resolve("err.git");
    SyntheticRepos.create(repo, List.of(SyntheticRepos.eseth()));
    CorpusRun.Evaluator fake = (r, m, p, b, o, t, w, c, j) -> FileResult.failed(r, m, p, "boom");
    CorpusReport.Section s = CorpusRun.replay("err", repo, List.of(), 10, config(tmp, tmp.resolve("l")), fake);
    assertEquals(1, s.stats().errors);
    assertEquals(1, s.failed().size());
    CorpusReport report = new CorpusReport();
    report.add(s);
    assertFalse(report.passed());
  }

  @Test
  void aBrokenCacheIsASectionNoteNotAnAbort() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path corpus = Files.createDirectories(tmp.resolve("corpus"));
    Files.createDirectories(corpus.resolve("broken.git"));
    SyntheticRepos.create(corpus.resolve("demo.git"), List.of(SyntheticRepos.eseth()));
    Path list = Files.writeString(tmp.resolve("repos.txt"), "broken file:///unused full\ndemo file:///unused full\n");
    CorpusReport report = CorpusRun.run(config(corpus, list), tmp);
    assertEquals(3, report.sections().size());
    CorpusReport.Section broken = report.sections().get(1);
    assertTrue(broken.note().contains("cannot read the cached repository"), broken.note());
    assertEquals(1, broken.stats().errors);
    assertEquals(1, report.sections().get(2).stats().files, "the next repository is still replayed");
    assertFalse(report.passed());
  }

  @Test
  void binaryConflictsAreSkippedAndTheFileLimitStopsTheReplay() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path repo = tmp.resolve("limit.git");
    SyntheticRepos.create(repo, List.of(
        new SyntheticRepos.Scenario("bin", "data.bin", "a\0b\n", "a\0c\n", "a\0d\n", "a\0e\n"),
        SyntheticRepos.eseth(), new SyntheticRepos.Scenario("two", "other.txt", SyntheticRepos.ESETH_BASE,
            SyntheticRepos.ESETH_OURS, SyntheticRepos.ESETH_THEIRS, SyntheticRepos.ESETH_MERGED)));
    CorpusReport.Section all = CorpusRun.replay("limit", repo, List.of(), 10, config(tmp, tmp.resolve("l")));
    assertEquals(1, all.stats().skippedFiles);
    assertEquals(2, all.stats().files);
    assertEquals("", all.note());
    CorpusRun.Config one = new CorpusRun.Config(tmp, tmp.resolve("l"), Path.of(System.getProperty("aftergit.corpus.calc")),
        tmp.resolve("out"), 50, 2, true);
    CorpusReport.Section cut = CorpusRun.replay("limit", repo, List.of(), 10, one);
    assertEquals("stopped after 2 files", cut.note());
    assertEquals(2, cut.stats().files + cut.stats().skippedFiles);
  }

  /** A blobless bare clone (file remote) of a repository with one conflicting merge. */
  private Path bloblessClone() throws Exception {
    Path src = tmp.resolve("src");
    SyntheticRepos.create(src, List.of(SyntheticRepos.eseth()));
    GitCli srcGit = new GitCli(src);
    srcGit.run("config", "uploadpack.allowFilter", "true");
    srcGit.run("config", "uploadpack.allowAnySHA1InWant", "true");
    Path corpus = Files.createDirectories(tmp.resolve("blobless-corpus"));
    new GitCli(tmp).run("clone", "-q", "--bare", "--filter=blob:none", src.toUri().toString(),
        corpus.resolve("lazy.git").toString());
    return corpus;
  }

  @Test
  void aBloblessCloneIsSkippedWithoutFetching() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path corpus = bloblessClone();
    Path list = Files.writeString(tmp.resolve("repos.txt"), "lazy file:///unused blobless\n");
    CorpusReport report = CorpusRun.run(config(corpus, list), tmp);
    CorpusReport.Section lazy = report.sections().get(1);
    assertEquals(0, lazy.stats().files);
    assertEquals(0, lazy.stats().failedMerges, "missing contents are skipped, not failed");
    assertTrue(lazy.note().contains("skipped"), lazy.note());
    assertTrue(report.passed());
  }

  @Test
  void lazyFetchingReplaysABloblessCloneWhenAsked() throws Exception {
    assumeTrue(GitCli.available(), GIT_NEEDED);
    Path corpus = bloblessClone();
    Path list = Files.writeString(tmp.resolve("repos.txt"), "lazy file:///unused blobless\n");
    CorpusRun.Config c = new CorpusRun.Config(corpus, list, Path.of(System.getProperty("aftergit.corpus.calc")),
        tmp.resolve("out"), 50, 100, true, true);
    assertEquals(1, CorpusRun.run(c, tmp).sections().get(1).stats().files);
  }
}
