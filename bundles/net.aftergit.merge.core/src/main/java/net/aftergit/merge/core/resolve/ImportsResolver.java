package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.imports.ImportMerge;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.Strategy;

/**
 * Pass 1 of the pipeline (spec §5.1): conflicts in the import section. Takes only import blocks, and takes them
 * also with an empty base or an empty side: the import set is well defined then too.
 */
public final class ImportsResolver implements ConflictResolver {
  /** The switch "Java imports" (preferences design §4). */
  public static final String ID = "imports";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Strategy strategy() {
    return Strategy.IMPORTS;
  }

  @Override
  public Optional<String> tryResolve(String left, String base, String right) {
    return ImportMerge.merge(left, base, right);
  }

  @Override
  public boolean isApplicable(BlockContext ctx) {
    return ctx.block().region() == Region.IMPORTS;
  }
}
