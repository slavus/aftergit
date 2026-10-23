package net.aftergit.merge.core.units;

import java.util.Optional;

/** Splits a whole text into units around a block (resolvers 6c design); implemented per file format. */
@FunctionalInterface
public interface Segmenter {
  /**
   * The body of {@code text} (whole lines, each terminated by '\n') that holds the lines [from, to), or empty when the
   * block cannot be resolved by units there (no such body, text that cannot be parsed). With from == to the block is
   * the position before line {@code from}. Lines are counted as LineText counts them.
   */
  Optional<Body> segment(String text, int from, int to);
}
