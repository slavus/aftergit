package net.aftergit.merge.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.core.imports.ImportSection;
import org.junit.jupiter.api.Test;

/** The same cases as the JDT locator's tests: the corpus stand-in must find the same sections. */
class JavacImportLocatorTest {
  static ImportSection locate(String text) {
    return JavacImportLocator.INSTANCE.locate(text).orElseThrow();
  }

  @Test
  void theSectionRunsFromTheFirstToTheLastImportLine() {
    ImportSection s = locate("package p;\n\nimport a.A;\n// tools\nimport static b.B.c;\nimport d.*;\n\nclass C {}\n");
    assertEquals(2, s.startLine());
    assertEquals(6, s.endLine());
    assertEquals(List.of("a.A", "static b.B.c", "d.*"), s.imports().stream().map(ImportDecl::key).toList());
    assertEquals(4, s.imports().get(1).line());
  }

  @Test
  void withoutImportsTheSectionIsEmptyAfterThePackage() {
    ImportSection s = locate("package p;\n\nclass C {}\n");
    assertEquals(1, s.startLine());
    assertEquals(1, s.endLine());
  }

  @Test
  void withoutPackageAndImportsItIsAtTheFirstType() {
    ImportSection s = locate("// header\n\n/** Doc. */\nclass C {}\n");
    assertEquals(2, s.startLine());
    assertEquals(2, s.endLine());
  }

  @Test
  void anImportSharingALineWithCodeIsNoSection() {
    assertTrue(JavacImportLocator.INSTANCE.locate("package p; import a.A;\nclass C {}\n").isEmpty());
    assertTrue(JavacImportLocator.INSTANCE.locate("import a.A; class C {}\n").isEmpty());
  }

  @Test
  void aTrailingLineCommentIsPartOfTheLastImportLine() {
    ImportSection s = locate("import a.A; // why\nclass C {}\n");
    assertEquals(0, s.startLine());
    assertEquals(1, s.endLine());
    assertEquals(List.of("a.A"), s.imports().stream().map(ImportDecl::key).toList());
  }

  @Test
  void crlfLinesCountLikeTheModel() {
    ImportSection s = locate("package p;\r\n\r\nimport a.A;\r\n\r\nclass C {}\r\n");
    assertEquals(2, s.startLine());
    assertEquals(3, s.endLine());
    assertTrue(JavacImportLocator.INSTANCE.locate("package p;\rimport a.A;\n").isEmpty(), "a lone carriage return");
  }

  @Test
  void moduleImports() {
    assertEquals("module java.base", locate("import module java.base;\nclass C {}\n").imports().get(0).key());
  }

  private static List<String> keys(ImportSection s) {
    return s.imports().stream().map(ImportDecl::key).toList();
  }

  @Test
  void aSyntaxErrorInABodyDoesNotHideTheSection() {
    ImportSection s = locate("package p;\n\nimport a.A;\n\nclass C {\n  void m() {\n    int = ;\n  }\n}\n");
    assertEquals(2, s.startLine());
    assertEquals(3, s.endLine());
    assertEquals(List.of("a.A"), keys(s));
  }

  @Test
  void aStaticOnDemandImportHasItsOwnKey() {
    ImportSection s = locate("import static a.B.*;\nimport a.B.*;\nclass C {}\n");
    assertEquals(List.of("static a.B.*", "a.B.*"), keys(s));
    assertTrue(s.imports().get(0).isStatic());
    assertTrue(s.imports().get(0).onDemand());
  }

  @Test
  void anIndentedFirstImportStartsTheSectionOnItsLine() {
    ImportSection s = locate("package p;\n\n    import a.A;\n  import b.B;\nclass C {}\n");
    assertEquals(2, s.startLine());
    assertEquals(4, s.endLine());
  }

  @Test
  void aCommentBeforeTheFirstImportIsNotPartOfTheSection() {
    ImportSection doc = locate("/** Doc. */\nimport a.A;\nclass C {}\n");
    assertEquals(1, doc.startLine());
    assertEquals(2, doc.endLine());
    ImportSection block = locate("package p;\n/* license\n   text */\nimport a.A;\nclass C {}\n");
    assertEquals(3, block.startLine());
    assertEquals(4, block.endLine());
    assertTrue(JavacImportLocator.INSTANCE.locate("/* x */ import a.A;\nclass C {}\n").isEmpty(),
        "a comment on the import's own line");
  }

  @Test
  void aModuleDescriptorHasNoSection() {
    assertTrue(JavacImportLocator.INSTANCE.locate("module m {\n  requires java.base;\n}\n").isEmpty());
  }
}
