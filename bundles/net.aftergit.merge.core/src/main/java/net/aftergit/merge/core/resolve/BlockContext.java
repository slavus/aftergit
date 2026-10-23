package net.aftergit.merge.core.resolve;

import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/**
 * What a resolver sees of one conflict (spec §3.3, MergeContext): the model, the block and its three texts
 * (chunks: whole lines, each terminated by '\n'); {@code fileName}: the name the pipeline is bound to
 * ({@link ResolverPipeline#forFile}), or null.
 */
public record BlockContext(MergeModel model, MergeBlock block, String left, String base, String right,
    String fileName) {
  /** Without a file name. */
  public BlockContext(MergeModel model, MergeBlock block, String left, String base, String right) {
    this(model, block, left, base, right, null);
  }
}
