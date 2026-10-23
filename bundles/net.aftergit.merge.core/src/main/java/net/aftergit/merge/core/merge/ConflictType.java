package net.aftergit.merge.core.merge;

/** Kind of a 3-way change and which sides changed relative to base. */
public record ConflictType(Kind kind, boolean leftChanged, boolean rightChanged) {

  public enum Kind { INSERTED, DELETED, MODIFIED, CONFLICT }

  public boolean isChange(ThreeSide side) {
    return switch (side) {
      case LEFT -> leftChanged;
      case BASE -> true;
      case RIGHT -> rightChanged;
    };
  }

  public boolean isConflict() {
    return kind == Kind.CONFLICT;
  }
}
