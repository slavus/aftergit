package net.aftergit.merge.core.diff;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

/** Token-list diff built on JGit's histogram algorithm. */
public final class Diffs {
  private static final DiffAlgorithm ALGORITHM =
      DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);

  private Diffs() {}

  /** Unchanged ranges between {@code a} and {@code b}, in increasing order. */
  public static List<Range> unchanged(List<String> a, List<String> b) {
    EditList edits = ALGORITHM.diff(StringSequence.COMPARATOR, new StringSequence(a), new StringSequence(b));
    List<Range> result = new ArrayList<>();
    int ia = 0;
    int ib = 0;
    for (Edit e : edits) {
      if (e.getBeginA() > ia) {
        result.add(new Range(ia, e.getBeginA(), ib, e.getBeginB()));
      }
      ia = e.getEndA();
      ib = e.getEndB();
    }
    if (ia < a.size()) {
      result.add(new Range(ia, a.size(), ib, b.size()));
    }
    return result;
  }

  private static final class StringSequence extends Sequence {
    static final SequenceComparator<StringSequence> COMPARATOR = new SequenceComparator<>() {
      @Override
      public boolean equals(StringSequence a, int ai, StringSequence b, int bi) {
        return a.items.get(ai).equals(b.items.get(bi));
      }

      @Override
      public int hash(StringSequence seq, int ptr) {
        return seq.items.get(ptr).hashCode();
      }
    };

    private final List<String> items;

    StringSequence(List<String> items) {
      this.items = items;
    }

    @Override
    public int size() {
      return items.size();
    }
  }
}
