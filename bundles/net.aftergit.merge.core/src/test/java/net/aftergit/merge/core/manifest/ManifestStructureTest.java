package net.aftergit.merge.core.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.core.structure.Visibility;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1: one node per manifest header, the clauses of a list header as its children. */
class ManifestStructureTest {
  static final String MF = String.join("\n",
      "Manifest-Version: 1.0",                           // 0
      "Bundle-SymbolicName: p",                          // 1
      "Require-Bundle: org.a;bundle-version=\"1.0\",",   // 2
      " org.b,",                                         // 3
      " org.c",                                          // 4
      "Bundle-Name: A, B",                               // 5
      "");

  @Test
  void headersAreNodesAndTheClausesOfAListHeaderTheirChildren() {
    assertEquals(List.of(
        new StructureItem("header:\u0000manifest-version", NodeKind.HEADER, "Manifest-Version", 0, 1),
        new StructureItem("header:\u0000bundle-symbolicname", NodeKind.HEADER, "Bundle-SymbolicName", 1, 2),
        new StructureItem("header:\u0000require-bundle", NodeKind.HEADER, "Require-Bundle", Visibility.PACKAGE, 2, 5,
            List.of(new StructureItem("clause:org.a", NodeKind.CLAUSE, "org.a", 2, 3),
                new StructureItem("clause:org.b", NodeKind.CLAUSE, "org.b", 3, 4),
                new StructureItem("clause:org.c", NodeKind.CLAUSE, "org.c", 4, 5))),
        new StructureItem("header:\u0000bundle-name", NodeKind.HEADER, "Bundle-Name", 5, 6)),
        ManifestStructure.INSTANCE.locate(MF), "Bundle-Name is no list header: its comma splits nothing");
  }

  @Test
  void clausesOnOneLineShareIt() {
    List<StructureItem> items = ManifestStructure.INSTANCE.locate("Import-Package: a.b,c.d;version=\"1\"\n");
    assertEquals(List.of(new StructureItem("clause:a.b", NodeKind.CLAUSE, "a.b", 0, 1),
        new StructureItem("clause:c.d", NodeKind.CLAUSE, "c.d", 0, 1)), items.get(0).children());
  }

  @Test
  void anUnreadableManifestHasNoNodes() {
    assertEquals(List.of(), ManifestStructure.INSTANCE.locate("Manifest-Version: 1.0\nnot a header\n"));
  }
}
