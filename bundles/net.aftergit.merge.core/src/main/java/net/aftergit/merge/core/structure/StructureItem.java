package net.aftergit.merge.core.structure;

import java.util.BitSet;
import java.util.List;
import net.aftergit.merge.core.text.LineText;

/**
 * One unit of one text as a locator finds it (structure-compare design §1): its key among its siblings, kind, label,
 * visibility, lines [start, end) and the units inside it. Children lie within their parent; siblings may share a line
 * (enum constants, manifest clauses). Lines are counted as LineText counts them. A range with start after end counts
 * as empty.
 */
public record StructureItem(String key, NodeKind kind, String label, Visibility visibility, int start, int end,
    List<StructureItem> children) {
  public StructureItem {
    children = List.copyOf(children);
  }

  public StructureItem(String key, NodeKind kind, String label, int start, int end) {
    this(key, kind, label, Visibility.PACKAGE, start, end, List.of());
  }

  /**
   * Its own lines (Ruling 3): a leaf owns [start, end); a container owns them without its children's lines and without
   * blank lines.
   */
  public BitSet ownLines(LineText text) {
    BitSet own = new BitSet();
    int from = Math.max(0, Math.min(start, text.size()));
    int to = Math.max(from, Math.min(end, text.size()));
    own.set(from, to); // a locator's inverted range is an empty node
    if (children.isEmpty()) {
      return own;
    }
    for (StructureItem c : children) {
      int cf = Math.max(0, c.start());
      if (cf < c.end()) {
        own.clear(cf, c.end());
      }
    }
    for (int i = own.nextSetBit(0); i >= 0; i = own.nextSetBit(i + 1)) {
      if (text.line(i).isBlank()) {
        own.clear(i);
      }
    }
    return own;
  }

  /** The text of its own lines, each followed by '\n'; what "who" compares. */
  public String ownText(LineText text) {
    StringBuilder sb = new StringBuilder();
    BitSet own = ownLines(text);
    for (int i = own.nextSetBit(0); i >= 0; i = own.nextSetBit(i + 1)) {
      sb.append(text.line(i)).append('\n');
    }
    return sb.toString();
  }
}
