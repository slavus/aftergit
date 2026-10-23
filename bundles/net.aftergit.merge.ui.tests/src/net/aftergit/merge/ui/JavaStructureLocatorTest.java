package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.jdt.JavaStructureLocator;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1: the Java nodes, parsed with the JDT parser without bindings. */
class JavaStructureLocatorTest {
  static final String SRC = String.join("\n",
      "package p;",                                                     // 0
      "",                                                               // 1
      "import java.util.List;",                                         // 2
      "import java.util.Map;",                                          // 3
      "",                                                               // 4
      "/** A. */",                                                      // 5
      "public class A {",                                               // 6
      "  private int count;",                                           // 7
      "",                                                               // 8
      "  /** Twice. */",                                                // 9
      "  protected int twice(java.util.List<String> xs, int... more) {", // 10
      "    return 0;",                                                  // 11
      "  }",                                                            // 12
      "",                                                               // 13
      "  int twice(Map.Entry[] e) {",                                   // 14
      "    return 1;",                                                  // 15
      "  }",                                                            // 16
      "",                                                               // 17
      "  static {",                                                     // 18
      "  }",                                                            // 19
      "",                                                               // 20
      "  A(String s) {",                                                // 21
      "  }",                                                            // 22
      "",                                                               // 23
      "  enum Color { RED, GREEN }",                                    // 24
      "",                                                               // 25
      "  record Point(int x, int y) {",                                 // 26
      "    Point {",                                                    // 27
      "    }",                                                          // 28
      "  }",                                                            // 29
      "}",                                                              // 30
      "");

  static String describe(List<StructureItem> items, String indent) {
    StringBuilder sb = new StringBuilder();
    for (StructureItem i : items) {
      sb.append(indent).append(i.kind()).append(' ').append(i.key()).append(" '").append(i.label()).append("' ")
          .append(i.visibility()).append(" [").append(i.start()).append(',').append(i.end()).append(")\n");
      sb.append(describe(i.children(), indent + "  "));
    }
    return sb.toString();
  }

  @Test
  void importsTypesAndMembersAreNestedAsInTheSource() {
    assertEquals(String.join("\n",
        "IMPORTS imports '' PACKAGE [2,4)",
        "CLASS type:A 'A' PUBLIC [5,31)",
        "  FIELD field:count 'count' PRIVATE [7,8)",
        "  METHOD method:twice(java.util.List,int[]) 'twice(List, int...)' PROTECTED [9,13)",
        "  METHOD method:twice(Map.Entry[]) 'twice(Entry[])' PACKAGE [14,17)",
        "  INITIALIZER init:0 'static {...}' PACKAGE [18,20)",
        "  CONSTRUCTOR ctor:(String) 'A(String)' PACKAGE [21,23)",
        "  ENUM type:Color 'Color' PACKAGE [24,25)",
        "    CONSTANT const:RED 'RED' PACKAGE [24,25)",
        "    CONSTANT const:GREEN 'GREEN' PACKAGE [24,25)",
        "  RECORD type:Point 'Point' PACKAGE [26,30)",
        "    CONSTRUCTOR ctor:compact 'Point' PACKAGE [27,29)",
        ""), describe(JavaStructureLocator.INSTANCE.locate(SRC), ""));
  }

  @Test
  void anInterfaceAndAnAnnotationTypeKeepTheirMembersPublicUnlessPrivate() {
    String src = "interface I {\n  void run();\n  int N = 1;\n  private void help() {}\n  class In {}\n}\n"
        + "@interface Note {\n  String value();\n}\nclass C {\n  void own() {}\n}\n";
    assertEquals(String.join("\n",
        "INTERFACE type:I 'I' PACKAGE [0,6)",
        "  METHOD method:run() 'run()' PUBLIC [1,2)",
        "  FIELD field:N 'N' PUBLIC [2,3)",
        "  METHOD method:help() 'help()' PRIVATE [3,4)",
        "  CLASS type:In 'In' PUBLIC [4,5)",
        "ANNOTATION type:Note 'Note' PACKAGE [6,9)",
        "  METHOD method:value() 'value()' PUBLIC [7,8)",
        "CLASS type:C 'C' PACKAGE [9,12)",
        "  METHOD method:own() 'own()' PACKAGE [10,11)",
        ""), describe(JavaStructureLocator.INSTANCE.locate(src), ""));
  }

  @Test
  void aSyntaxErrorKeepsTheMembersTheParserRecovered() {
    String src = "class B {\n  void ok() {\n  }\n\n  void broken( {\n  }\n\n  int after;\n}\n";
    String d = describe(JavaStructureLocator.INSTANCE.locate(src), "");
    assertTrue(d.contains("METHOD method:ok() 'ok()'"), d);
  }

  @Test
  void aLoneCarriageReturnGivesNoNodes() {
    assertEquals(List.of(), JavaStructureLocator.INSTANCE.locate("class A {\r  int x;\n}\n"));
  }
}
