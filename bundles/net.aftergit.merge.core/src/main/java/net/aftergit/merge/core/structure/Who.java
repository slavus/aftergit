package net.aftergit.merge.core.structure;

/** Who changed a node, from comparing base with each side (structure-compare design §1, Ruling 10). */
public enum Who {
  NONE, OURS, THEIRS, BOTH, REMOVED_BY_OURS, REMOVED_BY_THEIRS, REMOVED_BY_BOTH;

  /** The node's own text in base, ours and theirs; null where the node is not. */
  public static Who of(String base, String ours, String theirs) {
    if (base != null && ours == null && theirs == null) {
      return REMOVED_BY_BOTH;
    }
    if (base != null && ours == null) {
      return REMOVED_BY_OURS;
    }
    if (base != null && theirs == null) {
      return REMOVED_BY_THEIRS;
    }
    boolean left = ours != null && !ours.equals(base);
    boolean right = theirs != null && !theirs.equals(base);
    return left && right ? BOTH : left ? OURS : right ? THEIRS : NONE;
  }
}
