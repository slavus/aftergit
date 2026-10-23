package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.merge.WordMerge;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/**
 * Word-level differences inside a block (spec §6.4). Offsets are block-relative. An unresolved conflict compares its
 * sides with the base; a resolved one compares each side with its result. Ranges are trimmed of surrounding
 * whitespace, so a conflict whose sides differ only in whitespace yields no ranges and keeps its strong fill.
 */
final class InnerLine {
  private InnerLine() {}

  /**
   * Word ranges ({offset, length}) of the left side, the result pane ({@code base}: the base text of an open conflict,
   * the result of a resolved one) and the right side, and the insertion marks of each: the offsets where that text
   * lacks words the text it is compared with has.
   */
  record Ranges(List<int[]> left, List<int[]> base, List<int[]> right,
      List<Integer> leftMarks, List<Integer> baseMarks, List<Integer> rightMarks) {
    static final Ranges NONE = new Ranges(List.of(), List.of(), List.of());

    Ranges(List<int[]> left, List<int[]> base, List<int[]> right) {
      this(left, base, right, List.of(), List.of(), List.of());
    }

    boolean isEmpty() {
      return left.isEmpty() && base.isEmpty() && right.isEmpty()
          && leftMarks.isEmpty() && baseMarks.isEmpty() && rightMarks.isEmpty();
    }
  }

  /** The states of a conflict whose sides are compared with its result, not with the base. */
  static boolean comparesWithResult(MergeBlock b) {
    return b.isConflict() && switch (b.state()) {
      case TAKEN_LEFT, TAKEN_RIGHT, TAKEN_BOTH, EDITED, AUTO_RESOLVED, AI_RESOLVED -> true;
      default -> false;
    };
  }

  static Ranges compute(MergeModel model, int block) {
    MergeBlock b = model.blocks().get(block);
    if (comparesWithResult(b)) {
      return againstResult(model.sideText(block, ThreeSide.LEFT), model.resultText(block),
          model.sideText(block, ThreeSide.RIGHT));
    }
    if (!b.isConflict() || b.state() != BlockState.UNRESOLVED) {
      return Ranges.NONE;
    }
    String l = model.sideText(block, ThreeSide.LEFT);
    String base = b.baseText();
    String r = model.sideText(block, ThreeSide.RIGHT);
    List<MergeRange> ranges;
    try {
      ranges = WordMerge.threeWay(l, base, r);
    } catch (WordMerge.TooBigException e) {
      return Ranges.NONE;
    }
    List<int[]> ls = new ArrayList<>();
    List<int[]> bs = new ArrayList<>();
    List<int[]> rs = new ArrayList<>();
    for (MergeRange m : ranges) {
      add(ls, l, m.start1(), m.end1());
      add(bs, base, m.start2(), m.end2());
      add(rs, r, m.start3(), m.end3());
    }
    return new Ranges(List.copyOf(ls), List.copyOf(bs), List.copyOf(rs));
  }

  /**
   * Each side against the result of a resolved block: a side highlights the words in which it differs from the
   * result and marks where it lacks words of the result; the result highlights the words in which it differs from
   * at least one side and marks where it lacks words of one. Text equal to the result has nothing.
   */
  static Ranges againstResult(String left, String result, String right) {
    List<WordMerge.Fragment> lf;
    List<WordMerge.Fragment> rf;
    try {
      lf = WordMerge.twoWay(result, left);
      rf = WordMerge.twoWay(result, right);
    } catch (WordMerge.TooBigException e) {
      return Ranges.NONE;
    }
    List<int[]> ls = new ArrayList<>();
    List<int[]> rs = new ArrayList<>();
    List<int[]> res = new ArrayList<>();
    List<Integer> lm = new ArrayList<>();
    List<Integer> rm = new ArrayList<>();
    List<Integer> resm = new ArrayList<>();
    compare(lf, result, left, res, resm, ls, lm);
    compare(rf, result, right, res, resm, rs, rm);
    List<int[]> resultRanges = union(res);
    return new Ranges(List.copyOf(ls), resultRanges, List.copyOf(rs), marks(lm, ls, left),
        marks(resm, resultRanges, result), marks(rm, rs, right));
  }

  /** The words and marks of both texts of the fragments of {@code result} against {@code side}. */
  private static void compare(List<WordMerge.Fragment> fragments, String result, String side,
      List<int[]> resultWords, List<Integer> resultMarks, List<int[]> sideWords, List<Integer> sideMarks) {
    for (WordMerge.Fragment f : fragments) {
      boolean inResult = add(resultWords, result, f.start1(), f.end1());
      boolean inSide = add(sideWords, side, f.start2(), f.end2());
      if (inResult && !inSide) {
        sideMarks.add(f.end2());
      } else if (inSide && !inResult) {
        resultMarks.add(f.end1());
      }
    }
  }

  /** Sorted ranges, overlapping ones joined. */
  private static List<int[]> union(List<int[]> ranges) {
    List<int[]> sorted = new ArrayList<>(ranges);
    sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
    List<int[]> out = new ArrayList<>();
    for (int[] r : sorted) {
      int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
      if (last != null && r[0] <= last[0] + last[1]) {
        last[1] = Math.max(last[0] + last[1], r[0] + r[1]) - last[0];
      } else {
        out.add(new int[] {r[0], r[1]});
      }
    }
    return List.copyOf(out);
  }

  /**
   * Marks sorted and once each, none inside a highlighted word; a mark behind the block's last line end moves to the
   * end of its last line, so it shows on the block.
   */
  private static List<Integer> marks(List<Integer> marks, List<int[]> words, String text) {
    java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
    for (int m : marks) {
      if (m == text.length() && m > 0 && text.charAt(m - 1) == '\n') {
        m--;
      }
      int at = m;
      if (words.stream().noneMatch(w -> w[0] < at && at < w[0] + w[1])) {
        out.add(m);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Changed words of one side of a block against its base text, block-relative: the strong words of blocks that
   * are not open conflicts in the side panes (ui-polish design §2).
   */
  static List<int[]> sideWords(MergeModel model, int block, ThreeSide side) {
    MergeBlock b = model.blocks().get(block);
    String text = model.sideText(block, side);
    List<WordMerge.Fragment> fragments;
    try {
      fragments = WordMerge.twoWay(b.baseText(), text);
    } catch (WordMerge.TooBigException e) {
      return List.of();
    }
    List<int[]> out = new ArrayList<>();
    for (WordMerge.Fragment f : fragments) {
      add(out, text, f.start2(), f.end2());
    }
    return List.copyOf(out);
  }

  /** Word ranges come back padded with the whitespace between tokens; highlight only the words. */
  private static boolean add(List<int[]> out, String text, int start, int end) {
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    if (end > start) {
      out.add(new int[] {start, end - start});
      return true;
    }
    return false;
  }
}
