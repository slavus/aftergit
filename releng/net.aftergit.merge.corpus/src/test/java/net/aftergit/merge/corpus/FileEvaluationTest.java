package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import org.junit.jupiter.api.Test;

class FileEvaluationTest {
  /** git merge-file's result for the eseth inputs: one hunk over all five lines. */
  private static final String ESETH_GIT = "<<<<<<< ours\n" + SyntheticRepos.ESETH_OURS + "=======\n"
      + SyntheticRepos.ESETH_THEIRS + ">>>>>>> theirs\n";

  @Test
  void esethIsFullyResolvedLikeTheArticleAsks() {
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "jabberwocky.txt", SyntheticRepos.ESETH_BASE,
        SyntheticRepos.ESETH_OURS, SyntheticRepos.ESETH_THEIRS, ESETH_GIT, SyntheticRepos.ESETH_MERGED, false);
    assertNull(r.error());
    assertFalse(r.fallback());
    assertEquals(2, r.conflicts());
    assertEquals(2, r.autoResolved());
    assertEquals(0, r.unresolved());
    assertTrue(r.fullyResolved());
    assertEquals(0, r.deviations(), r.firstDeviation());
    assertTrue(r.committedKnown());
    assertTrue(r.matchesCommit());
  }

  @Test
  void newerVersionWinsRunsOnlyWhenTheCorpusSwitchesItOn() {
    assertFalse(FileEvaluation.pipeline(false).forFile("pom.xml").ids().contains(NewerVersionResolver.ID),
        "off, as in the plugin");
    assertTrue(FileEvaluation.pipeline(true).forFile("pom.xml").ids().contains(NewerVersionResolver.ID));
    assertEquals(FileEvaluation.pipeline(false).forFile("MANIFEST.MF").ids().size() + 1,
        FileEvaluation.pipeline(true).forFile("MANIFEST.MF").ids().size(), "the switch adds only that resolver");
  }

  @Test
  void anOpenConflictIsNoFullResolution() {
    String w = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w,
        null, false);
    assertEquals(1, r.conflicts());
    assertEquals(0, r.autoResolved());
    assertFalse(r.fullyResolved());
    assertFalse(r.committedKnown());
    assertEquals(0, r.deviations());
  }

  @Test
  void aFileWithoutConflictBlocksIsNeitherResolvedNorKnownAgainstTheCommit() {
    String text = "class A { }\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "A.java", text, text, text, text, text, true);
    assertNull(r.error());
    assertEquals(0, r.conflicts());
    assertFalse(r.fullyResolved());
    assertFalse(r.committedKnown());
    assertFalse(r.matchesCommit());
    assertFalse(r.javaChecked());
  }

  @Test
  void anOpenConflictIsNotComparedWithTheCommit() {
    String w = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n", w,
        "a\nL\nc\n", false);
    assertEquals(1, r.unresolved());
    assertFalse(r.committedKnown());
    assertFalse(r.matchesCommit());
  }

  @Test
  void aFullyResolvedJavaFileIsCompileChecked() {
    org.junit.jupiter.api.Assumptions.assumeTrue(javax.tools.ToolProvider.getSystemJavaCompiler() != null);
    String base = "class W { int a = 0; int b = 0; }\n";
    String ours = "class W { int a = 1; int b = 0; }\n";
    String theirs = "class W { int a = 0; int b = 2; }\n";
    String merged = "class W { int a = 1; int b = 2; }\n";
    String working = "<<<<<<< ours\n" + ours + "=======\n" + theirs + ">>>>>>> theirs\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "src/W.java", base, ours, theirs, working, merged, true);
    assertTrue(r.fullyResolved());
    assertTrue(r.javaChecked());
    assertTrue(r.noNewErrors());
    assertFalse(r.javaFailed());
  }

  @Test
  void fieldsAddedOnBothSidesAreResolvedByTheMemberResolver() {
    String base = "class M {\n  int a;\n}\n";
    String ours = "class M {\n  int a;\n  int b;\n}\n";
    String theirs = "class M {\n  int a;\n  int c;\n}\n";
    String w = "class M {\n  int a;\n<<<<<<< ours\n  int b;\n=======\n  int c;\n>>>>>>> theirs\n}\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "src/M.java", base, ours, theirs, w, null, true);
    assertNull(r.error());
    assertTrue(r.fullyResolved());
    assertEquals(Map.of("members", 1), r.byResolver());
    assertEquals(0, r.deviations(), r.firstDeviation());
    assertTrue(r.javaChecked() && r.noNewErrors());
  }

  @Test
  void keysAddedOnBothSidesAreResolvedByThePropertiesResolver() {
    String w = "a=1\nb=2\n<<<<<<< ours\nc=3\n=======\nd=4\n>>>>>>> theirs\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "conf/app.properties", "a=1\nb=2\n", "a=1\nb=2\nc=3\n",
        "a=1\nb=2\nd=4\n", w, "a=1\nb=2\nc=3\nd=4\n", false);
    assertEquals(Map.of("properties", 1), r.byResolver());
    assertTrue(r.matchesCommit());
  }

  @Test
  void importsAddedOnBothSidesAreResolvedByTheImportsResolver() {
    String head = "package p;\n\nimport a.A;\n";
    String tail = "\nclass I {\n}\n";
    String base = head + tail;
    String ours = head + "import b.B;\n" + tail;
    String theirs = head + "import c.C;\n" + tail;
    String w = head + "<<<<<<< ours\nimport b.B;\n=======\nimport c.C;\n>>>>>>> theirs\n" + tail;
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "src/I.java", base, ours, theirs, w, null, false);
    assertNull(r.error());
    assertTrue(r.fullyResolved());
    assertEquals(Map.of("imports", 1), r.byResolver());
    assertEquals(0, r.deviations(), r.firstDeviation());
  }

  @Test
  void aCompoundTokenInNoneOfTheSidesIsMadeUp() {
    assertEquals("6.7.1", FileEvaluation.madeUpToken("<v>6.7.1</v>\n", "<v>6.7.0</v>\n", "<v>6.6.0</v>\n",
        "<v>6.6.1</v>\n"));
    assertNull(FileEvaluation.madeUpToken("<v>6.7.0</v> a.b\n", "<v>6.7.0</v>\n", "<v>6.6.0</v>\n", "a.b\n"));
  }

  @Test
  void aVersionBothSidesChangedStaysAConflictWithoutAMadeUpToken() {
    String base = "<project>\n  <version>6.6.0</version>\n</project>\n";
    String ours = base.replace("6.6.0", "6.7.0");
    String theirs = base.replace("6.6.0", "6.6.1");
    String w = "<project>\n<<<<<<< ours\n  <version>6.7.0</version>\n=======\n  <version>6.6.1</version>\n"
        + ">>>>>>> theirs\n</project>\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "pom.xml", base, ours, theirs, w, null, false);
    assertEquals(1, r.unresolved());
    assertEquals(0, r.madeUpTokens(), r.firstMadeUp());
    FileResult on = FileEvaluation.evaluate("synthetic", "m", "pom.xml", base, ours, theirs, w, ours, false, true);
    assertEquals(0, on.unresolved(), "Newer version wins takes 6.7.0");
    assertEquals(Map.of("newer-version", 1), on.byResolver());
    assertTrue(on.matchesCommit());
    assertEquals(0, on.madeUpTokens(), on.firstMadeUp());
  }

  @Test
  void anImportBothSidesAddedAtDifferentPlacesIsNoDeviation() {
    // git keeps theirs' b.B clean and ours' in the conflict; the imports resolver merges the section, so b.B is
    // written once, where ours has it
    String base = "package p;\n\nimport a.A;\nimport c.C;\nimport e.E;\nimport f.F;\n\nclass I {\n}\n";
    String ours = "package p;\n\nimport a.A;\nimport c.C;\nimport b.B;\nimport d.D;\nimport e.E;\nimport f.F;\n\n"
        + "class I {\n}\n";
    String theirs = "package p;\n\nimport a.A;\nimport b.B;\nimport c.C;\nimport f.F;\n\nclass I {\n}\n";
    String w = "package p;\n\nimport a.A;\nimport b.B;\nimport c.C;\n<<<<<<< ours\nimport b.B;\nimport d.D;\n"
        + "import e.E;\n=======\n>>>>>>> theirs\nimport f.F;\n\nclass I {\n}\n";
    FileResult r = FileEvaluation.evaluate("synthetic", "m", "src/I.java", base, ours, theirs, w, null, false);
    assertNull(r.error());
    assertEquals(Map.of("imports", 1), r.byResolver());
    assertEquals(0, r.deviations(), r.firstDeviation());
  }
}
