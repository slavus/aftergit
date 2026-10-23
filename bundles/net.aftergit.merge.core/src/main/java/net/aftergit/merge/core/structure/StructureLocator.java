package net.aftergit.merge.core.structure;

import java.util.List;

/**
 * Finds the units of a whole text for the structure tree (structure-compare design §1); one per file format, the Java
 * one in net.aftergit.merge.jdt.
 */
@FunctionalInterface
public interface StructureLocator {
  /**
   * The top-level items of {@code text} (whole lines, each terminated by '\n'), in text order, children nested and in
   * order; an empty list when the text has none or cannot be read. May throw on a broken parser: the caller treats that
   * as no items.
   */
  List<StructureItem> locate(String text);
}
