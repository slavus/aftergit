package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.model.Strategy;

public final class SimpleTextResolver implements ConflictResolver {
  @Override
  public String id() {
    return "simple";
  }

  @Override
  public Strategy strategy() {
    return Strategy.TEXT;
  }

  @Override
  public Optional<String> tryResolve(String left, String base, String right) {
    return SimpleResolver.tryResolve(left, base, right);
  }

  @Override
  public Optional<Resolution> tryResolveDetailed(String left, String base, String right) {
    return SimpleResolver.tryResolveDetailed(left, base, right);
  }
}
