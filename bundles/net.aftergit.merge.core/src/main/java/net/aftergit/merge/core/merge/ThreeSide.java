package net.aftergit.merge.core.merge;

/** The three inputs of a merge. LEFT = ours, BASE = common ancestor, RIGHT = theirs. */
public enum ThreeSide {
  LEFT, BASE, RIGHT;

  public <T> T select(T left, T base, T right) {
    return switch (this) {
      case LEFT -> left;
      case BASE -> base;
      case RIGHT -> right;
    };
  }
}
