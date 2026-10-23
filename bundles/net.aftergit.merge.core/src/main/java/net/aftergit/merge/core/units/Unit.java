package net.aftergit.merge.core.units;

/**
 * One unit of a text: the lines [start, end) and the key it is matched by across base, ours and theirs (resolvers 6c
 * design). A unit's first lines are the blank lines and comments that separate it from the unit before.
 */
public record Unit(String key, int start, int end) {
  /** Key of the lines after the last unit of a body (blank lines, comments, the closing part). */
  public static final String TRAILING = "\u0000trailing";
}
