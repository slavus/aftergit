package net.aftergit.merge.core.resolve;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.properties.PropertiesUnits;
import net.aftergit.merge.core.units.BlockRegion;
import net.aftergit.merge.core.units.Keyed;
import net.aftergit.merge.core.units.UnitMerge;

/**
 * Conflicts in .properties files, merged per key (resolvers 6c design §2). Exclusive: a block it cannot decide stays a
 * conflict, no text resolver merges the words of a key both sides changed.
 */
public final class PropertiesResolver implements ConflictResolver {
  public static final String ID = "properties";

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
    return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".properties");
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
    return BlockRegion.resolve(ctx, PropertiesUnits.INSTANCE,
        (b, o, t) -> UnitMerge.merge(b, o, t, UnitMerge.Policy.STRICT).filter(PropertiesResolver::noDanglingContinuation))
        .map(text -> new Resolution(text, false));
  }

  /** A unit ending in a continuation backslash would swallow the unit after it: only the last one may end so. */
  private static boolean noDanglingContinuation(List<Keyed> merged) {
    for (int i = 0; i < merged.size() - 1; i++) {
      if (PropertiesUnits.continues(merged.get(i).text().replaceAll("[\\r\\n]+$", ""))) {
        return false;
      }
    }
    return true;
  }
}
