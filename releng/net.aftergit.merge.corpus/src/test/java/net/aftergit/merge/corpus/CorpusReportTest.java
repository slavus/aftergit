package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusReportTest {
  @TempDir
  Path out;

  private static FileResult result(int deviations) {
    return new FileResult("r", "abc", "x/A.java", 2, 1, 1, false, deviations, deviations == 0 ? null : "ours [x] vs git [y]",
        false, false, false, false, null);
  }

  @Test
  void aDeviationFailsTheCorpus() throws Exception {
    CorpusStats s = new CorpusStats();
    s.add(result(1));
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(result(1)), List.of()));
    assertFalse(report.passed());
    report.write(out);
    String md = Files.readString(out.resolve("report.md"));
    assertTrue(md.contains("x/A.java") && md.contains("ours [x] vs git [y]"), md);
    assertTrue(Files.readString(out.resolve("report.json")).contains("\"passed\":false"));
  }

  @Test
  void skippedRepositoriesKeepTheirNote() {
    CorpusReport report = new CorpusReport();
    report.skip("jgit", "not cached");
    assertTrue(report.passed());
    assertTrue(report.markdown().contains("not cached"));
    assertTrue(report.json().contains("\"note\":\"not cached\""));
  }

  @Test
  void percentages() {
    assertEquals("n/a", CorpusStats.percent(0, 0));
    assertEquals("25.0 %", CorpusStats.percent(1, 4));
  }

  @Test
  void aFileErrorFailsTheCorpus() {
    CorpusStats s = new CorpusStats();
    FileResult failed = FileResult.failed("r", "abc", "x/A.java", "boom");
    s.add(failed);
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(), List.of(failed)));
    assertFalse(report.passed());
    assertTrue(report.markdown().contains("x/A.java: boom"));
    assertTrue(report.json().contains("\"errors\":1"));
  }

  @Test
  void checkerFailuresAreCounted() {
    CorpusStats s = new CorpusStats();
    s.add(new FileResult("r", "abc", "A.java", 1, 1, 0, false, 0, null, false, false, false, false, null, true));
    assertEquals(1, s.javaFailures);
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(), List.of()));
    assertTrue(report.json().contains("\"javaFailures\":1"));
    assertFalse(report.passed());
  }

  @Test
  void aMergeGitCouldNotReplayFailsTheRun() {
    CorpusStats s = new CorpusStats();
    s.failedMerges = 1;
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(), List.of()));
    assertFalse(report.passed());
  }

  @Test
  void theReportCountsWhatEachResolverResolved() {
    CorpusStats s = new CorpusStats();
    s.add(new FileResult("r", "abc", "x/M.java", 2, 2, 0, false, 0, null, true, false, false, false, null, false,
        Map.of("members", 1, "simple", 1)));
    s.add(new FileResult("r", "abd", "x/a.properties", 1, 1, 0, false, 0, null, false, false, false, false, null, false,
        Map.of("properties", 1)));
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(), List.of()));
    String md = report.markdown();
    assertTrue(md.contains("## Resolvers"), md);
    assertTrue(md.contains("| members | 1 | 1 |"), md);
    assertTrue(md.contains("| properties | 1 | 0 |"), md);
    assertTrue(md.contains("| simple | 1 | 1 |"), md);
    assertTrue(report.json().contains("\"resolvedBy\":{\"members\":1,\"properties\":1,\"simple\":1}"), report.json());
    assertTrue(report.json().contains("\"notCompilingBy\":{\"members\":1,\"simple\":1}"), report.json());
  }

  @Test
  void aMadeUpTokenFailsTheCorpus() {
    FileResult r = new FileResult("r", "abc", "pom.xml", 1, 1, 0, false, 0, null, false, false, false, false, null,
        false, Map.of("simple", 1), 1, "'6.7.1' in none of the sides");
    CorpusStats s = new CorpusStats();
    s.add(r);
    assertEquals(1, s.madeUpTokens);
    CorpusReport report = new CorpusReport();
    report.add(new CorpusReport.Section("r", "", s, List.of(r), List.of()));
    assertFalse(report.passed());
    String md = report.markdown();
    assertTrue(md.contains("| Made-up tokens |"), md);
    assertTrue(md.contains("pom.xml: made-up token '6.7.1' in none of the sides"), md);
    assertTrue(report.json().contains("\"madeUpTokens\":1"), report.json());
  }
}
