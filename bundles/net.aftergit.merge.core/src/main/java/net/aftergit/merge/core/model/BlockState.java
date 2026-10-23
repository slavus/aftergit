package net.aftergit.merge.core.model;

/** Lifecycle state of a merge block (spec §3.3). */
public enum BlockState {
  UNRESOLVED, RESOLVED_BY_GIT, AUTO_RESOLVED, AI_RESOLVED, TAKEN_LEFT, TAKEN_RIGHT, TAKEN_BOTH, IGNORED, EDITED;

  public boolean isResolved() {
    return this != UNRESOLVED;
  }
}
