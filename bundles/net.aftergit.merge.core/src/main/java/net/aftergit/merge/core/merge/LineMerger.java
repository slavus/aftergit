package net.aftergit.merge.core.merge;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.Diffs;
import net.aftergit.merge.core.text.LineText;

/** 3-way line merge: base→left and base→right histogram diffs combined by FairMergeBuilder. */
public final class LineMerger {
  private LineMerger() {}

  public static List<MergeChange> merge(LineText left, LineText base, LineText right, ComparisonPolicy policy) {
    List<String> nl = normalize(left, policy);
    List<String> nb = normalize(base, policy);
    List<String> nr = normalize(right, policy);
    FairMergeBuilder.SideEquality trueEquality = policy == ComparisonPolicy.DEFAULT ? null
        : (l, b, r) -> base.line(b).equals(left.line(l)) && base.line(b).equals(right.line(r));
    List<MergeRange> ranges = FairMergeBuilder.build(Diffs.unchanged(nb, nl), Diffs.unchanged(nb, nr),
        left.size(), base.size(), right.size(), trueEquality);
    List<MergeChange> result = new ArrayList<>(ranges.size());
    for (MergeRange r : ranges) {
      ConflictType type = MergeTypes.classify(r::isEmpty,
          (a, b) -> equalLines(r, a, b, left, base, right, policy),
          (a, b) -> equalLines(r, a, b, left, base, right, ComparisonPolicy.DEFAULT));
      result.add(new MergeChange(r, type));
    }
    return result;
  }

  private static List<String> normalize(LineText text, ComparisonPolicy policy) {
    List<String> result = new ArrayList<>(text.size());
    for (String line : text.lines()) {
      result.add(policy.normalize(line));
    }
    return result;
  }

  private static boolean equalLines(MergeRange r, ThreeSide s1, ThreeSide s2,
                                    LineText left, LineText base, LineText right, ComparisonPolicy policy) {
    LineText t1 = s1.select(left, base, right);
    LineText t2 = s2.select(left, base, right);
    int start1 = r.start(s1);
    int start2 = r.start(s2);
    int count = r.end(s1) - start1;
    if (count != r.end(s2) - start2) {
      return false;
    }
    for (int i = 0; i < count; i++) {
      if (!policy.equalTexts(t1.line(start1 + i), t2.line(start2 + i))) {
        return false;
      }
    }
    return true;
  }
}
