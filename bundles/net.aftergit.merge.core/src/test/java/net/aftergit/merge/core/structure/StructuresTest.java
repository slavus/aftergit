package net.aftergit.merge.core.structure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.manifest.ManifestStructure;
import net.aftergit.merge.core.properties.PropertiesStructure;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1, §2: the file name decides the locator. */
class StructuresTest {
  @Test
  void theFileNameDecides() {
    assertEquals(Structures.Support.JAVA, Structures.of("Calc.java"));
    assertEquals(Structures.Support.PROPERTIES, Structures.of("messages.properties"));
    assertEquals(Structures.Support.PROPERTIES, Structures.of("X.PROPERTIES"));
    assertEquals(Structures.Support.MANIFEST, Structures.of("MANIFEST.MF"));
    assertEquals(Structures.Support.NONE, Structures.of("manifest.mf"), "as the manifest resolver: exact name");
    assertEquals(Structures.Support.NONE, Structures.of("notes.txt"));
    assertEquals(Structures.Support.NONE, Structures.of(""));
    assertEquals(Structures.Support.NONE, Structures.of(null));
  }

  @Test
  void javaNeedsItsLocator() {
    StructureLocator java = text -> List.of();
    assertNull(Structures.forFile("A.java", null), "no JDT: no structure");
    assertSame(java, Structures.forFile("A.java", java));
    assertSame(PropertiesStructure.INSTANCE, Structures.forFile("m.properties", null));
    assertSame(ManifestStructure.INSTANCE, Structures.forFile("MANIFEST.MF", java));
    assertNull(Structures.forFile("notes.txt", java));
  }
}
