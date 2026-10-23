package net.aftergit.merge.core.model;

import java.util.Set;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolveReport;

/**
 * Outcome of {@link HybridInitializer#initialize}. {@code imports}: the import sections the merge was split at, or
 * null (no locator, a side without a section, or the marker fallback). These are the sections as located: when a
 * side's section is empty, the IMPORTS blocks also cover the blank lines around each section (see
 * {@link net.aftergit.merge.core.imports.ImportSplit#merge}), so a block's range need not equal a section.
 * {@code sidesFromMarkers}: the sides the marker fallback built from git's conflict markers because their stage
 * was missing or did not hold the text of every hunk (spec 4.3.5); the model's text of such a side is what the
 * markers show, not the stage.
 */
public record InitResult(MergeModel model, boolean usedFallback, int resolvedByGit, ResolveReport autoReport,
    ImportSections imports, Set<ThreeSide> sidesFromMarkers) {
  public InitResult {
    sidesFromMarkers = Set.copyOf(sidesFromMarkers);
  }

  public InitResult(MergeModel model, boolean usedFallback, int resolvedByGit, ResolveReport autoReport) {
    this(model, usedFallback, resolvedByGit, autoReport, null, Set.of());
  }

  public InitResult(MergeModel model, boolean usedFallback, int resolvedByGit, ResolveReport autoReport,
      ImportSections imports) {
    this(model, usedFallback, resolvedByGit, autoReport, imports, Set.of());
  }
}
