package net.aftergit.merge.core.imports;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class ImportMergeTest {
  static String merge(String left, String base, String right) {
    return ImportMerge.merge(left, base, right).orElseThrow();
  }

  @Test
  void importsAddedOnBothSidesAreAllKept() {
    assertEquals("import a.A;\nimport b.B;\nimport c.C;\n",
        merge("import a.A;\nimport b.B;\n", "import a.A;\n", "import a.A;\nimport c.C;\n"));
  }

  @Test
  void anImportDeletedOnOneSideIsGoneEvenWhenTheOtherAddsNextToIt() {
    assertEquals("import a.A;\nimport c.C;\n",
        merge("import a.A;\n", "import a.A;\nimport b.B;\n", "import a.A;\nimport b.B;\nimport c.C;\n"));
    assertEquals("import b.B;\nimport d.D;\n",
        merge("import a.A;\nimport b.B;\nimport d.D;\n", "import a.A;\nimport b.B;\n", "import b.B;\n"));
  }

  @Test
  void whenOursHasNoImportsTheirsLayoutIsKept() {
    assertEquals("\nimport c.C;\n\n", merge("\n", "\nimport a.A;\n\n", "\nimport a.A;\nimport c.C;\n\n"));
    assertEquals("\n", merge("\n", "\nimport a.A;\nimport x.X;\n\n", "\nimport a.A;\n\n"),
        "nothing left to import: ours' layout stays");
  }

  @Test
  void blankLinesLeftByRemovedImportsCollapseToOne() {
    assertEquals("\nimport y.Y;\n\n",
        merge("\n", "\nimport a.A;\n\nimport x.X;\n\n", "\nimport a.A;\n\nimport x.X;\nimport y.Y;\n\n"));
    assertEquals("import a.A;\n\n\nimport b.B;\n",
        merge("import a.A;\n\n\nimport b.B;\n", "import a.A;\n\n\nimport b.B;\n", "import a.A;\n\n\nimport b.B;\n"),
        "blank lines the template has next to each other stay");
  }

  @Test
  void staticAndNormalImportsOfTheSameNameAreDifferentImports() {
    assertEquals("import static a.M.x;\nimport a.M.x;\n", merge("import static a.M.x;\n", "", "import a.M.x;\n"));
  }

  @Test
  void theSameImportAddedByBothSidesAppearsOnce() {
    assertEquals("import a.A;\nimport x.X;\n",
        merge("import a.A;\nimport x.X;\n", "import a.A;\n", "import x.X;\nimport a.A;\n"));
  }

  @Test
  void wildcardAndSingleTypeImportsAreDifferentImports() {
    assertEquals("import java.util.*;\nimport java.util.Map;\n",
        merge("import java.util.*;\n", "import java.util.List;\n", "import java.util.List;\nimport java.util.Map;\n"));
  }

  @Test
  void blankLinesAndCommentsOfOursStay() {
    assertEquals("import a.A;\nimport c.C;\n\n// tools\nimport t.T;\n",
        merge("import a.A;\n\n// tools\nimport t.T;\n", "import a.A;\n", "import a.A;\nimport c.C;\n"));
  }

  @Test
  void spacingAndTrailingCommentsDoNotChangeAnImport() {
    assertEquals("import a . A ; // why\n", merge("import a . A ; // why\n", "import a.A;\n", "import a.A;\n"));
  }

  @Test
  void anythingButImportsBlanksAndOneLineCommentsIsNotMerged() {
    assertTrue(ImportMerge.merge("import a.A;\nclass X {}\n", "import a.A;\n", "import a.A;\n").isEmpty());
    assertTrue(ImportMerge.merge("/* a\n b */\n", "", "").isEmpty());
    assertTrue(ImportMerge.merge("package p;\nimport a.A;\n", "import a.A;\n", "import a.A;\n").isEmpty());
  }

  @Test
  void codeBetweenTwoBlockCommentsOnOneLineIsNotAComment() {
    assertTrue(ImportMerge.merge("import a.A;\n", "import a.A;\n", "import a.A;\n/* x */ import b.B; /* z */\n")
        .isEmpty());
    assertTrue(ImportMerge.merge("import a.A;\n", "import a.A;\n", "import a.A;\n/* x */ int y; /* z */\n")
        .isEmpty());
  }

  @Test
  void blankAndCommentLinesOnlyTheirsAddedAreDropped() {
    assertEquals("import a.A;\nimport c.C;\n",
        merge("import a.A;\n", "import a.A;\n", "import a.A;\n\n// new\nimport c.C;\n"));
  }

  @Test
  void theImportSetDoesNotDependOnWhichSideIsOurs() {
    Random random = new Random(7);
    String[] pool = {"import a.A;", "import b.B;", "import static b.B.c;", "import c.*;", "import d.D;"};
    for (int n = 0; n < 500; n++) {
      String b = pick(random, pool);
      String l = pick(random, pool);
      String r = pick(random, pool);
      assertEquals(lines(merge(l, b, r)), lines(merge(r, b, l)), l + "|" + b + "|" + r);
    }
  }

  private static String pick(Random random, String[] pool) {
    StringBuilder sb = new StringBuilder();
    for (String p : pool) {
      if (random.nextBoolean()) {
        sb.append(p).append('\n');
      }
    }
    return sb.toString();
  }

  private static Set<String> lines(String chunk) {
    return new TreeSet<>(LineText.chunkLines(chunk));
  }

  @Test
  void anImportTheirsAddedWithoutAnyPrecedingNeighbourGoesBeforeTheNextOneTheResultHas() {
    assertEquals("import x.X;\nimport b.B;\n", merge("import b.B;\n", "import b.B;\n", "import x.X;\nimport b.B;\n"));
  }

  @Test
  void anImportTheirsAddedWithoutAnyNeighbourInTheResultGoesAfterItsLastImport() {
    assertEquals("import c.C;\nimport x.X;\n",
        merge("import a.A;\nimport c.C;\n", "import a.A;\n", "import x.X;\n"));
    assertEquals("// hi\nimport x.X;\n", merge("// hi\nimport a.A;\n", "import a.A;\n", "import x.X;\n"),
        "no import left at all: at the end");
  }

  @Test
  void anEmptyBaseOrAnEmptySideStillHasAWellDefinedImportSet() {
    assertEquals("import a.A;\nimport b.B;\n", merge("import a.A;\n", "", "import b.B;\n"));
    assertEquals("import b.B;\n", merge("", "import a.A;\n", "import a.A;\nimport b.B;\n"));
    assertEquals("", merge("import a.A;\n", "import a.A;\n", ""), "theirs deleted the only import");
    assertEquals("", merge("", "", ""));
  }

  /** Pins: only the import set is merged; a text change theirs made to a kept import line is not carried over. */
  @Test
  void aTheirsOnlyTextChangeOnAKeptImportLineIsNotCarriedOver() {
    assertEquals("import a.A;\n", merge("import a.A;\n", "import a.A;\n", "import a.A; // keep\n"));
    assertEquals("import a.A;\nimport b.B;\n",
        merge("import a.A;\n", "import a.A;\n", "import a.A; // keep\nimport b.B;\n"));
  }
}
