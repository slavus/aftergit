package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.NodeState;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.core.structure.Visibility;
import net.aftergit.merge.core.structure.Who;
import net.aftergit.merge.ui.StructureLabels.Chip;
import net.aftergit.merge.ui.StructureLabels.ChipStyle;
import org.junit.jupiter.api.Test;

/** Structure-compare design §2: who, chip and tooltip of a row, as the mockup shows them. */
class StructureLabelsTest {
  static StructureNode node(NodeState state, int count) {
    boolean none = state == NodeState.NONE;
    return new StructureNode("m", NodeKind.METHOD, "m()", Visibility.PUBLIC, Who.BOTH, true, state, count,
        none ? -1 : 0, none ? List.of() : List.of(0), List.of());
  }

  static Chip chip(NodeState s, PaneStyle.Origin o) {
    return StructureLabels.chip(node(s, 1), o);
  }

  @Test
  void chipsFollowTheMockup() {
    assertEquals(new Chip("conflict", ChipStyle.CONFLICT), chip(NodeState.CONFLICT, PaneStyle.Origin.NONE));
    assertEquals("conflict ×2", StructureLabels.chip(node(NodeState.CONFLICT, 2), PaneStyle.Origin.NONE).text());
    assertEquals(new Chip("A ◆", ChipStyle.AUTO_FILLED), chip(NodeState.AUTO_TO_REVIEW, PaneStyle.Origin.BOTH));
    assertEquals(new Chip("A ▶", ChipStyle.AUTO_OUTLINED), chip(NodeState.AUTO_REVIEWED, PaneStyle.Origin.RIGHT));
    assertEquals(new Chip("A ◀", ChipStyle.AUTO_FILLED), chip(NodeState.AUTO_TO_REVIEW, PaneStyle.Origin.LEFT));
    assertEquals(new Chip("✨ AI", ChipStyle.AI_FILLED), chip(NodeState.AI_TO_REVIEW, PaneStyle.Origin.NONE));
    assertEquals(new Chip("✨ AI", ChipStyle.AI_OUTLINED), chip(NodeState.AI_REVIEWED, PaneStyle.Origin.NONE));
    assertEquals(new Chip("◀", ChipStyle.TAKEN), chip(NodeState.TAKEN_LEFT, PaneStyle.Origin.LEFT));
    assertEquals(new Chip("▶", ChipStyle.TAKEN), chip(NodeState.TAKEN_RIGHT, PaneStyle.Origin.RIGHT));
    assertEquals(new Chip("◆", ChipStyle.TAKEN), chip(NodeState.TAKEN_BOTH, PaneStyle.Origin.BOTH));
    assertEquals(new Chip("✎", ChipStyle.EDITED), chip(NodeState.EDITED, PaneStyle.Origin.EDITED));
    assertEquals(new Chip("ignored", ChipStyle.GREY), chip(NodeState.IGNORED, PaneStyle.Origin.NONE));
    assertEquals(new Chip("git", ChipStyle.GREY), chip(NodeState.GIT, PaneStyle.Origin.NONE));
    assertEquals(new Chip("", ChipStyle.NONE), chip(NodeState.NONE, PaneStyle.Origin.NONE));
  }

  static StructureNode container(NodeKind kind, NodeState state, List<StructureNode> children) {
    return new StructureNode("t", kind, "T", Visibility.PUBLIC, Who.BOTH, true, state, 1, 0, List.of(0), children);
  }

  @Test
  void aContainerShowsNoGitChipButItsOtherStates() {
    List<StructureNode> member = List.of(node(NodeState.NONE, 0));
    assertEquals(new Chip("", ChipStyle.NONE), StructureLabels.chip(container(NodeKind.CLASS, NodeState.GIT, member),
        PaneStyle.Origin.NONE), "a type merged by git stays quiet");
    assertEquals(new Chip("", ChipStyle.NONE), StructureLabels.chip(container(NodeKind.RECORD, NodeState.GIT,
        List.of()), PaneStyle.Origin.NONE), "also a type without members");
    assertEquals(new Chip("", ChipStyle.NONE), StructureLabels.chip(container(NodeKind.HEADER, NodeState.GIT, member),
        PaneStyle.Origin.NONE), "a header with clauses");
    assertEquals(new Chip("conflict", ChipStyle.CONFLICT), StructureLabels.chip(container(NodeKind.CLASS,
        NodeState.CONFLICT, member), PaneStyle.Origin.NONE));
    assertEquals(new Chip("A ▶", ChipStyle.AUTO_FILLED), StructureLabels.chip(container(NodeKind.CLASS,
        NodeState.AUTO_TO_REVIEW, member), PaneStyle.Origin.RIGHT));
    assertEquals(new Chip("✨ AI", ChipStyle.AI_FILLED), StructureLabels.chip(container(NodeKind.INTERFACE,
        NodeState.AI_TO_REVIEW, member), PaneStyle.Origin.NONE));
    assertEquals(new Chip("◀", ChipStyle.TAKEN), StructureLabels.chip(container(NodeKind.ENUM, NodeState.TAKEN_LEFT,
        member), PaneStyle.Origin.LEFT));
    assertEquals(new Chip("✎", ChipStyle.EDITED), StructureLabels.chip(container(NodeKind.CLASS, NodeState.EDITED,
        member), PaneStyle.Origin.EDITED));
    assertEquals(new Chip("git", ChipStyle.GREY), StructureLabels.chip(container(NodeKind.HEADER, NodeState.GIT,
        List.of()), PaneStyle.Origin.NONE), "a header without clauses is a leaf");
    assertEquals(new Chip("git", ChipStyle.GREY), chip(NodeState.GIT, PaneStyle.Origin.NONE), "a leaf keeps git");
  }

  @Test
  void whoIsShortAndTheImportsAreNamedByTheUi() {
    assertEquals("ours", StructureLabels.who(Who.OURS));
    assertEquals("both", StructureLabels.who(Who.BOTH));
    assertEquals("removed by theirs", StructureLabels.who(Who.REMOVED_BY_THEIRS));
    assertEquals("", StructureLabels.who(Who.NONE));
    StructureNode imports = new StructureNode("imports", NodeKind.IMPORTS, "", Visibility.PACKAGE, Who.OURS, true,
        NodeState.NONE, 0, -1, List.of(), List.of());
    assertEquals("imports", StructureLabels.label(imports));
  }

  @Test
  void theTooltipNamesWhoAndTheStateOfEachBlock() {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n",
        "a\nR1\nc\nR3\ne\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    StructureNode n = new StructureNode("m", NodeKind.METHOD, "m()", Visibility.PUBLIC, Who.BOTH, true,
        NodeState.CONFLICT, 2, 0, List.of(0, 1), List.of());
    assertEquals("Changed by both\nBlock 1: " + StatusText.state(m.blocks().get(0)) + "\nBlock 2: "
        + StatusText.state(m.blocks().get(1)), StructureLabels.tooltip(n, m));
  }
}
