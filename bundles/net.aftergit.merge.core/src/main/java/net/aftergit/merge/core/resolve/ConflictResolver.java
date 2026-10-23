package net.aftergit.merge.core.resolve;

import java.util.Optional;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.Strategy;

/** Tries to resolve one conflict. Inputs and output are chunks (lines terminated by '\n'). */
public interface ConflictResolver {
  String id();

  Strategy strategy();

  Optional<String> tryResolve(String left, String base, String right);

  /** Like {@link #tryResolve(String, String, String)}, but also says whether whitespace had to be ignored to resolve. */
  default Optional<Resolution> tryResolveDetailed(String left, String base, String right) {
    return tryResolve(left, base, right).map(text -> new Resolution(text, false));
  }

  /**
   * Whether this resolver may try the block of {@code ctx}. Default: a body conflict whose sides and base are all
   * non-empty; modify/delete (one side empty) and add/add (empty base) are never auto-resolved at text level,
   * because without both texts and a common ancestor any merge is a guess.
   */
  default boolean isApplicable(BlockContext ctx) {
    return ctx.block().region() == Region.BODY && !ctx.left().isEmpty() && !ctx.right().isEmpty()
        && !ctx.base().isEmpty();
  }

  /**
   * Whether this resolver may act in a file named {@code fileName} (its last path segment; null when unknown). The
   * pipeline asks once per run (see {@link ResolverPipeline#forFile}); default: every file.
   */
  default boolean appliesTo(String fileName) {
    return true;
  }

  /**
   * When true, a block this resolver is applicable to is left to it alone: when it does not resolve the block, no
   * later resolver tries it (resolvers 6c design: a file-format resolver that cannot decide never lets a text resolver
   * guess). Default: false.
   */
  default boolean exclusive() {
    return false;
  }

  /** Resolves the block of {@code ctx}; the default resolves its three texts with {@link #tryResolveDetailed}. */
  default Optional<Resolution> tryResolve(BlockContext ctx) {
    return tryResolveDetailed(ctx.left(), ctx.base(), ctx.right());
  }

  /**
   * A resolved chunk. {@code whitespaceIgnored}: only the whitespace-insensitive pass succeeded, so
   * conflicting whitespace-only edits were resolved by keeping one side's whitespace. {@code rationale}: why the block
   * was resolved so, kept on the block ({@link net.aftergit.merge.core.model.MergeBlock#rationale}); null for none.
   */
  record Resolution(String text, boolean whitespaceIgnored, String rationale) {
    public Resolution(String text, boolean whitespaceIgnored) {
      this(text, whitespaceIgnored, null);
    }
  }
}
