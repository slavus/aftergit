package net.aftergit.merge.core.model;

/**
 * Raw merge inputs. {@code workingTree} (git's result with markers) may be null. Any of the stages
 * {@code base}, {@code ours}, {@code theirs} may be null when it cannot be read; the model is then
 * built from the conflict markers of {@code workingTree}, which must be non-null in that case.
 */
public record MergeInput(String base, String ours, String theirs, String workingTree) {

  /** True when at least one stage is missing. */
  public boolean hasMissingStage() {
    return base == null || ours == null || theirs == null;
  }
}
