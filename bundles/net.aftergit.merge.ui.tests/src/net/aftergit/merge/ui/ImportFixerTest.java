package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.jdt.ImportFixer;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Import rewrites after a body change (spec §5.3), on a scratch working copy in a real Java project. */
class ImportFixerTest {
  static final String PATH = "src/p/X.java";

  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;

  @BeforeEach
  void setUp() throws Exception {
    repos = new ConflictRepos(dir);
    repos.write(PATH, cls("", ""));
    file = repos.javaProject("fixer-it", PATH);
  }

  @AfterEach
  void tearDown() throws Exception {
    JdtTestHooks.setParseHook(null);
    repos.dispose();
  }

  static String cls(String imports, String body) {
    return "package p;\n\n" + imports + "public class X {\n" + body + "}\n";
  }

  static ImportDecl type(String name) {
    return new ImportDecl(name, false, false, false, 0);
  }

  static ImportDecl member(String name) {
    return new ImportDecl(name, true, false, false, 0);
  }

  String fix(String before, String after, ImportDecl... candidates) {
    return ImportFixer.fix(file, before, after, List.of(candidates), null).orElse(null);
  }

  @Test
  void addsTheImportANewNameNeeds() {
    String fixed = fix(cls("", "  Object o;\n"), cls("", "  List<String> o;\n"), type("java.util.List"));
    assertNotNull(fixed);
    assertTrue(fixed.startsWith("package p;\n\nimport java.util.List;\n"), fixed);
    assertTrue(fixed.endsWith("public class X {\n  List<String> o;\n}\n"), fixed);
  }

  @Test
  void anAmbiguousNameGetsNoImport() {
    assertNull(fix(cls("", "  Object o;\n"), cls("", "  List o;\n"), type("java.util.List"), type("java.awt.List")));
  }

  @Test
  void addsAStaticImportForAMethod() {
    String fixed = fix(cls("", "  int v() {\n    return 0;\n  }\n"), cls("", "  int v() {\n    return max(1, 2);\n  }\n"),
        member("java.lang.Math.max"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import static java.lang.Math.max;\n"), fixed);
  }

  @Test
  void removesAnImportTheChangeLeftUnused() {
    String imports = "import java.util.Map;\n\n";
    String fixed = fix(cls(imports, "  Map<String, String> m;\n"), cls(imports, "  Object m;\n"));
    assertNotNull(fixed);
    assertFalse(fixed.contains("import java.util.Map;"), fixed);
    assertTrue(fixed.contains("  Object m;\n"), fixed);
  }

  @Test
  void keepsAnImportThatWasUnusedBefore() {
    String imports = "import java.util.Map;\nimport java.util.Set;\n\n";
    // the change mentions Set, which was unused before and stays unused: nothing to do
    assertNull(fix(cls(imports, "  Map<String, String> m;\n"), cls(imports, "  Map<String, String> m; // Set later\n")));
  }

  @Test
  void keepsAWildcardImport() {
    // which names an on-demand import serves is not known without bindings: it is never removed
    String imports = "import java.util.*;\n\n";
    assertNull(fix(cls(imports, "  List<String> l;\n"), cls(imports, "  Object l;\n")));
  }

  @Test
  void addsAndRemovesInOnePass() {
    String imports = "import java.util.Map;\n\n";
    String fixed = fix(cls(imports, "  Map<String, String> m;\n"), cls(imports, "  List<String> m;\n"),
        type("java.util.List"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import java.util.List;\n"), fixed);
    assertFalse(fixed.contains("import java.util.Map;"), fixed);
  }

  @Test
  void anImportUnusedBeforeStaysWhenBeforeDidNotCompile() {
    String imports = "import java.util.Map;\nimport java.util.Set;\n\n";
    // before calls an unknown foo(); after compiles, and Set was unused all along
    assertNull(fix(cls(imports, "  Map<String, String> m;\n  Object o = foo();\n"),
        cls(imports, "  Map<String, String> m;\n  Object o = Map.of();\n")));
  }

  @Test
  void removesAnImportWhenAfterHasAnUnrelatedError() {
    String imports = "import java.util.Map;\n\n";
    String fixed = fix(cls(imports, "  Map<String, String> m;\n  int x = foo();\n"),
        cls(imports, "  Object m;\n  int x = foo();\n"));
    assertNotNull(fixed);
    assertFalse(fixed.contains("import java.util.Map;"), fixed);
  }

  @Test
  void anImportKeptByJavadocStaysWhateverTheProjectSays() {
    JavaCore.create(file.getProject()).setOption(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.DISABLED);
    String imports = "import java.util.Map;\n\n";
    assertNull(fix(cls(imports, "  /** See {@link Map}. */\n  Map<String, String> m;\n"),
        cls(imports, "  /** See {@link Map}. */\n  Object m;\n")));
  }

  @Test
  void anImportUsedInAnAnnotationStays() {
    String imports = "import java.beans.Transient;\n\n";
    String method = "  @Transient\n  int v() {\n    return 0;\n  }\n";
    assertNull(fix(cls(imports, method + "  Transient t;\n"), cls(imports, method)));
  }

  @Test
  void removesAStaticImportTheChangeLeftUnused() {
    String imports = "import static java.lang.Math.max;\n\n";
    String fixed = fix(cls(imports, "  int v() {\n    return max(1, 2);\n  }\n"),
        cls(imports, "  int v() {\n    return 0;\n  }\n"));
    assertNotNull(fixed);
    assertFalse(fixed.contains("import static java.lang.Math.max;"), fixed);
  }

  @Test
  void keepsAStaticWildcardImport() {
    String imports = "import static java.lang.Math.*;\n\n";
    assertNull(fix(cls(imports, "  int v() {\n    return max(1, 2);\n  }\n"),
        cls(imports, "  int v() {\n    return 0;\n  }\n")));
  }

  @Test
  void aSyntaxErrorRemovesNothing() {
    String imports = "import java.util.Map;\n\n";
    // the recovered tree may have lost uses: no import is judged unused
    assertNull(fix(cls(imports, "  Map<String, String> m;\n"), cls(imports, "  Object m = (;\n")));
  }

  @Test
  void anUnterminatedTextBlockRemovesNothing() {
    String imports = "import java.util.Map;\n\n";
    // the stray text block swallows the rest of the file, Map's use included
    assertNull(fix(cls(imports, "  Map<String, String> m;\n"),
        cls(imports, "  String s = \"\"\"\n  List<String> l;\n  Map<String, String> m;\n"), type("java.util.List")));
  }

  @Test
  void anImportUsedInPackageInfoJavadocStays() throws Exception {
    String path = "src/p/package-info.java";
    String imports = "import java.util.Map;\n\n";
    String before = "/** See {@link Map}. */\npackage p;\n\n" + imports + "class Holder {\n  Map<String, String> m;\n}\n";
    String after = "/** See {@link Map}. */\npackage p;\n\n" + imports + "class Holder {\n  Object m;\n}\n";
    repos.write(path, before);
    IFile info = file.getProject().getFile(path);
    info.getParent().refreshLocal(1, null);
    assertNull(ImportFixer.fix(info, before, after, List.of(), null).orElse(null));
  }

  @Test
  void aDuplicatedImportThatIsStillUsedStays() {
    String fixed = fix(cls("import java.util.Map;\n\n", "  Map<String, String> m;\n"),
        cls("import java.util.Map;\nimport java.util.Map;\n\n", "  Map<String, String> m;\n"));
    assertTrue(fixed == null || fixed.contains("import java.util.Map;"), fixed);
  }

  @Test
  void existingImportLinesStayAsTheyAre() {
    String imports = "import java.util.Map; // keep me\n\n";
    String fixed = fix(cls(imports, "  Map<String, String> m;\n"),
        cls(imports, "  Map<String, String> m;\n  List<String> l;\n"), type("java.util.List"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import java.util.Map; // keep me\n"), fixed);
    assertTrue(fixed.contains("import java.util.List;\n"), fixed);
  }

  @Test
  void newImportsFollowTheProjectImportOrder() throws Exception {
    var node = new ProjectScope(file.getProject()).getNode("org.eclipse.jdt.ui");
    node.put("org.eclipse.jdt.ui.importorder", "org;java;");
    node.flush();
    String imports = "import org.w3c.dom.Node;\n\n";
    String fixed = fix(cls(imports, "  Node n;\n"), cls(imports, "  Node n;\n  List<String> l;\n"),
        type("java.util.List"));
    assertNotNull(fixed);
    assertTrue(fixed.indexOf("import org.w3c.dom.Node;") < fixed.indexOf("import java.util.List;"), fixed);
  }

  @Test
  void unusedImportsAreFoundWhateverTheProjectSays() {
    JavaCore.create(file.getProject()).setOption(JavaCore.COMPILER_PB_UNUSED_IMPORT, JavaCore.IGNORE);
    String imports = "import java.util.Map;\n\n";
    String fixed = fix(cls(imports, "  Map<String, String> m;\n"), cls(imports, "  Object m;\n"));
    assertNotNull(fixed);
    assertFalse(fixed.contains("import java.util.Map;"), fixed);
  }

  @Test
  void aCrlfProjectStillGivesModelLines() throws Exception {
    var node = new ProjectScope(file.getProject()).getNode(Platform.PI_RUNTIME);
    node.put(Platform.PREF_LINE_SEPARATOR, "\r\n");
    node.flush();
    String fixed = fix(cls("", "  Object o;\n"), cls("", "  List<String> o;\n"), type("java.util.List"));
    assertNotNull(fixed);
    assertFalse(fixed.contains("\r"), fixed);
  }

  @Test
  void anUnrelatedChangeIsNotParsed() {
    assertFalse(ImportFixer.mayMatter("int a;", "int b;", List.of(type("java.util.List"))));
    assertTrue(ImportFixer.mayMatter("int a;", "List a;", List.of(type("java.util.List"))));
    assertTrue(ImportFixer.mayMatter("import java.util.Map;\nMap a;", "import java.util.Map;\nint a;", List.of()),
        "a name the result imports");
    assertTrue(ImportFixer.mayMatter("import java.util.*;\nint a;", "import java.util.*;\nint b;", List.of()),
        "an on-demand import could be about any name");
  }

  @Test
  void anErrorWhileParsingLeavesNoScratchCopyOpen() {
    JdtTestHooks.setParseHook(() -> {
      throw new StackOverflowError("deeply nested source");
    });
    int open = JdtTestHooks.openScratchCopies();
    assertThrows(StackOverflowError.class,
        () -> fix(cls("", "  Object o;\n"), cls("", "  List<String> o;\n"), type("java.util.List")));
    assertEquals(open, JdtTestHooks.openScratchCopies(), "the scratch copy was discarded");
    JdtTestHooks.setParseHook(() -> {
      throw new IllegalStateException("broken parser");
    });
    assertNull(fix(cls("", "  Object o;\n"), cls("", "  List<String> o;\n"), type("java.util.List")));
    assertEquals(open, JdtTestHooks.openScratchCopies());
    JdtTestHooks.setParseHook(null);
    assertNotNull(fix(cls("", "  Object o;\n"), cls("", "  List<String> o;\n"), type("java.util.List")));
    assertEquals(open, JdtTestHooks.openScratchCopies(), "a fix that worked discards its copy too");
  }

  @Test
  void failuresGoToTheCallersHandler() {
    JdtTestHooks.setParseHook(() -> {
      throw new IllegalStateException("broken parser");
    });
    java.util.List<Exception> failures = new java.util.ArrayList<>();
    java.util.List<org.eclipse.core.runtime.IStatus> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    org.eclipse.core.runtime.ILogListener l = (status, plugin) -> logged.add(status);
    Platform.addLogListener(l);
    try {
      assertTrue(ImportFixer.fix(file, cls("", "  Object o;\n"), cls("", "  List<String> o;\n"),
          List.of(type("java.util.List")), null, failures::add).isEmpty());
    } finally {
      Platform.removeLogListener(l);
    }
    assertEquals(1, failures.size());
    assertInstanceOf(IllegalStateException.class, failures.get(0));
    assertTrue(logged.isEmpty(), "the handler decides what is logged: " + logged);
  }

  @Test
  void aVariableGetsNoStaticImportOfAMethod() {
    assertNull(fix(cls("", "  int v() {\n    return 0;\n  }\n"), cls("", "  int v() {\n    return max;\n  }\n"),
        member("java.lang.Math.max")));
  }

  @Test
  void aVariableGetsAStaticImportOfAField() {
    String fixed = fix(cls("", "  double v() {\n    return 0;\n  }\n"),
        cls("", "  double v() {\n    return PI;\n  }\n"), member("java.lang.Math.PI"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import static java.lang.Math.PI;\n"), fixed);
  }

  @Test
  void aNameOutsideTheChangeGetsNoImport() {
    // "Vector" sits in text the change did not write (an unresolved conflict, say): not this change's business
    String fixed = fix(cls("", "  Vector v;\n  Object o;\n"), cls("", "  Vector v;\n  List<String> o;\n"),
        type("java.util.List"), type("java.util.Vector"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import java.util.List;\n"), fixed);
    assertFalse(fixed.contains("import java.util.Vector;"), fixed);
  }

  @Test
  void aJavaFileOutsideAJavaProjectIsLeftAlone(@org.junit.jupiter.api.io.TempDir File other) throws Exception {
    ConflictRepos plain = new ConflictRepos(other);
    try {
      plain.write("src/p/Y.java", cls("", ""));
      IFile y = plain.plainProject("fixer-plain", "src/p/Y.java");
      java.util.List<Exception> failures = new java.util.ArrayList<>();
      assertTrue(ImportFixer.fix(y, cls("", "  Object o;\n"), cls("", "  List<String> o;\n"),
          List.of(type("java.util.List")), null, failures::add).isEmpty());
      assertEquals(List.of(), failures, "nothing to report: there is no Java project to ask");
    } finally {
      plain.dispose();
    }
  }

  @Test
  void aLoneCarriageReturnAboveTheChangeDoesNotShiftItsLines() {
    // a private result keeps a lone '\r' inside a line; JDT counts it as a line end, the model does not
    String fixed = fix(cls("", "  /* a\rb */\n  Object o;\n"), cls("", "  /* a\rb */\n  List<String> o;\n"),
        type("java.util.List"));
    assertNotNull(fixed);
    assertTrue(fixed.contains("import java.util.List;\n"), fixed);
  }
}
