package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.manifest.ManifestPolicy;
import net.aftergit.merge.core.manifest.ManifestUnits;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.units.BlockRegion;
import net.aftergit.merge.core.units.UnitMerge;

/**
 * Conflicts in MANIFEST.MF (and SOURCE-MANIFEST.MF, the manifest of a source bundle), merged per header and per list
 * clause (resolvers 6c design §3). Exclusive.
 */
public final class ManifestResolver implements ConflictResolver {
  public static final String ID = "manifest";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Strategy strategy() {
    return Strategy.SEMANTIC;
  }

  /** Needs the whole file: see {@link #tryResolve(BlockContext)}. */
  @Override
  public Optional<String> tryResolve(String left, String base, String right) {
    return Optional.empty();
  }

  @Override
  public boolean appliesTo(String fileName) {
    return "MANIFEST.MF".equals(fileName) || "SOURCE-MANIFEST.MF".equals(fileName);
  }

  @Override
  public boolean exclusive() {
    return true;
  }

  @Override
  public boolean isApplicable(BlockContext ctx) {
    return ctx.block().region() == Region.BODY;
  }

  @Override
  public Optional<Resolution> tryResolve(BlockContext ctx) {
    return BlockRegion.resolve(ctx, ManifestUnits.INSTANCE,
        (b, o, t) -> UnitMerge.merge(b, o, t, ManifestPolicy.INSTANCE)).map(text -> new Resolution(text, false));
  }
}
