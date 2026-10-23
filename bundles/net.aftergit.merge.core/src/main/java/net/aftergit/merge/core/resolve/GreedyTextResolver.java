package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.model.Strategy;

public final class GreedyTextResolver implements ConflictResolver {
  @Override
  public String id() {
    return "greedy";
  }

  @Override
  public Strategy strategy() {
    return Strategy.GREEDY;
  }

  @Override
  public Optional<String> tryResolve(String left, String base, String right) {
    return GreedyResolver.tryResolve(left, base, right);
  }

  @Override
  public Optional<Resolution> tryResolveDetailed(String left, String base, String right) {
    return GreedyResolver.tryResolveDetailed(left, base, right);
  }
}
