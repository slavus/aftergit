package net.aftergit.merge.core.imports;

import java.util.Optional;

/**
 * Finds the import section of a source text; implemented outside core by a language parser (for Java in
 * net.aftergit.merge.jdt). Lines are counted as {@link net.aftergit.merge.core.text.LineText} counts them.
 */
public interface ImportLocator {
  /** The section, or empty when the text has none that covers whole lines; nothing is split then. */
  Optional<ImportSection> locate(String text);
}
