package net.aftergit.merge.core.properties;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.StructureBuilder;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.core.structure.StructureNode;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1: one node per .properties key, with the comments above it. */
class PropertiesStructureTest {
  @Test
  void everyEntryIsANodeWithTheBlankAndCommentLinesAboveIt() {
    String text = "# head\na=1\n\n# about b\nb = 2 \\\n  more\nc:3\n# tail\n";
    assertEquals(List.of(
        new StructureItem("key:a", NodeKind.PROPERTY, "a", 0, 2),
        new StructureItem("key:b", NodeKind.PROPERTY, "b", 2, 6),
        new StructureItem("key:c", NodeKind.PROPERTY, "c", 6, 7)), PropertiesStructure.INSTANCE.locate(text));
  }

  @Test
  void anEmptyTextHasNoNodes() {
    assertEquals(List.of(), PropertiesStructure.INSTANCE.locate(""));
    assertEquals(List.of(), PropertiesStructure.INSTANCE.locate("# only a comment\n\n"));
  }

  @Test
  void aKeyWrittenTwiceStaysTwoNodes() {
    var t = StructureBuilder.Located.of("a=1\na=2\n", PropertiesStructure.INSTANCE);
    List<StructureNode> nodes = StructureBuilder.build(t, t, t, t, List.of());
    assertEquals(List.of("key:a", "key:a#2"), nodes.stream().map(StructureNode::path).toList());
  }
}
