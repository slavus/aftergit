package net.aftergit.merge.core.units;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.BlockContext;
import net.aftergit.merge.core.text.LineText;

/**
 * Resolves one conflict block by units (resolvers 6c design). The three versions of the file are the current result
 * with the block holding its base, ours and theirs text (lines outside the block are the same in all three); each is
 * split into units, the block is widened until no unit of any version crosses its edges, the units of that region are
 * merged, and the lines of the merged region outside the block must be the lines that are there now: the answer is
 * the block's new text, nothing else changes. Anything else leaves the block alone (empty), also: a body with a key
 * twice; a key of the region that a unit outside the region has, in any version or in the original base, ours or
 * theirs file (see {@link #regionLocal}: a key added twice, or moved, in another block); a merged result with a key
 * twice; a merged unit text that does not end with '\n'.
 *
 * <p>Because the lines outside the block are the current result in all three versions, a change git already merged
 * outside the block counts as base text: a unit that one side changed there (merged without a conflict) and the
 * other side changed inside the block is merged as changed on one side only, as a line merge would do.
 */
public final class BlockRegion {
  /** Merges the units of the region of base, ours and theirs; empty is a conflict. */
  @FunctionalInterface
  public interface Merger {
    Optional<List<Keyed>> merge(List<Keyed> base, List<Keyed> ours, List<Keyed> theirs);
  }

  /** Widening rounds before giving up; each round only grows the region. */
  static final int MAX_ROUNDS = 64;

  /** One version: all its lines, the end of the block in it, its body. */
  private record Version(List<String> lines, int blockEnd, Body body) {}

  private BlockRegion() {}

  public static Optional<String> resolve(BlockContext ctx, Segmenter segmenter, Merger merger) {
    MergeModel m = ctx.model();
    MergeBlock b = ctx.block();
    List<String> result = LineText.chunkLines(m.document().chunk(0, m.document().lineCount()));
    int s = b.resultStart();
    List<String> before = result.subList(0, s);
    List<String> after = result.subList(b.resultEnd(), result.size());
    String[] middles = {ctx.base(), ctx.left(), ctx.right()};
    Version[] v = new Version[3];
    for (int k = 0; k < 3; k++) {
      List<String> middle = LineText.chunkLines(middles[k]);
      List<String> lines = new ArrayList<>(before);
      lines.addAll(middle);
      lines.addAll(after);
      int blockEnd = s + middle.size();
      Optional<Body> body = segmenter.segment(chunk(lines, 0, lines.size()), s, blockEnd);
      if (body.isEmpty() || !body.get().tiles() || body.get().start() < 0 || body.get().start() > s
          || body.get().end() < blockEnd || body.get().end() > lines.size()) {
        return Optional.empty();
      }
      if (!uniqueKeys(body.get())) {
        return Optional.empty(); // a key twice: which unit another version's unit matches would be a guess
      }
      v[k] = new Version(lines, blockEnd, body.get());
    }
    for (Version x : v) {
      if (x.body().start() != v[0].body().start()
          || x.body().end() - x.blockEnd() != v[0].body().end() - v[0].blockEnd()) {
        return Optional.empty(); // not the same body in base, ours and theirs
      }
    }
    int start = s; // a line before the block: the same in every version
    int tail = 0; // lines of the region after the block: the same in every version
    for (int round = 0; ; round++) {
      if (round == MAX_ROUNDS) {
        return Optional.empty();
      }
      int newStart = start;
      int newTail = tail;
      for (Version x : v) {
        int end = x.blockEnd() + tail;
        for (Unit u : x.body().units()) {
          boolean cut = start < end ? u.start() < end && u.end() > start : u.start() < start && u.end() > start;
          if (cut) {
            newStart = Math.min(newStart, u.start());
            newTail = Math.max(newTail, u.end() - x.blockEnd());
          }
        }
      }
      if (newStart == start && newTail == tail) {
        break;
      }
      start = newStart;
      tail = newTail;
    }
    List<List<Keyed>> units = new ArrayList<>();
    Set<String> outside = new HashSet<>(); // keys of the units outside the region, in any version
    for (Version x : v) {
      int end = x.blockEnd() + tail;
      if (end > x.lines().size()) {
        return Optional.empty();
      }
      List<Keyed> list = new ArrayList<>();
      for (Unit u : x.body().units()) {
        if (u.start() >= start && u.end() <= end) {
          list.add(new Keyed(u.key(), chunk(x.lines(), u.start(), u.end())));
        } else if (!u.key().equals(Unit.TRAILING)) {
          outside.add(u.key());
        }
      }
      units.add(list);
    }
    for (List<Keyed> list : units) {
      for (Keyed k : list) {
        if (!k.key().equals(Unit.TRAILING) && outside.contains(k.key())) {
          return Optional.empty(); // the key also stands outside the region: a move, or added twice
        }
      }
    }
    if (!regionLocal(ctx, segmenter, units)) {
      return Optional.empty();
    }
    Optional<List<Keyed>> merged = merger.merge(units.get(0), units.get(1), units.get(2));
    if (merged.isEmpty()) {
      return Optional.empty();
    }
    Set<String> keys = new HashSet<>();
    for (Keyed k : merged.get()) {
      if (!k.text().isEmpty() && !k.text().endsWith("\n")) {
        return Optional.empty(); // it would run into the next unit's first line
      }
      if (!k.key().equals(Unit.TRAILING) && (outside.contains(k.key()) || !keys.add(k.key()))) {
        return Optional.empty(); // the file would hold the key twice
      }
    }
    StringBuilder sb = new StringBuilder();
    merged.get().forEach(k -> sb.append(k.text()));
    List<String> out = LineText.chunkLines(sb.toString());
    List<String> prefix = result.subList(start, s);
    List<String> suffix = after.subList(0, tail);
    if (out.size() < prefix.size() + suffix.size() || !out.subList(0, prefix.size()).equals(prefix)
        || !out.subList(out.size() - suffix.size(), out.size()).equals(suffix)) {
      return Optional.empty(); // the merge would rewrite lines outside the block
    }
    return Optional.of(chunk(out, prefix.size(), out.size() - suffix.size()));
  }

  /**
   * Whether every key of the region is local to it in the whole original file of each version: where the body around
   * the block in base, ours or theirs as they were holds the key, it holds it once and that version's region has it.
   * Otherwise the key also stands elsewhere in that side (added or moved there in another block) and merging this
   * block alone would duplicate it or lose a delete.
   */
  private static boolean regionLocal(BlockContext ctx, Segmenter segmenter, List<List<Keyed>> units) {
    MergeModel m = ctx.model();
    MergeRange r = ctx.block().source();
    LineText[] originals = {m.base(), m.left(), m.right()};
    int[][] spans = {{r.start2(), r.end2()}, {r.start1(), r.end1()}, {r.start3(), r.end3()}};
    Set<String> keys = new HashSet<>();
    units.forEach(list -> list.forEach(k -> keys.add(k.key())));
    keys.remove(Unit.TRAILING);
    for (int x = 0; x < 3; x++) {
      LineText text = originals[x];
      Optional<Body> body = segmenter.segment(text.chunk(0, text.size()), spans[x][0], spans[x][1]);
      if (body.isEmpty()) {
        return false;
      }
      Set<String> inRegion = new HashSet<>();
      units.get(x).forEach(k -> inRegion.add(k.key()));
      Set<String> seen = new HashSet<>();
      for (Unit u : body.get().units()) {
        if (keys.contains(u.key()) && (!inRegion.contains(u.key()) || !seen.add(u.key()))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean uniqueKeys(Body body) {
    Set<String> keys = new HashSet<>();
    for (Unit u : body.units()) {
      if (!keys.add(u.key())) {
        return false;
      }
    }
    return true;
  }

  private static String chunk(List<String> lines, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }
}
