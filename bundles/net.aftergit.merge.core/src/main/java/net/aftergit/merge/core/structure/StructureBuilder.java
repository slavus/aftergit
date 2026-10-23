package net.aftergit.merge.core.structure;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;

/** The structure tree of a merge (structure-compare design §1): the union of base, ours, theirs and the result. */
public final class StructureBuilder {
  private StructureBuilder() {}

  /** One text and the items a locator found in it. */
  public record Located(LineText text, List<StructureItem> items) {
    public Located {
      items = List.copyOf(items);
    }

    public static Located of(String text, StructureLocator locator) {
      return new Located(LineText.of(text), locator.locate(text));
    }
  }

  private enum Text { BASE, OURS, THEIRS, RESULT }

  /** A whole LineText as a chunk, each line followed by '\n'. */
  public static String text(LineText t) {
    return t.chunk(0, t.size());
  }

  /** The tree of {@code m} now (on the model's thread). */
  public static List<StructureNode> build(MergeModel m, StructureLocator locator) {
    return build(Located.of(text(m.base()), locator), Located.of(text(m.left()), locator),
        Located.of(text(m.right()), locator), Located.of(m.document().chunk(0, m.document().lineCount()), locator),
        BlockFacts.of(m));
  }

  /** {@code blocks}: every block of the model, in index order. */
  public static List<StructureNode> build(Located base, Located ours, Located theirs, Located result,
      List<BlockFacts> blocks) {
    Map<Text, Located> texts = new EnumMap<>(Text.class);
    texts.put(Text.BASE, base);
    texts.put(Text.OURS, ours);
    texts.put(Text.THEIRS, theirs);
    texts.put(Text.RESULT, result);
    Map<Text, List<StructureItem>> items = new EnumMap<>(Text.class);
    texts.forEach((t, l) -> items.put(t, l.items()));
    return level("", items, texts, blocks);
  }

  private static List<StructureNode> level(String parent, Map<Text, List<StructureItem>> items,
      Map<Text, Located> texts, List<BlockFacts> blocks) {
    Map<Text, LinkedHashMap<String, StructureItem>> byKey = new EnumMap<>(Text.class);
    for (Text t : Text.values()) {
      byKey.put(t, unique(items.get(t)));
    }
    List<StructureNode> out = new ArrayList<>();
    for (String key : order(byKey)) {
      Map<Text, StructureItem> at = new EnumMap<>(Text.class);
      for (Text t : Text.values()) {
        StructureItem i = byKey.get(t).get(key);
        if (i != null) {
          at.put(t, i);
        }
      }
      StructureItem any = at.getOrDefault(Text.RESULT, at.getOrDefault(Text.OURS,
          at.getOrDefault(Text.THEIRS, at.get(Text.BASE))));
      String path = parent.isEmpty() ? key : parent + "/" + key;
      Map<Text, List<StructureItem>> kids = new EnumMap<>(Text.class);
      for (Text t : Text.values()) {
        kids.put(t, at.containsKey(t) ? at.get(t).children() : List.of());
      }
      List<StructureNode> children = level(path, kids, texts, blocks);
      Who who = Who.of(own(at, Text.BASE, texts), own(at, Text.OURS, texts), own(at, Text.THEIRS, texts));
      List<Integer> touching = touching(at, texts, blocks);
      NodeState worst = NodeState.NONE;
      int count = 0;
      int worstBlock = -1;
      for (int b : touching) {
        NodeState s = NodeState.of(blocks.get(b));
        if (s.rank() < worst.rank()) {
          worst = s;
          count = 1;
          worstBlock = b;
        } else if (s.rank() == worst.rank()) {
          count++;
        }
      }
      out.add(new StructureNode(path, any.kind(), any.label(), any.visibility(), who, at.containsKey(Text.RESULT),
          worst, count, worstBlock, touching, children));
    }
    return out;
  }

  /** Items by key in order; a key seen before in the same list gets "#2", "#3" (Ruling 8). */
  static LinkedHashMap<String, StructureItem> unique(List<StructureItem> items) {
    LinkedHashMap<String, StructureItem> out = new LinkedHashMap<>();
    Map<String, Integer> seen = new HashMap<>();
    for (StructureItem i : items) {
      int n = seen.merge(i.key(), 1, Integer::sum);
      out.put(n == 1 ? i.key() : i.key() + "#" + n, i);
    }
    return out;
  }

  /** Result order, then nodes only in ours, theirs, base, each after its predecessor there (Ruling 9). */
  private static List<String> order(Map<Text, LinkedHashMap<String, StructureItem>> byKey) {
    // a linked list with an index by key: inserting after a predecessor is constant time
    Map<String, Link> links = new HashMap<>();
    Link head = new Link(null);
    Link tail = head;
    for (String key : byKey.get(Text.RESULT).keySet()) {
      tail.next = new Link(key);
      tail = tail.next;
      links.put(key, tail);
    }
    for (Text t : List.of(Text.OURS, Text.THEIRS, Text.BASE)) {
      Link previous = head;
      for (String key : byKey.get(t).keySet()) {
        Link at = links.get(key);
        if (at == null) {
          at = new Link(key);
          at.next = previous.next;
          previous.next = at;
          links.put(key, at);
        }
        previous = at;
      }
    }
    List<String> out = new ArrayList<>(links.size());
    for (Link l = head.next; l != null; l = l.next) {
      out.add(l.key);
    }
    return out;
  }

  private static final class Link {
    final String key;
    Link next;

    Link(String key) {
      this.key = key;
    }
  }

  private static String own(Map<Text, StructureItem> at, Text t, Map<Text, Located> texts) {
    StructureItem i = at.get(t);
    return i == null ? null : i.ownText(texts.get(t).text());
  }

  /** The blocks that touch the node's own lines: in the result where it is there, else on its sides (Ruling 4). */
  private static List<Integer> touching(Map<Text, StructureItem> at, Map<Text, Located> texts,
      List<BlockFacts> blocks) {
    List<Text> where = at.containsKey(Text.RESULT) ? List.of(Text.RESULT)
        : List.of(Text.OURS, Text.THEIRS, Text.BASE);
    TreeSet<Integer> out = new TreeSet<>();
    for (Text t : where) {
      StructureItem item = at.get(t);
      if (item == null) {
        continue;
      }
      BitSet own = item.ownLines(texts.get(t).text());
      for (BlockFacts b : blocks) {
        if (overlaps(own, start(b, t), end(b, t))) {
          out.add(b.index());
        }
      }
    }
    return List.copyOf(out);
  }

  /** Non-empty: one of its lines is an own line; empty at p: lines p - 1 and p are both own lines (Ruling 4). */
  static boolean overlaps(BitSet own, int start, int end) {
    if (start < end) {
      int n = own.nextSetBit(start);
      return n >= 0 && n < end;
    }
    return start > 0 && own.get(start - 1) && own.get(start);
  }

  private static int start(BlockFacts b, Text t) {
    return switch (t) {
      case RESULT -> b.resultStart();
      case OURS -> b.source().start(ThreeSide.LEFT);
      case THEIRS -> b.source().start(ThreeSide.RIGHT);
      case BASE -> b.source().start(ThreeSide.BASE);
    };
  }

  private static int end(BlockFacts b, Text t) {
    return switch (t) {
      case RESULT -> b.resultEnd();
      case OURS -> b.source().end(ThreeSide.LEFT);
      case THEIRS -> b.source().end(ThreeSide.RIGHT);
      case BASE -> b.source().end(ThreeSide.BASE);
    };
  }
}
