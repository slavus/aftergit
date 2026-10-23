package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSection;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HybridInitializerImportsTest {
  @TempDir
  Path tmp;

  /** git's own merge of the three texts (with markers where it conflicts), as the working-tree file W. */
  String gitMerge(String base, String ours, String theirs) throws Exception {
    Path o = tmp.resolve("ours"), b = tmp.resolve("base"), t = tmp.resolve("theirs");
    Files.writeString(o, ours);
    Files.writeString(b, base);
    Files.writeString(t, theirs);
    Process p;
    try {
      ProcessBuilder pb = new ProcessBuilder("git", "merge-file", "-p", "--diff-algorithm=histogram", "-L", "ours",
          "-L", "base", "-L", "theirs", o.toString(), b.toString(), t.toString())
          .redirectError(ProcessBuilder.Redirect.DISCARD);
      // isolate from the user's git configuration (e.g. merge.conflictStyle)
      pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
      pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
      pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
      p = pb.start();
    } catch (IOException e) {
      assumeTrue(false, "git not available");
      return null;
    }
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor() < 128, "git merge-file failed");
    return out;
  }

  /** Finds "import" lines textually: enough for core tests (the plugin uses the Java parser). */
  static final ImportLocator LOCATOR = text -> {
    List<String> lines = LineText.of(text).lines();
    int first = -1;
    int last = -1;
    int pkg = -1;
    List<ImportDecl> decls = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String s = lines.get(i).strip();
      if (s.startsWith("package ")) {
        pkg = i;
      }
      if (s.startsWith("import ")) {
        first = first < 0 ? i : first;
        last = i;
        boolean isStatic = s.startsWith("import static ");
        String name = s.substring(isStatic ? 14 : 7, s.indexOf(';')).strip();
        boolean onDemand = name.endsWith(".*");
        decls.add(new ImportDecl(onDemand ? name.substring(0, name.length() - 2) : name, isStatic, onDemand, false, i));
      }
    }
    return Optional.of(first < 0 ? new ImportSection(pkg + 1, pkg + 1, List.of())
        : new ImportSection(first, last + 1, decls));
  };

  static final String BASE = "package p;\n\nimport a.A;\n\nclass C {\n  int x = 0;\n}\n";
  static final String OURS = "package p;\n\nimport a.A;\nimport b.B;\n\nclass C {\n  int x = 0;\n}\n";
  static final String THEIRS = "package p;\n\nimport a.A;\nimport c.C;\n\nclass C {\n  int x = 0;\n}\n";

  static InitResult init(String base, String ours, String theirs, String working, ImportLocator locator) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, working), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false), locator);
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  @Test
  void importsAddedOnBothSidesBecomeOneAutoResolvedBlock() {
    InitResult r = init(BASE, OURS, THEIRS, null, LOCATOR);
    MergeModel m = r.model();
    assertNotNull(r.imports());
    assertEquals(1, m.blocks().size());
    MergeBlock b = m.blocks().get(0);
    assertEquals(Region.IMPORTS, b.region());
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(Strategy.IMPORTS, b.strategy());
    assertEquals("imports", b.resolvedBy());
    assertEquals("import a.A;\n", b.baseText(), "the block covers the whole import section");
    assertEquals("import a.A;\nimport b.B;\nimport c.C;\n", m.resultText(0));
    assertEquals(0, m.unresolvedConflicts());
    m.revert(0);
    assertEquals(BASE, text(m));
  }

  @Test
  void noChangeCrossesTheImportSection() {
    String base = "import a.A;\nclass C {}\n";
    String ours = "import a.A2;\nclass D {}\n";
    MergeModel split = init(base, ours, base, null, LOCATOR).model();
    assertEquals(2, split.blocks().size());
    assertEquals(Region.IMPORTS, split.blocks().get(0).region());
    assertEquals(Region.BODY, split.blocks().get(1).region());
    MergeModel plain = init(base, ours, base, null, null).model();
    assertEquals(1, plain.blocks().size(), "without sections one change covers both lines");
  }

  @Test
  void importsAddedWhereTheBaseHadNone() {
    String base = "package p;\n\nclass C {}\n";
    String ours = "package p;\n\nimport b.B;\n\nclass C {}\n";
    String theirs = "package p;\n\nimport c.C;\n\nclass C {}\n";
    MergeModel m = init(base, ours, theirs, null, LOCATOR).model();
    assertEquals(0, m.unresolvedConflicts());
    assertEquals("package p;\n\nimport b.B;\nimport c.C;\n\nclass C {}\n", text(m));
  }

  @Test
  void gitMarkersInTheImportsKeepOurResolution() {
    String working = "package p;\n\nimport a.A;\n<<<<<<< HEAD\nimport b.B;\n=======\nimport c.C;\n>>>>>>> feature\n"
        + "\nclass C {\n  int x = 0;\n}\n";
    InitResult r = init(BASE, OURS, THEIRS, working, LOCATOR);
    assertFalse(r.usedFallback());
    assertEquals(BlockState.AUTO_RESOLVED, r.model().blocks().get(0).state());
    assertEquals("import a.A;\nimport b.B;\nimport c.C;\n", r.model().resultText(0));
  }

  @Test
  void importsGitMergedCleanlyWin() {
    String working = "package p;\n\nimport a.A;\nimport c.C;\nimport b.B;\n\nclass C {\n  int x = 0;\n}\n";
    InitResult r = init(BASE, OURS, THEIRS, working, LOCATOR);
    assertFalse(r.usedFallback());
    assertEquals(BlockState.RESOLVED_BY_GIT, r.model().blocks().get(0).state());
    assertEquals("import a.A;\nimport c.C;\nimport b.B;\n", r.model().resultText(0));
  }

  @Test
  void aGitHunkOverImportsAndBodyKeepsOurBlocks() {
    String ours = OURS.replace("int x = 0;", "int x = 1;").replace("class C {", "class C { // ours");
    String theirs = THEIRS.replace("int x = 0;", "int x = 2;").replace("class C {", "class C { // theirs");
    String working = "package p;\n\n<<<<<<< HEAD\nimport a.A;\nimport b.B;\n\nclass C { // ours\n  int x = 1;\n"
        + "=======\nimport a.A;\nimport c.C;\n\nclass C { // theirs\n  int x = 2;\n>>>>>>> feature\n}\n";
    InitResult r = init(BASE, ours, theirs, working, LOCATOR);
    assertFalse(r.usedFallback());
    List<MergeBlock> blocks = r.model().blocks();
    assertEquals(2, blocks.size());
    assertEquals(Region.IMPORTS, blocks.get(0).region());
    assertEquals(BlockState.AUTO_RESOLVED, blocks.get(0).state());
    assertEquals("import a.A;\nimport b.B;\nimport c.C;\n", r.model().resultText(0));
    assertEquals(Region.BODY, blocks.get(1).region());
    assertEquals(BlockState.UNRESOLVED, blocks.get(1).state());
    assertEquals("class C {\n  int x = 0;\n", r.model().resultText(1), "the body conflict keeps its base text");
    assertEquals("class C { // ours\n  int x = 1;\n", r.model().sideText(1, ThreeSide.LEFT));
    assertEquals("class C { // theirs\n  int x = 2;\n", r.model().sideText(1, ThreeSide.RIGHT));
    assertEquals(1, r.model().unresolvedConflicts());
  }

  @Test
  void aSideWithoutAnImportSectionMeansNoSplit() {
    ImportLocator onlyBase = t -> t.equals(BASE) ? LOCATOR.locate(t) : Optional.empty();
    InitResult r = init(BASE, OURS, THEIRS, null, onlyBase);
    assertNull(r.imports());
    assertEquals(Region.BODY, r.model().blocks().get(0).region());
    assertEquals(1, r.model().unresolvedConflicts(), "add/add without the imports pass stays a conflict");
  }

  @Test
  void aFailingLocatorMeansNoSplit() {
    InitResult r = init(BASE, OURS, THEIRS, null, t -> {
      throw new IllegalStateException("parser");
    });
    assertNull(r.imports());
    assertEquals(1, r.model().unresolvedConflicts());
  }

  @Test
  void withoutALocatorTheMergeIsAsBefore() {
    InitResult r = HybridInitializer.initialize(new MergeInput(BASE, OURS, THEIRS, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false));
    assertNull(r.imports());
    assertEquals(text(init(BASE, OURS, THEIRS, null, null).model()), text(r.model()));
  }

  static void assertAligned(InitResult r, String expected) {
    assertFalse(r.usedFallback(), "aligned with git's file");
    assertNotNull(r.imports());
    assertEquals(0, r.model().unresolvedConflicts());
    assertEquals(expected, text(r.model()));
  }

  @Test
  void oneSideRemovingAllImportsKeepsTheBlankLinesInPlace() {
    String base = "package p;\n\nimport a.A;\nimport x.X;\n\nclass C {}\n";
    String ours = "package p;\n\nclass C {}\n";
    String theirs = "package p;\n\nimport a.A;\n\nclass C {}\n";
    String working = "package p;\n\n<<<<<<< ours\n=======\nimport a.A;\n\n>>>>>>> theirs\nclass C {}\n";
    InitResult r = init(base, ours, theirs, working, LOCATOR);
    assertAligned(r, "package p;\n\nclass C {}\n");
    assertEquals(Region.IMPORTS, r.model().blocks().get(0).region());
    assertEquals("package p;\n\nclass C {}\n", text(init(base, ours, theirs, null, LOCATOR).model()));
  }

  @Test
  void anImportAddedWhileTheOtherSideRemovedAllKeepsBothBlankLines() {
    String base = "package p;\n\nimport a.A;\n\nclass C {}\n";
    String ours = "package p;\n\nclass C {}\n";
    String theirs = "package p;\n\nimport a.A;\nimport c.C;\n\nclass C {}\n";
    String working = "package p;\n\n<<<<<<< ours\n=======\nimport a.A;\nimport c.C;\n\n>>>>>>> theirs\nclass C {}\n";
    String expected = "package p;\n\nimport c.C;\n\nclass C {}\n";
    assertAligned(init(base, ours, theirs, working, LOCATOR), expected);
    assertEquals(expected, text(init(base, ours, theirs, null, LOCATOR).model()));
  }

  @Test
  void aGitConflictAtTheEndOfTheImportsThatTheSplitResolves() {
    String base = "package p;\nimport a.A;\nclass C {}\n";
    String ours = "package p;\nimport a.A;\n// ours\nclass C {}\n";
    String theirs = "package p;\nimport a.A;\nimport z.Z;\nclass C {}\n";
    String working = "package p;\nimport a.A;\n<<<<<<< ours\n// ours\n=======\nimport z.Z;\n>>>>>>> theirs\nclass C {}\n";
    InitResult r = init(base, ours, theirs, working, LOCATOR);
    assertAligned(r, "package p;\nimport a.A;\nimport z.Z;\n// ours\nclass C {}\n");
    assertEquals(Region.IMPORTS, r.model().blocks().get(0).region());
    assertEquals(Region.BODY, r.model().blocks().get(1).region());
  }

  @Test
  void aGitConflictAtTheStartOfTheImportsThatTheSplitResolves() {
    String base = "package p;\nimport a.A;\nclass C {}\n";
    String ours = "package p;\n// ours\nimport a.A;\nclass C {}\n";
    String theirs = "package p;\nimport z.Z;\nimport a.A;\nclass C {}\n";
    String working = "package p;\n<<<<<<< ours\n// ours\n=======\nimport z.Z;\n>>>>>>> theirs\nimport a.A;\nclass C {}\n";
    assertAligned(init(base, ours, theirs, working, LOCATOR), "package p;\n// ours\nimport z.Z;\nimport a.A;\nclass C {}\n");
  }

  @Test
  void crlfWithGitMarkers() {
    String working = "package p;\r\n\r\nimport a.A;\r\n<<<<<<< ours\r\nimport b.B;\r\n=======\r\nimport c.C;\r\n"
        + ">>>>>>> theirs\r\n\r\nclass C {\r\n  int x = 0;\r\n}\r\n";
    InitResult r = init(BASE.replace("\n", "\r\n"), OURS.replace("\n", "\r\n"), THEIRS.replace("\n", "\r\n"),
        working, LOCATOR);
    assertAligned(r, "package p;\n\nimport a.A;\nimport b.B;\nimport c.C;\n\nclass C {\n  int x = 0;\n}\n");
    assertEquals(BlockState.AUTO_RESOLVED, r.model().blocks().get(0).state());
  }

  @Test
  void noNewlineAtTheEndWithGitMarkers() {
    String working = "import a.A;\n<<<<<<< ours\nimport b.B;\n=======\nimport c.C;\n>>>>>>> theirs\n";
    InitResult r = init("import a.A;", "import a.A;\nimport b.B;", "import a.A;\nimport c.C;", working, LOCATOR);
    assertAligned(r, "import a.A;\nimport b.B;\nimport c.C;\n");
    assertEquals(BlockState.AUTO_RESOLVED, r.model().blocks().get(0).state());
  }

  @Test
  void bothSidesRemovingAllImportsNextToAGitConflictInTheBody() {
    String base = "package p;\n\nimport a.A;\nimport x.X;\n\nclass C {}\n";
    String ours = "package p;\n\nclass C {}\n";
    String theirs = "package p;\n\nclass C { }\n";
    String working = "package p;\n\n<<<<<<< ours\nclass C {}\n=======\nclass C { }\n>>>>>>> theirs\n";
    assertAligned(init(base, ours, theirs, working, LOCATOR), "package p;\n\nclass C { }\n");
  }

  @Test
  void aBodyDeletionNextToAnImportAddedAtTheEnd() {
    String base = "package p;\nimport a.A;\n// doc\nclass C {}\n";
    String ours = "package p;\nimport a.A;\nclass C {}\n";
    String theirs = "package p;\nimport a.A;\nimport z.Z;\n// doc\nclass C {}\n";
    String working = "package p;\nimport a.A;\n<<<<<<< ours\n=======\nimport z.Z;\n// doc\n>>>>>>> theirs\nclass C {}\n";
    assertAligned(init(base, ours, theirs, working, LOCATOR), "package p;\nimport a.A;\nimport z.Z;\nclass C {}\n");
  }

  @Test
  void removedImportGroupsLeaveOneBlankLine() {
    String base = "package p;\n\nimport a.A;\n\nimport x.X;\n\nclass C {}\n";
    String ours = "package p;\n\nclass C {}\n";
    String theirs = "package p;\n\nimport a.A;\n\nimport x.X;\nimport y.Y;\n\nclass C {}\n";
    String working = "package p;\n\n<<<<<<< ours\n=======\nimport a.A;\n\nimport x.X;\nimport y.Y;\n\n>>>>>>> theirs\n"
        + "class C {}\n";
    assertAligned(init(base, ours, theirs, working, LOCATOR), "package p;\n\nimport y.Y;\n\nclass C {}\n");
  }

  /** The text without its import section (first to last import line; the import blocks only cover what changed), found as the plugin's locator does. */
  private static String outsideImportSection(String text) {
    ImportSection s = LOCATOR.locate(text).orElseThrow();
    List<String> lines = LineText.of(text).lines();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      if (i < s.startLine() || i >= s.endLine()) {
        sb.append(lines.get(i)).append('\n');
      }
    }
    return sb.toString();
  }

  @Test
  void theResultOutsideTheImportSectionIsThePlainMergeOutsideItsImportSection() throws Exception {
    String[][] inputs = {
        {BASE, OURS.replace("int x = 0;", "int x = 1;"), THEIRS.replace("class C {", "class C { // t")},
        {BASE, OURS, THEIRS.replace("}\n", "  int y;\n}\n")},
        {"package p;\n\nimport a.A;\nimport x.X;\n\nclass C {\n  int x = 0;\n}\n",
            "package p;\n\nimport a.A;\n\nclass C {\n  int x = 0;\n}\n",
            "package p;\n\nimport a.A;\nimport x.X;\n\nclass C {\n  int x = 2;\n}\n"},
        {"package p;\nimport a.A;\nclass C {\n  int x = 0;\n}\n",
            "package p;\nimport a.A;\nimport b.B;\nclass C {\n  int x = 0;\n}\n",
            "package p;\nimport a.A;\nclass C {\n  int x = 0;\n  int z;\n}\n"},
    };
    for (String[] in : inputs) {
      String w = gitMerge(in[0], in[1], in[2]);
      InitResult split = init(in[0], in[1], in[2], w, LOCATOR);
      InitResult plain = init(in[0], in[1], in[2], w, null);
      assertNotNull(split.imports(), in[1]);
      assertEquals(outsideImportSection(text(plain.model())), outsideImportSection(text(split.model())), in[1]);
    }
  }

  @Test
  void blankLineImportGroupsAlignWithGitsFile() throws Exception {
    String base = "package p;\n\nimport a.A;\n\nimport java.util.List;\n\nclass C {}\n";
    String ours = "package p;\n\nimport a.A;\nimport b.B;\n\nimport java.util.List;\n\nclass C {}\n";
    String theirs = "package p;\n\nimport a.A;\n\nimport java.util.List;\nimport java.util.Map;\n\nclass C {}\n";
    String w = gitMerge(base, ours, theirs);
    assertFalse(w.contains("<<<<<<<"), "git merges the two groups cleanly");
    InitResult r = init(base, ours, theirs, w, LOCATOR);
    assertAligned(r, "package p;\n\nimport a.A;\nimport b.B;\n\nimport java.util.List;\nimport java.util.Map;\n\n"
        + "class C {}\n");
    assertEquals(Region.IMPORTS, r.model().blocks().get(0).region());
  }

  @Test
  void oursDeletingTheLastImportAndTheirsTheLineRightAfterItStayAligned() throws Exception {
    String base = "package p;\nimport a.A;\nimport b.B;\nint x;\nclass C {}\n";
    String ours = "package p;\nimport a.A;\nint x;\nclass C {}\n";
    String theirs = "package p;\nimport a.A;\nimport b.B;\nclass C {}\n";
    String w = gitMerge(base, ours, theirs);
    assertTrue(w.contains("<<<<<<<"), "git sees one conflict over the two adjacent deletions");
    assertAligned(init(base, ours, theirs, w, LOCATOR), "package p;\nimport a.A;\nclass C {}\n");
  }
}
