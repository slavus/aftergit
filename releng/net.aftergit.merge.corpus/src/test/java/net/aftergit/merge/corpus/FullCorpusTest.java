package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole corpus (spec §10.3): built-in scenarios and every cached repository; fails on any deviation from git. */
class FullCorpusTest {
  @TempDir
  Path scratch;

  @Test
  void corpus() throws Exception {
    CorpusRun.Config c = CorpusRun.Config.fromSystemProperties();
    CorpusReport report = CorpusRun.run(c, scratch);
    report.write(c.outDir());
    System.out.println(report.markdown());
    assertTrue(report.passed(), "the clean parts differ from git's; see " + c.outDir().resolve("report.md"));
  }
}
