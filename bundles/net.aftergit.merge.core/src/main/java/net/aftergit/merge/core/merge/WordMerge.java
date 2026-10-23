package net.aftergit.merge.core.merge;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.Diffs;
import net.aftergit.merge.core.diff.Range;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.diff.WordTokenizer.Token;

/** Word-level 3-way and 2-way comparison producing character offsets. */
public final class WordMerge {
  /** Above this many tokens on any side word-level work is refused. */
  public static final int MAX_TOKENS = 100_000;

  /** Input too large for word-level comparison. */
  public static final class TooBigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TooBigException(int tokens) {
      super("Too many tokens for word-level merge: " + tokens);
    }
  }

  /** A 2-way change: [start1, end1) in base, [start2, end2) in the other text. */
  public record Fragment(int start1, int end1, int start2, int end2) {}

  private WordMerge() {}

  public static List<MergeRange> threeWay(CharSequence left, CharSequence base, CharSequence right) {
    List<Token> tl = tokens(left);
    List<Token> tb = tokens(base);
    List<Token> tr = tokens(right);
    List<String> sb = WordTokenizer.texts(tb);
    List<MergeRange> tokenRanges = FairMergeBuilder.build(
        Diffs.unchanged(sb, WordTokenizer.texts(tl)), Diffs.unchanged(sb, WordTokenizer.texts(tr)),
        tl.size(), tb.size(), tr.size(), null);
    List<MergeRange> result = new ArrayList<>(tokenRanges.size());
    for (MergeRange m : tokenRanges) {
      result.add(new MergeRange(
          charStart(tl, m.start1()), charEnd(tl, m.end1(), left.length()),
          charStart(tb, m.start2()), charEnd(tb, m.end2(), base.length()),
          charStart(tr, m.start3()), charEnd(tr, m.end3(), right.length())));
    }
    return result;
  }

  public static List<Fragment> twoWay(CharSequence base, CharSequence other) {
    return twoWay(base, other, ComparisonPolicy.DEFAULT);
  }

  /**
   * 2-way char fragments. Every gap between consecutive matched tokens (and before the first and
   * after the last) whose texts differ by {@code policy} becomes one fragment, so applying all
   * fragments to {@code base} yields exactly {@code other} (with DEFAULT policy). Fragments never
   * split a token: a pure insertion keeps the base whitespace in front of it, a pure deletion keeps
   * the leading whitespace that remains in {@code other}, and a replacement drops only whitespace
   * common to both ends (none when the base side would be left with whitespace alone, so that
   * insertions into one whitespace gap from both sides still overlap).
   */
  public static List<Fragment> twoWay(CharSequence base, CharSequence other, ComparisonPolicy policy) {
    List<Token> ta = tokens(base);
    List<Token> tb = tokens(other);
    List<Fragment> result = new ArrayList<>();
    int prevA = 0;
    int prevB = 0;
    for (Range u : Diffs.unchanged(WordTokenizer.texts(ta), WordTokenizer.texts(tb))) {
      for (int k = 0; k < u.end1() - u.start1(); k++) {
        Token a = ta.get(u.start1() + k);
        Token b = tb.get(u.start2() + k);
        addGap(result, base, prevA, a.start(), other, prevB, b.start(), policy);
        prevA = a.end();
        prevB = b.end();
      }
    }
    addGap(result, base, prevA, base.length(), other, prevB, other.length(), policy);
    return result;
  }

  private static void addGap(List<Fragment> result, CharSequence a, int a0, int a1,
                             CharSequence b, int b0, int b1, ComparisonPolicy policy) {
    String gapA = a.subSequence(a0, a1).toString();
    String gapB = b.subSequence(b0, b1).toString();
    if (sameWords(gapA, gapB, policy)) {
      return;
    }
    if (gapA.isBlank() && gapB.startsWith(gapA)) {
      // insertion after the base whitespace
      result.add(new Fragment(a1, a1, b0 + gapA.length(), b1));
    } else if (gapB.isBlank() && gapA.startsWith(gapB)) {
      // deletion keeping the leading whitespace
      result.add(new Fragment(a0 + gapB.length(), a1, b1, b1));
    } else {
      // replacement: drop whitespace common to both ends, unless only whitespace of base would remain
      int s0 = a0;
      int t0 = b0;
      int s1 = a1;
      int t1 = b1;
      while (s0 < s1 && t0 < t1 && a.charAt(s0) == b.charAt(t0) && Character.isWhitespace(a.charAt(s0))) {
        s0++;
        t0++;
      }
      // trailing whitespace is dropped only when both runs are identical, so extra whitespace is
      // always placed after the base whitespace, as for insertions
      int runA = trailingWhitespace(a, s0, s1);
      int runB = trailingWhitespace(b, t0, t1);
      if (runA == runB
          && a.subSequence(s1 - runA, s1).toString().equals(b.subSequence(t1 - runB, t1).toString())) {
        s1 -= runA;
        t1 -= runB;
      }
      if (a.subSequence(s0, s1).toString().isBlank()) {
        result.add(new Fragment(a0, a1, b0, b1));
      } else {
        result.add(new Fragment(s0, s1, t0, t1));
      }
    }
  }

  /**
   * Texts equal under {@code policy} that also split into the same tokens: whitespace may be
   * ignored only where it does not join or split words or add or remove a newline.
   */
  public static boolean sameWords(CharSequence a, CharSequence b, ComparisonPolicy policy) {
    return policy.equalTexts(a, b)
        && WordTokenizer.texts(WordTokenizer.tokenize(a)).equals(WordTokenizer.texts(WordTokenizer.tokenize(b)));
  }

  private static int trailingWhitespace(CharSequence text, int from, int to) {
    int i = to;
    while (i > from && Character.isWhitespace(text.charAt(i - 1))) {
      i--;
    }
    return to - i;
  }

  private static List<Token> tokens(CharSequence text) {
    List<Token> tokens = WordTokenizer.tokenize(text);
    if (tokens.size() > MAX_TOKENS) {
      throw new TooBigException(tokens.size());
    }
    return tokens;
  }

  /** Char offset where token range starting at {@code index} begins: end of the previous token. */
  private static int charStart(List<Token> tokens, int index) {
    return index == 0 ? 0 : tokens.get(index - 1).end();
  }

  /** Char offset where token range ending at {@code index} ends: start of the next token. */
  private static int charEnd(List<Token> tokens, int index, int length) {
    return index == tokens.size() ? length : tokens.get(index).start();
  }
}
