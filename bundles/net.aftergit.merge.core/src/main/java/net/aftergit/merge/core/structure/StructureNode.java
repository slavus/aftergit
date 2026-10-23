package net.aftergit.merge.core.structure;

import java.util.List;

/**
 * One node of the structure tree (structure-compare design §1): {@code path} is unique in the tree (parent path, "/",
 * key); {@code worstBlock} is the first block in the worst state, -1 without blocks; {@code blocks} are the indexes of
 * the blocks that touch its own lines, ascending.
 */
public record StructureNode(String path, NodeKind kind, String label, Visibility visibility, Who who,
    boolean inResult, NodeState state, int stateCount, int worstBlock, List<Integer> blocks,
    List<StructureNode> children) {
  public StructureNode {
    blocks = List.copyOf(blocks);
    children = List.copyOf(children);
  }

  /** Changed by a side or touched by a block: shown with "Show all members" off (design §2). */
  public boolean changed() {
    return who != Who.NONE || !blocks.isEmpty();
  }

  /** Changed, or the parent of a changed node. */
  public boolean shownWhenFiltered() {
    return changed() || children.stream().anyMatch(StructureNode::shownWhenFiltered);
  }

  /** The block a click goes to (design §2): the first, or the one after {@code current} when it is one of these. */
  public int blockAfter(int current) {
    if (blocks.isEmpty()) {
      return -1;
    }
    int i = blocks.indexOf(current);
    return i < 0 ? blocks.get(0) : blocks.get((i + 1) % blocks.size());
  }
}
