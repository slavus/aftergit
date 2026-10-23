package net.aftergit.merge.core.structure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1: union, who, worst state, order. */
class StructureBuilderTest {
  /** "name {" ... "}" is a container keyed "type:name", any other non-blank line a leaf keyed "m:" + its first word. */
  static final StructureLocator TOY = text -> {
    LineText t = LineText.of(text);
    return parse(t, new int[] {0});
  };

  private static List<StructureItem> parse(LineText t, int[] at) {
    List<StructureItem> out = new ArrayList<>();
    while (at[0] < t.size()) {
      String line = t.line(at[0]).strip();
      if (line.equals("}")) {
        return out; // the caller consumes the closing line
      }
      int start = at[0]++;
      if (line.isEmpty()) {
        continue;
      }
      String name = line.split("[ ={(]")[0];
      if (line.endsWith("{")) {
        List<StructureItem> kids = parse(t, at);
        at[0]++;
        out.add(new StructureItem("type:" + name, NodeKind.CLASS, name, Visibility.PUBLIC, start, at[0], kids));
      } else {
        out.add(new StructureItem("m:" + name, NodeKind.METHOD, name, start, start + 1));
      }
    }
    return out;
  }

  static MergeModel model(String base, String ours, String theirs) {
    LineText l = LineText.of(ours);
    LineText b = LineText.of(base);
    LineText r = LineText.of(theirs);
    return MergeModel.fromChanges(l, b, r, LineMerger.merge(l, b, r, ComparisonPolicy.DEFAULT));
  }

  /** "path who inResult state×count [blocks]" per node, children indented. */
  static List<String> describe(List<StructureNode> nodes) {
    List<String> out = new ArrayList<>();
    describe(nodes, "", out);
    return out;
  }

  private static void describe(List<StructureNode> nodes, String indent, List<String> out) {
    for (StructureNode n : nodes) {
      out.add(indent + n.path() + " " + n.who() + (n.inResult() ? "" : " removed") + " " + n.state()
          + (n.stateCount() > 1 ? "x" + n.stateCount() : "") + " " + n.blocks());
      describe(n.children(), indent + "  ", out);
    }
  }

  static final String BASE = "A {\n  keep()\n\n  both()\n\n  gone()\n\n  oursOnly()\n}\n";
  static final String OURS = "A {\n  keep()\n\n  both() ours\n\n  gone()\n\n  oursOnly() changed\n}\n";
  static final String THEIRS = "A {\n  keep()\n\n  both() theirs\n\n\n  oursOnly()\n}\n";

  @Test
  void theTreeIsTheUnionInResultOrderWithARemovedNodeAfterItsPredecessor() {
    MergeModel m = model(BASE, OURS, THEIRS);
    assertEquals(3, m.blocks().size(), "a conflict, theirs' deletion, ours' change: " + m.blocks());
    assertEquals(List.of(
        "type:A NONE NONE []",
        "  type:A/m:keep NONE NONE []",
        "  type:A/m:both BOTH CONFLICT [0]",
        "  type:A/m:gone REMOVED_BY_THEIRS removed GIT [1]",
        "  type:A/m:oursOnly OURS GIT [2]"), describe(StructureBuilder.build(m, TOY)));
  }

  @Test
  void aMemberTheTakeRemovedFromTheResultTakesItsStateFromTheSides() {
    MergeModel m = model("A {\n  x()\n\n  y()\n}\n", "A {\n  x() o\n\n  y()\n}\n", "A {\n\n  y()\n}\n");
    m.take(0, ThreeSide.RIGHT); // theirs deleted x: the result has no x any more
    assertEquals(List.of(
        "type:A NONE NONE []",
        "  type:A/m:x REMOVED_BY_THEIRS removed TAKEN_RIGHT [0]",
        "  type:A/m:y NONE NONE []"), describe(StructureBuilder.build(m, TOY)));
  }

  static StructureBuilder.Located located(String text, StructureItem... items) {
    return new StructureBuilder.Located(LineText.of(text), List.of(items));
  }

  static BlockFacts block(int index, BlockState state, boolean review, int start, int end) {
    return new BlockFacts(index, state, true, review, new MergeRange(start, end, start, end, start, end), start, end);
  }

  @Test
  void theWorstStateWinsAndCountsTheBlocksInIt() {
    var text = located("a\nb\nc\nd\n", new StructureItem("m:m", NodeKind.METHOD, "m", 1, 4));
    List<StructureNode> nodes = StructureBuilder.build(text, text, text, text, List.of(
        block(0, BlockState.AUTO_RESOLVED, true, 1, 2),
        block(1, BlockState.UNRESOLVED, false, 2, 3),
        block(2, BlockState.UNRESOLVED, false, 3, 4),
        block(3, BlockState.TAKEN_LEFT, false, 0, 1)));
    StructureNode n = nodes.get(0);
    assertEquals(NodeState.CONFLICT, n.state());
    assertEquals(2, n.stateCount(), "conflict ×2");
    assertEquals(1, n.worstBlock());
    assertEquals(List.of(0, 1, 2), n.blocks(), "block 3 lies before the node");

    nodes = StructureBuilder.build(text, text, text, text, List.of(
        block(0, BlockState.AUTO_RESOLVED, false, 1, 2),
        block(1, BlockState.AI_RESOLVED, false, 2, 3),
        block(2, BlockState.TAKEN_LEFT, false, 3, 4)));
    assertEquals(NodeState.TAKEN_LEFT, nodes.get(0).state());
    assertEquals(1, nodes.get(0).stateCount());
    assertEquals(2, nodes.get(0).worstBlock());
  }

  @Test
  void ranksFollowTheDesignOrder() {
    List<NodeState> order = List.of(NodeState.CONFLICT, NodeState.AI_TO_REVIEW, NodeState.AUTO_TO_REVIEW,
        NodeState.EDITED, NodeState.TAKEN_LEFT, NodeState.AI_REVIEWED, NodeState.AUTO_REVIEWED, NodeState.GIT,
        NodeState.NONE);
    for (int i = 1; i < order.size(); i++) {
      assertTrue(order.get(i - 1).rank() < order.get(i).rank(), order.get(i - 1) + " before " + order.get(i));
    }
    assertEquals(NodeState.TAKEN_LEFT.rank(), NodeState.TAKEN_RIGHT.rank());
    assertEquals(NodeState.TAKEN_LEFT.rank(), NodeState.TAKEN_BOTH.rank());
    assertEquals(NodeState.TAKEN_LEFT.rank(), NodeState.IGNORED.rank(), "Ruling 6");
    assertEquals(NodeState.GIT, NodeState.of(new BlockFacts(0, BlockState.UNRESOLVED, false, false,
        new MergeRange(0, 1, 0, 1, 0, 1), 0, 1)), "a change that never was a conflict");
  }

  @Test
  void anEmptyBlockTouchesANodeOnlyStrictlyInside() {
    BitSet own = new BitSet();
    own.set(1, 4);
    assertTrue(StructureBuilder.overlaps(own, 2, 3));
    assertFalse(StructureBuilder.overlaps(own, 4, 5));
    assertTrue(StructureBuilder.overlaps(own, 2, 2), "lines 1 and 2 are the node's");
    assertFalse(StructureBuilder.overlaps(own, 1, 1), "before its first line");
    assertFalse(StructureBuilder.overlaps(own, 4, 4), "after its last line");
    assertFalse(StructureBuilder.overlaps(own, 0, 0));
  }

  @Test
  void aRenamedMemberIsRemovedPlusAdded() {
    var base = located("old\n", new StructureItem("m:old", NodeKind.METHOD, "old", 0, 1));
    var ours = located("new\n", new StructureItem("m:new", NodeKind.METHOD, "new", 0, 1));
    List<StructureNode> nodes = StructureBuilder.build(base, ours, base, ours, List.of());
    assertEquals(List.of("m:old REMOVED_BY_OURS removed NONE []", "m:new OURS NONE []"), describe(nodes));
  }

  @Test
  void addedOnBothSidesIsBothAndRemovedOnBothIsRemovedByBoth() {
    var none = located("");
    var a = located("z a\n", new StructureItem("m:z", NodeKind.METHOD, "z", 0, 1));
    var b = located("z b\n", new StructureItem("m:z", NodeKind.METHOD, "z", 0, 1));
    assertEquals(Who.BOTH, StructureBuilder.build(none, a, b, a, List.of()).get(0).who());
    var w = located("w\n", new StructureItem("m:w", NodeKind.METHOD, "w", 0, 1));
    StructureNode gone = StructureBuilder.build(w, none, none, none, List.of()).get(0);
    assertEquals(Who.REMOVED_BY_BOTH, gone.who());
    assertFalse(gone.inResult());
  }

  @Test
  void aContainerIsJudgedByItsOwnNonBlankLines() {
    String base = "A {\n  x()\n}\n";
    String ours = "A {\n\n  x() changed\n}\n"; // a blank line and a change of the member
    var b = StructureBuilder.Located.of(base, TOY);
    var o = StructureBuilder.Located.of(ours, TOY);
    StructureNode a = StructureBuilder.build(b, o, b, o, List.of()).get(0);
    assertEquals(Who.NONE, a.who(), "the class's own lines did not change");
    assertEquals(Who.OURS, a.children().get(0).who());
    assertTrue(a.shownWhenFiltered(), "the parent of a changed member stays visible");
    assertFalse(a.changed());
  }

  @Test
  void twoMembersWithTheSameKeyInOneTextAreBothShown() {
    var side = located("x\n", new StructureItem("m:x", NodeKind.METHOD, "x", 0, 1));
    var result = located("x\nx\n", new StructureItem("m:x", NodeKind.METHOD, "x", 0, 1),
        new StructureItem("m:x", NodeKind.METHOD, "x", 1, 2));
    List<StructureNode> nodes = StructureBuilder.build(side, side, side, result,
        List.of(block(0, BlockState.TAKEN_BOTH, false, 0, 2)));
    assertEquals(List.of("m:x NONE TAKEN_BOTH [0]", "m:x#2 NONE TAKEN_BOTH [0]"), describe(nodes));
  }

  @Test
  void theFilterKeepsChangedNodesAndTheirParents() {
    MergeModel m = model(BASE, OURS, THEIRS);
    List<StructureNode> a = StructureBuilder.build(m, TOY).get(0).children();
    assertFalse(a.get(0).shownWhenFiltered(), "keep() is unchanged");
    assertTrue(a.get(1).shownWhenFiltered());
  }

  @Test
  void aSecondClickCyclesThroughTheNodesBlocks() {
    StructureNode n = new StructureNode("m", NodeKind.METHOD, "m", Visibility.PUBLIC, Who.BOTH, true,
        NodeState.CONFLICT, 1, 2, List.of(2, 5, 7), List.of());
    assertEquals(2, n.blockAfter(-1));
    assertEquals(5, n.blockAfter(2));
    assertEquals(2, n.blockAfter(7));
    assertEquals(2, n.blockAfter(4), "a current block of another node starts at the first");
    assertEquals(-1, new StructureNode("p", NodeKind.CLASS, "p", Visibility.PUBLIC, Who.NONE, true, NodeState.NONE,
        0, -1, List.of(), List.of()).blockAfter(3));
  }

  @Test
  void ignoredCountsAsTakenAndMixedTakesOnOneNodeCountTogether() {
    var text = located("a\nb\nc\nd\n", new StructureItem("m:m", NodeKind.METHOD, "m", 0, 4));
    List<StructureNode> nodes = StructureBuilder.build(text, text, text, text, List.of(
        block(0, BlockState.TAKEN_LEFT, false, 0, 1),
        block(1, BlockState.TAKEN_RIGHT, false, 1, 2),
        block(2, BlockState.IGNORED, false, 2, 3),
        block(3, BlockState.AI_RESOLVED, false, 3, 4)));
    assertEquals(NodeState.TAKEN_LEFT, nodes.get(0).state(), "the first of the equal ranks");
    assertEquals(3, nodes.get(0).stateCount());
    assertEquals(0, nodes.get(0).worstBlock());

    nodes = StructureBuilder.build(text, text, text, text, List.of(
        block(0, BlockState.IGNORED, false, 0, 1), block(1, BlockState.AUTO_RESOLVED, false, 1, 2)));
    assertEquals(NodeState.IGNORED, nodes.get(0).state());
    assertEquals(1, nodes.get(0).stateCount());
  }

  @Test
  void aChangeOfAContainersOwnLinesIsAChangeOfTheContainer() {
    var b = StructureBuilder.Located.of("A {\n  x()\n}\n", TOY);
    var header = StructureBuilder.Located.of("A  {\n  x()\n}\n", TOY);
    var closing = StructureBuilder.Located.of("A {\n  x()\n} \n", TOY);
    StructureNode a = StructureBuilder.build(b, header, b, header, List.of()).get(0);
    assertEquals(Who.OURS, a.who(), "the header line");
    assertEquals(Who.NONE, a.children().get(0).who());
    a = StructureBuilder.build(b, b, closing, closing, List.of()).get(0);
    assertEquals(Who.THEIRS, a.who(), "the closing brace");
    assertEquals(Who.NONE, a.children().get(0).who());
  }

  @Test
  void anEmptyBlockInsideANodeTouchesItOnTheModel() {
    StructureLocator whole = text -> List.of(
        new StructureItem("m:all", NodeKind.METHOD, "all", 0, LineText.of(text).size()));
    String base = "a\nb\nc\n";
    MergeModel m = model(base, "a\nc\n", base); // ours deletes b: an empty block between a and c in the result
    StructureNode n = StructureBuilder.build(m, whole).get(0);
    assertEquals(Who.OURS, n.who());
    assertEquals(1, n.blocks().size(), "the deletion lies strictly inside the node");
    assertEquals(0, n.blocks().get(0));
  }

  @Test
  void aNodeAddedOnOneSideWithoutABaseIsThatSides() {
    assertEquals(Who.THEIRS, Who.of(null, null, "x\n"));
    assertEquals(Who.OURS, Who.of(null, "x\n", null));
    var none = located("");
    var added = located("z\n", new StructureItem("m:z", NodeKind.METHOD, "z", 0, 1));
    assertEquals(Who.THEIRS, StructureBuilder.build(none, none, added, added, List.of()).get(0).who());
    assertEquals(Who.OURS, StructureBuilder.build(none, added, none, added, List.of()).get(0).who());
  }

  @Test
  void aLocatorReturningAnInvertedRangeGivesAnEmptyNodeInsteadOfThrowing() {
    var text = LineText.of("a\nb\nc\n");
    assertTrue(new StructureItem("k", NodeKind.METHOD, "k", 3, 1).ownLines(text).isEmpty());
    assertTrue(new StructureItem("k", NodeKind.METHOD, "k", -2, 1).ownLines(text).get(0));
    var parent = new StructureItem("p", NodeKind.CLASS, "p", Visibility.PUBLIC, 0, 3,
        List.of(new StructureItem("c", NodeKind.METHOD, "c", 2, 1)));
    assertEquals("a\nb\nc\n", parent.ownText(text));
  }

  @Test
  void theOrderOfAHugeTextIsNotQuadratic() {
    int n = 60000;
    List<StructureItem> items = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      items.add(new StructureItem("key:k" + i, NodeKind.PROPERTY, "k" + i, i, i + 1));
      sb.append("k").append(i).append("=v\n");
    }
    var full = new StructureBuilder.Located(LineText.of(sb.toString()), items);
    var empty = located("");
    var nodes = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
        () -> StructureBuilder.build(full, full, full, empty, List.of()));
    assertEquals(n, nodes.size());
    assertEquals("key:k0", nodes.get(0).path());
    assertEquals("key:k" + (n - 1), nodes.get(n - 1).path());
  }
}
