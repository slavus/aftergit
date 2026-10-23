/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.WordMerge;

/**
 * Resolves a line conflict when, at word level, no region was changed differently by both sides.
 * Whitespace gaps between unchanged words are merged gap by gap; one gap changed differently by
 * both sides is a conflict too (ignored by the whitespace-insensitive pass, which keeps base).
 * Port of IntelliJ MergeResolveUtil.SimpleHelper.
 */
public final class SimpleResolver {
  private SimpleResolver() {}

  public static Optional<String> tryResolve(String left, String base, String right) {
    return tryResolveDetailed(left, base, right).map(ConflictResolver.Resolution::text);
  }

  /** Tries DEFAULT first, then IGNORE_WHITESPACES; the result says which one succeeded. */
  public static Optional<ConflictResolver.Resolution> tryResolveDetailed(String left, String base, String right) {
    try {
      String resolved = resolve(left, base, right, ComparisonPolicy.DEFAULT);
      if (resolved != null) {
        return Optional.of(new ConflictResolver.Resolution(resolved, false));
      }
      resolved = resolve(left, base, right, ComparisonPolicy.IGNORE_WHITESPACES);
      return Optional.ofNullable(resolved).map(t -> new ConflictResolver.Resolution(t, true));
    } catch (WordMerge.TooBigException e) {
      return Optional.empty();
    }
  }

  static String resolve(String left, String base, String right, ComparisonPolicy policy) {
    return WordResolution.resolve(left, base, right, policy, (l, b, r, p) -> null, false);
  }
}
