/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.resolve;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.diff.WordTokenizer.Token;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.MergeTypes;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.merge.WordMerge;

/**
 * Word-level 3-way merge shared by the simple and the greedy resolver.
 *
 * <p>The texts are split by {@link WordMerge#threeWay} into stable stretches (the same tokens on
 * all three sides) and change regions. A stable stretch is merged whitespace gap by whitespace
 * gap: a gap changed by one side takes that side's text; a gap changed differently by both sides
 * fails under DEFAULT and keeps the base text under a whitespace-ignoring policy. A region changed
 * by one side (or identically by both) takes that side's text; a region changed by both is handed
 * to a {@link ConflictMerger}.
 *
 * <p>Every result is then checked to be a word-level merge of the inputs; when it is not, the
 * merge gives up (null):
 * <ul>
 *   <li>no two pieces from different places are joined into one word (glued words);</li>
 *   <li>every token of the result is a token of one of the inputs, and so is every compound token
 *       ({@link WordTokenizer#compounds}: a version, a qualified name, Maven coordinates);</li>
 *   <li>no compound token of base is changed by both sides differently: two edits inside one
 *       version or name are never combined into a value that neither side has;</li>
 *   <li>a region whose line breaks only one side changed keeps that side's number of line breaks.</li>
 * </ul>
 */
final class WordResolution {

  /** Merges one region changed by both sides; null when it cannot. */
  @FunctionalInterface
  interface ConflictMerger {
    String merge(String left, String base, String right, ComparisonPolicy policy);
  }

  private WordResolution() {}

  static String resolve(String left, String base, String right, ComparisonPolicy policy,
                        ConflictMerger conflicts, boolean shorterOfEqual) {
    String[] texts = {left, base, right};
    Output out = new Output();
    int last1 = 0;
    int last2 = 0;
    int last3 = 0;
    List<MergeRange> regions = WordMerge.threeWay(left, base, right);
    if (bothChangeOneCompound(regions, texts, policy)) {
      return null;
    }
    for (MergeRange f : regions) {
      if (!mergeStretch(out, texts, last1, f.start1(), last2, f.start2(), last3, f.start3(), policy)) {
        return null;
      }
      String l = part(texts, f, ThreeSide.LEFT);
      String b = part(texts, f, ThreeSide.BASE);
      String r = part(texts, f, ThreeSide.RIGHT);
      ConflictType type = wordType(f, texts, policy);
      String merged;
      if (type.isConflict()) {
        merged = conflicts.merge(l, b, r, policy);
      } else if (type.isChange(ThreeSide.LEFT) && type.isChange(ThreeSide.RIGHT)) {
        merged = shorterOfEqual && r.length() < l.length() ? r : l;
      } else {
        merged = type.isChange(ThreeSide.LEFT) ? l : r;
      }
      if (merged == null || !keepsLineBreaks(l, b, r, merged)) {
        return null;
      }
      out.append(merged);
      last1 = f.end1();
      last2 = f.end2();
      last3 = f.end3();
    }
    if (!mergeStretch(out, texts, last1, left.length(), last2, base.length(), last3, right.length(), policy)) {
      return null;
    }
    String result = out.result();
    return result != null && knownTokens(result, texts) ? result : null;
  }

  /**
   * True when both sides changed one compound token of base (a version, a qualified name) and not
   * in the same way: the word regions that touch it include a change of each side and one that the
   * sides did not make identically. A compound inside a single region is left to that region. Combining such edits word by word would make up a value that
   * neither side has, so the region is left as a conflict.
   */
  static boolean bothChangeOneCompound(List<MergeRange> regions, String[] texts, ComparisonPolicy policy) {
    int from = 0; // regions are in base order: those ending before a compound cannot touch a later one
    for (Token c : WordTokenizer.compounds(texts[1])) {
      if (!multiWord(c.text())) {
        continue;
      }
      while (from < regions.size() && regions.get(from).end2() < c.start()) {
        from++;
      }
      boolean left = false;
      boolean right = false;
      boolean differ = false;
      int touching = 0;
      boolean covered = false;
      for (MergeRange f : regions.subList(from, regions.size())) {
        if (f.start2() > c.end()) {
          break;
        }
        if (!touches(f, c, texts)) {
          continue;
        }
        touching++;
        covered |= f.start2() <= c.start() && c.end() <= f.end2();
        ConflictType type = wordType(f, texts, policy);
        boolean l = type.isChange(ThreeSide.LEFT);
        boolean r = type.isChange(ThreeSide.RIGHT);
        left |= l;
        right |= r;
        differ |= type.isConflict() || l != r;
      }
      // one region holding the whole compound merges it as a whole (both sides deleting it, say); a value made up
      // there is caught by the compound check of the result
      if (left && right && differ && !(covered && touching == 1)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The region changes the compound {@code c} of base: it replaces some of its words, inserts
   * inside it, or inserts right next to it a joiner and a word that extend it.
   */
  private static boolean touches(MergeRange f, Token c, String[] texts) {
    int b0 = f.start2();
    int b1 = f.end2();
    if (b0 < c.end() && c.start() < b1) {
      return true; // compounds contain no whitespace, so the region replaces a word of it
    }
    if (c.start() < b0 && b1 < c.end()) {
      return true;
    }
    for (ThreeSide side : List.of(ThreeSide.LEFT, ThreeSide.RIGHT)) {
      String p = part(texts, f, side);
      if (p.length() >= 2 && b0 == c.end() && joiner(p.charAt(0)) && wordChar(p.codePointAt(1))) {
        return true;
      }
      if (p.length() >= 2 && b1 == c.start() && joiner(p.charAt(p.length() - 1))
          && wordChar(p.codePointBefore(p.length() - 1))) {
        return true;
      }
    }
    return false;
  }

  /** Words joined by a joiner; a lone '.', '-' or ':' joins nothing. */
  private static boolean multiWord(String compound) {
    if (compound.length() < 2) {
      return false;
    }
    for (int i = 0; i < compound.length(); i++) {
      if (joiner(compound.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean joiner(char ch) {
    return ch == '.' || ch == '-' || ch == ':';
  }

  private static boolean wordChar(int cp) {
    return Character.isLetterOrDigit(cp) || cp == '_';
  }

  /** Classification of a region: equal by {@code policy} only when also equal token by token. */
  static ConflictType wordType(MergeRange range, String[] texts, ComparisonPolicy policy) {
    return MergeTypes.classify(range::isEmpty,
        (x, y) -> WordMerge.sameWords(part(texts, range, x), part(texts, range, y), policy),
        (x, y) -> part(texts, range, x).equals(part(texts, range, y)));
  }

  /** Appends a stretch whose tokens are equal on all sides, merging each whitespace gap. */
  private static boolean mergeStretch(Output out, String[] texts, int l0, int l1, int b0, int b1,
                                      int r0, int r1, ComparisonPolicy policy) {
    String left = texts[0].substring(l0, l1);
    String base = texts[1].substring(b0, b1);
    String right = texts[2].substring(r0, r1);
    if (left.equals(base) && right.equals(base)) {
      out.append(base);
      return true;
    }
    List<Token> tl = WordTokenizer.tokenize(left);
    List<Token> tb = WordTokenizer.tokenize(base);
    List<Token> tr = WordTokenizer.tokenize(right);
    if (tl.size() != tb.size() || tr.size() != tb.size()) {
      return false;
    }
    for (int k = 0; k <= tb.size(); k++) {
      String gl = gap(left, tl, k);
      String gb = gap(base, tb, k);
      String gr = gap(right, tr, k);
      if (gl.equals(gb)) {
        out.append(gr);
      } else if (gr.equals(gb) || gl.equals(gr)) {
        out.append(gl);
      } else if (policy == ComparisonPolicy.DEFAULT) {
        return false;
      } else {
        out.append(gb); // whitespace conflict ignored by the policy
      }
      if (k < tb.size()) {
        out.append(tb.get(k).text());
      }
    }
    return true;
  }

  private static String gap(String text, List<Token> tokens, int k) {
    int s = k == 0 ? 0 : tokens.get(k - 1).end();
    int e = k == tokens.size() ? text.length() : tokens.get(k).start();
    return text.substring(s, e);
  }

  /**
   * A region whose line-break count only one side changed must end with that side's count; when
   * both changed it, the result has one side's count or both changes applied.
   */
  static boolean keepsLineBreaks(String left, String base, String right, String merged) {
    int nl = newlines(left);
    int nb = newlines(base);
    int nr = newlines(right);
    int nm = newlines(merged);
    if (nl == nb) {
      return nm == nr;
    }
    if (nr == nb) {
      return nm == nl;
    }
    return nm == nl || nm == nr || nm == nl + nr - nb;
  }

  private static int newlines(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }

  private static boolean knownTokens(String result, String[] texts) {
    Set<String> known = new HashSet<>();
    for (String t : texts) {
      known.addAll(WordTokenizer.texts(WordTokenizer.compounds(t)));
    }
    for (Token t : WordTokenizer.compounds(result)) {
      if (!known.contains(t.text())) {
        return false;
      }
    }
    return true;
  }

  private static String part(String[] texts, MergeRange range, ThreeSide side) {
    return texts[side.ordinal()].substring(range.start(side), range.end(side));
  }

  /**
   * Result text built from pieces that never split a token of their source. Joining two pieces
   * must not change how the text splits into tokens: a piece ending in a word character followed
   * by one starting with a word character would glue two words, so the output is refused.
   */
  static final class Output {
    private final StringBuilder sb = new StringBuilder();
    private boolean glued;

    void append(CharSequence piece) {
      append(piece, 0, piece.length());
    }

    void append(CharSequence text, int from, int to) {
      if (from == to) {
        return;
      }
      if (sb.length() > 0 && wordChar(Character.codePointBefore(sb, sb.length()))
          && wordChar(Character.codePointAt(text, from))) {
        glued = true;
      }
      sb.append(text, from, to);
    }

    /** The text, or null when two pieces were glued into one word. */
    String result() {
      return glued ? null : sb.toString();
    }

    private static boolean wordChar(int cp) {
      return WordResolution.wordChar(cp);
    }
  }
}
