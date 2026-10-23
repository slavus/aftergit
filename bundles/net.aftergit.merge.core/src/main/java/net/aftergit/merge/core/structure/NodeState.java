package net.aftergit.merge.core.structure;

/** The state a node's chip shows: the worst of its blocks (structure-compare design §1, Ruling 6). */
public enum NodeState {
  CONFLICT(0), AI_TO_REVIEW(1), AUTO_TO_REVIEW(2), EDITED(3), TAKEN_LEFT(4), TAKEN_RIGHT(4), TAKEN_BOTH(4),
  IGNORED(4), AI_REVIEWED(5), AUTO_REVIEWED(6), GIT(7), NONE(8);

  private final int rank;

  NodeState(int rank) {
    this.rank = rank;
  }

  /** Lower is worse. */
  public int rank() {
    return rank;
  }

  public static NodeState of(BlockFacts b) {
    return switch (b.state()) {
      case UNRESOLVED -> b.conflict() ? CONFLICT : GIT;
      case RESOLVED_BY_GIT -> GIT;
      case AUTO_RESOLVED -> b.needsReview() ? AUTO_TO_REVIEW : AUTO_REVIEWED;
      case AI_RESOLVED -> b.needsReview() ? AI_TO_REVIEW : AI_REVIEWED;
      case TAKEN_LEFT -> TAKEN_LEFT;
      case TAKEN_RIGHT -> TAKEN_RIGHT;
      case TAKEN_BOTH -> TAKEN_BOTH;
      case EDITED -> EDITED;
      case IGNORED -> IGNORED;
    };
  }
}
