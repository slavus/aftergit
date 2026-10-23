package net.aftergit.merge.core.resolve;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.diff.WordTokenizer.Token;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.WordMerge;

/**
 * Independent check that a word-level merge result neither glues text nor drops an edit that only
 * one side made. Works on the 3-way word regions without locating them in the result:
 * <ul>
 *   <li>every token of the result is a token of one of the inputs, and so is every compound token
 *       (letters and digits joined by '.', '-' or ':' without whitespace, such as a version);</li>
 *   <li>a region or whitespace gap changed by one side only appears in the result verbatim,
 *       together with the unchanged tokens around it;</li>
 *   <li>the number of line breaks is one that the regions allow: a region whose line breaks only
 *       one side changed contributes that side's count.</li>
 * </ul>
 */
final class MergeOracle {
  private MergeOracle() {}

  /** Null when {@code result} is acceptable, otherwise what is wrong with it. */
  static String violation(String left, String base, String right, String result) {
    if (result == null) {
      return null;
    }
    Set<String> known = new HashSet<>();
    for (String s : List.of(left, base, right)) {
      known.addAll(WordTokenizer.texts(WordTokenizer.tokenize(s)));
    }
    for (Token t : WordTokenizer.tokenize(result)) {
      if (!known.contains(t.text())) {
        return "token '" + t.text() + "' is in none of the inputs";
      }
    }

    Set<String> compounds = new HashSet<>();
    for (String s : List.of(left, base, right)) {
      compounds.addAll(compounds(s));
    }
    for (String c : compounds(result)) {
      if (!compounds.contains(c)) {
        return "compound token '" + c + "' is in none of the inputs";
      }
    }

    List<MergeRange> regions = WordMerge.threeWay(left, base, right);
    List<Token> tl = WordTokenizer.tokenize(left);
    List<Token> tr = WordTokenizer.tokenize(right);
    BitSet totals = new BitSet();
    totals.set(0);
    int s1 = 0;
    int s2 = 0;
    int s3 = 0;
    for (int i = 0; i <= regions.size(); i++) {
      MergeRange r = i < regions.size() ? regions.get(i)
          : new MergeRange(left.length(), left.length(), base.length(), base.length(),
              right.length(), right.length());
      // stable stretch before the region: equal tokens, compare gap by gap
      String why = checkStretch(left, s1, r.start1(), base, s2, r.start2(), right, s3, r.start3(), result);
      if (why != null) {
        return why;
      }
      totals = shift(totals, newlines(base, s2, r.start2()));
      if (i == regions.size()) {
        break;
      }
      String l = left.substring(r.start1(), r.end1());
      String b = base.substring(r.start2(), r.end2());
      String g = right.substring(r.start3(), r.end3());
      if (g.equals(b) && !l.equals(b) && !result.contains(context(left, tl, r.start1(), r.end1()))) {
        return "left-only region '" + l + "' lost";
      }
      if (l.equals(b) && !g.equals(b) && !result.contains(context(right, tr, r.start3(), r.end3()))) {
        return "right-only region '" + g + "' lost";
      }
      int nl = count(l);
      int nb = count(b);
      int ng = count(g);
      BitSet next = new BitSet();
      if (nl == nb) {
        next.or(shift(totals, ng));
      } else if (ng == nb) {
        next.or(shift(totals, nl));
      } else {
        next.or(shift(totals, nl));
        next.or(shift(totals, ng));
        if (nl + ng - nb >= 0) {
          next.or(shift(totals, nl + ng - nb));
        }
      }
      totals = next;
      s1 = r.end1();
      s2 = r.end2();
      s3 = r.end3();
    }
    if (!totals.get(count(result))) {
      return "line breaks: result has " + count(result) + ", allowed " + totals;
    }
    return null;
  }

  private static final Pattern COMPOUND = Pattern.compile("[\\p{L}\\p{Nd}_]+(?:[.:-][\\p{L}\\p{Nd}_]+)*");

  /** Compound tokens of {@code text}, found independently of the tokenizer. */
  static Set<String> compounds(String text) {
    Set<String> out = new HashSet<>();
    Matcher m = COMPOUND.matcher(text);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  private static String checkStretch(String left, int l0, int l1, String base, int b0, int b1,
                                     String right, int r0, int r1, String result) {
    List<Token> a = WordTokenizer.tokenize(left.substring(l0, l1));
    List<Token> b = WordTokenizer.tokenize(base.substring(b0, b1));
    List<Token> c = WordTokenizer.tokenize(right.substring(r0, r1));
    if (a.size() != b.size() || c.size() != b.size()) {
      return "oracle: unequal stable stretch";
    }
    for (int k = 0; k <= b.size(); k++) {
      String gl = gap(left, l0, l1, a, k);
      String gb = gap(base, b0, b1, b, k);
      String gr = gap(right, r0, r1, c, k);
      if (gr.equals(gb) && !gl.equals(gb) && !result.contains(gapContext(left, l0, l1, a, k))) {
        return "left-only whitespace '" + gl + "' lost";
      }
      if (gl.equals(gb) && !gr.equals(gb) && !result.contains(gapContext(right, r0, r1, c, k))) {
        return "right-only whitespace '" + gr + "' lost";
      }
    }
    return null;
  }

  private static String gap(String text, int from, int to, List<Token> tokens, int k) {
    int s = k == 0 ? from : from + tokens.get(k - 1).end();
    int e = k == tokens.size() ? to : from + tokens.get(k).start();
    return text.substring(s, e);
  }

  private static String gapContext(String text, int from, int to, List<Token> tokens, int k) {
    int s = k == 0 ? from : from + tokens.get(k - 1).start();
    int e = k == tokens.size() ? to : from + tokens.get(k).end();
    return text.substring(s, e);
  }

  /** The region text with the whole unchanged token before and after it. */
  private static String context(String text, List<Token> tokens, int start, int end) {
    int s = start;
    int e = end;
    for (Token t : tokens) {
      if (t.end() == start) {
        s = t.start();
      }
      if (t.start() == end && e == end) {
        e = t.end();
      }
    }
    return text.substring(s, e);
  }

  private static BitSet shift(BitSet set, int by) {
    BitSet out = new BitSet();
    for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
      out.set(i + by);
    }
    return out;
  }

  private static int newlines(String text, int from, int to) {
    return count(text.substring(from, to));
  }

  private static int count(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }
}
