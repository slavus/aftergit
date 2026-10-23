/*
 * Portions derived from IntelliJ Community (https://github.com/JetBrains/intellij-community),
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.aftergit.merge.core.resolve;

import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.WordMerge;
import net.aftergit.merge.core.merge.WordMerge.Fragment;

/**
 * Greedy word-level resolver. Like {@link SimpleResolver}, but a word region changed by both sides
 * is merged greedily instead of failing: overlapping deletions are united, an edit next to the
 * other side's deletion is applied together with it, and two different insertions at the same
 * place fail, except when one is a prefix or suffix of the other (AfterGit extension: the longer
 * one wins). One side's text replaces the other's edits only where its own edits cover all the
 * base text the cluster touches. Port of IntelliJ MergeResolveUtil.GreedyHelper. Results must
 * always be reviewed.
 */
public final class GreedyResolver {
  private GreedyResolver() {}

  public static Optional<String> tryResolve(String left, String base, String right) {
    return tryResolveDetailed(left, base, right).map(ConflictResolver.Resolution::text);
  }

  /** Tries DEFAULT first, then IGNORE_WHITESPACES; the result says which one succeeded. */
  public static Optional<ConflictResolver.Resolution> tryResolveDetailed(String left, String base, String right) {
    try {
      String resolved = resolve(left, base, right, ComparisonPolicy.DEFAULT);
      if (resolved != null) {
        return Optional.of(new ConflictResolver.Resolution(resolved, false));
      }
      resolved = resolve(left, base, right, ComparisonPolicy.IGNORE_WHITESPACES);
      return Optional.ofNullable(resolved).map(t -> new ConflictResolver.Resolution(t, true));
    } catch (WordMerge.TooBigException e) {
      return Optional.empty();
    }
  }

  static String resolve(String left, String base, String right, ComparisonPolicy policy) {
    return WordResolution.resolve(left, base, right, policy, GreedyResolver::mergeRegion, true);
  }

  /** Greedy merge of one word region that both sides changed; null on a real conflict. */
  static String mergeRegion(String left, String base, String right, ComparisonPolicy policy) {
    List<Fragment> f1 = WordMerge.twoWay(base, left, policy);
    List<Fragment> f2 = WordMerge.twoWay(base, right, policy);
    WordResolution.Output out = new WordResolution.Output();
    int lastBase = 0;
    int i1 = 0;
    int i2 = 0;
    while (true) {
      int c1 = i1 < f1.size() ? f1.get(i1).start1() : -1;
      int c2 = i2 < f2.size() ? f2.get(i2).start1() : -1;
      if (c1 == -1 && c2 == -1) {
        out.append(base, lastBase, base.length());
        break;
      }
      int next = (c1 != -1 && c2 != -1) ? Math.min(c1, c2) : (c1 != -1 ? c1 : c2);
      out.append(base, lastBase, next);
      lastBase = next;

      // a cluster: fragments of both sides that overlap or touch; together they cover [start, end)
      int start = lastBase;
      int end = lastBase;
      int e1 = i1;
      int e2 = i2;
      while (true) {
        if (e1 < f1.size() && f1.get(e1).start1() <= end) {
          end = Math.max(end, f1.get(e1).end1());
          e1++;
          continue;
        }
        if (e2 < f2.size() && f2.get(e2).start1() <= end) {
          end = Math.max(end, f2.get(e2).end1());
          e2++;
          continue;
        }
        break;
      }
      List<Fragment> c1s = f1.subList(i1, e1);
      List<Fragment> c2s = f2.subList(i2, e2);
      i1 = e1;
      i2 = e2;
      lastBase = end;

      String ins1 = inserted(c1s, left);
      String ins2 = inserted(c2s, right);
      if (deletesModified(c1s, c2s) || deletesModified(c2s, c1s)) {
        return null;
      }
      if (ins1.isEmpty() || ins2.isEmpty()) {
        // one side only deletes: apply both sides' edits, keeping base text neither touched
        compose(out, base, start, end, c1s, left, c2s, right);
        continue;
      }
      boolean cover1 = covers(c1s, start, end);
      boolean cover2 = covers(c2s, start, end);
      if (WordMerge.sameWords(ins1, ins2, policy)) {
        if (cover1 && (!cover2 || ins1.length() <= ins2.length())) {
          out.append(ins1);
        } else if (cover2) {
          out.append(ins2);
        } else {
          return null;
        }
        continue;
      }
      boolean longer1 = ins1.length() >= ins2.length();
      String longer = longer1 ? ins1 : ins2;
      String shorter = longer1 ? ins2 : ins1;
      // a whitespace-only edit never counts as a prefix or suffix of a word edit
      if (!shorter.isBlank() && extendsOnTokenBoundary(longer, shorter) && (longer1 ? cover1 : cover2)) {
        out.append(longer);
        continue;
      }
      return null;
    }
    return out.result();
  }

  /** The side's fragments replace all of [start, end) without a hole. */
  private static boolean covers(List<Fragment> fragments, int start, int end) {
    int at = start;
    for (Fragment f : fragments) {
      if (f.start1() != at) {
        return false;
      }
      at = f.end1();
    }
    return !fragments.isEmpty() && at == end;
  }

  /**
   * Applies the fragments of both sides to base [start, end). Overlaps are only deletion over
   * deletion or an insertion inside the other side's deletion (see {@link #deletesModified}), and
   * at most one side inserts text, so the result is well defined.
   */
  private static void compose(WordResolution.Output out, String base, int start, int end,
                              List<Fragment> a, String textA, List<Fragment> b, String textB) {
    int[] depth = new int[end - start + 1];
    for (List<Fragment> side : List.of(a, b)) {
      for (Fragment f : side) {
        depth[f.start1() - start]++;
        depth[f.end1() - start]--;
      }
    }
    int ia = 0;
    int ib = 0;
    int covered = 0;
    int run = -1;
    for (int p = start; p <= end; p++) {
      boolean emits = (ia < a.size() && a.get(ia).start1() == p) || (ib < b.size() && b.get(ib).start1() == p);
      covered += depth[p - start];
      if (run != -1 && (emits || covered > 0 || p == end)) {
        out.append(base, run, p);
        run = -1;
      }
      while (ia < a.size() && a.get(ia).start1() == p) {
        Fragment f = a.get(ia++);
        out.append(textA, f.start2(), f.end2());
      }
      while (ib < b.size() && b.get(ib).start1() == p) {
        Fragment f = b.get(ib++);
        out.append(textB, f.start2(), f.end2());
      }
      if (p < end && covered == 0 && run == -1) {
        run = p;
      }
    }
  }

  /** True when a deletion on side A and a replacement on side B remove some of the same base text. */
  private static boolean deletesModified(List<Fragment> a, List<Fragment> b) {
    for (Fragment d : a) {
      if (d.start1() == d.end1() || d.start2() != d.end2()) {
        continue;
      }
      for (Fragment m : b) {
        if (m.start1() < m.end1() && m.start2() < m.end2()
            && Math.max(d.start1(), m.start1()) < Math.min(d.end1(), m.end1())) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code longer} is {@code shorter} plus extra text that does not continue a word of it. */
  private static boolean extendsOnTokenBoundary(String longer, String shorter) {
    int extra = longer.length() - shorter.length();
    if (longer.startsWith(shorter)
        && (!wordChar(shorter.codePointBefore(shorter.length())) || !wordChar(longer.codePointAt(shorter.length())))) {
      return true;
    }
    return longer.endsWith(shorter)
        && (!wordChar(shorter.codePointAt(0)) || !wordChar(longer.codePointBefore(extra)));
  }

  private static boolean wordChar(int cp) {
    return Character.isLetterOrDigit(cp) || cp == '_';
  }

  private static String inserted(List<Fragment> fragments, String text) {
    StringBuilder sb = new StringBuilder();
    for (Fragment f : fragments) {
      sb.append(text, f.start2(), f.end2());
    }
    return sb.toString();
  }
}
