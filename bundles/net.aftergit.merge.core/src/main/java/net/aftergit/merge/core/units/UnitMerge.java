package net.aftergit.merge.core.units;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Three-way merge of keyed units (resolvers 6c design: members, .properties entries, manifest headers and clauses).
 * Per key, against base: added on one side is taken; added on both with the same text is taken once; changed on one
 * side is that change; deleted on one side and untouched on the other is deleted; changed on both sides, or deleted on
 * one and changed on the other, is a conflict unless the {@link Policy} merges it. A key twice in one side, and keys
 * that a side orders differently from base (a move), are a conflict too: the result is then empty.
 *
 * <p>Order: ours' order. A unit new in theirs goes after its nearest preceding unit of theirs that is in the result,
 * and after the units new in ours that directly follow that one (ours, then theirs, as Take Both); without such a
 * unit it goes after the units new in ours at the start. {@link Unit#TRAILING} always ends the result. Units both
 * sides added with the same text are taken once at ours' place, whatever order theirs has them in: the order of
 * identical additions is not a conflict.
 */
public final class UnitMerge {
  /** How units that both sides changed are merged. */
  public interface Policy {
    /**
     * No unit both sides changed differently is merged; one both changed (or both added) to the same text is taken
     * once.
     */
    Policy STRICT = new Policy() {};

    /**
     * The text of a unit both sides changed to different texts ({@code base} null: both added it), or empty: a
     * conflict.
     */
    default Optional<String> bothChanged(String key, String base, String ours, String theirs) {
      return Optional.empty();
    }

    /** Whether a unit both sides changed, or both added, to the same text is still a conflict. */
    default boolean sameChangeConflicts(String key) {
      return false;
    }
  }

  /** The decision for one key: a conflict, or the text to keep (null: the unit goes). */
  private record Choice(boolean conflict, String text) {
    static final Choice CONFLICT = new Choice(true, null);

    static Choice of(String text) {
      return new Choice(false, text);
    }
  }

  private UnitMerge() {}

  public static Optional<List<Keyed>> merge(List<Keyed> base, List<Keyed> ours, List<Keyed> theirs, Policy policy) {
    Map<String, String> b = index(base);
    Map<String, String> o = index(ours);
    Map<String, String> t = index(theirs);
    if (b == null || o == null || t == null || moved(base, ours) || moved(base, theirs)) {
      return Optional.empty();
    }
    Set<String> keys = new LinkedHashSet<>(b.keySet());
    keys.addAll(o.keySet());
    keys.addAll(t.keySet());
    Map<String, String> kept = new HashMap<>();
    for (String key : keys) {
      Choice c = decide(key, b.get(key), o.get(key), t.get(key), policy);
      if (c.conflict()) {
        return Optional.empty();
      }
      if (c.text() != null) {
        kept.put(key, c.text());
      }
    }
    List<Keyed> out = new ArrayList<>();
    Set<String> oursOnly = new HashSet<>();
    for (Keyed k : ours) {
      if (kept.containsKey(k.key())) {
        out.add(new Keyed(k.key(), kept.get(k.key())));
        if (!t.containsKey(k.key())) {
          oursOnly.add(k.key());
        }
      }
    }
    for (int i = 0; i < theirs.size(); i++) {
      String key = theirs.get(i).key();
      if (o.containsKey(key) || !kept.containsKey(key)) {
        continue;
      }
      int at = 0;
      for (int j = i - 1; j >= 0; j--) {
        int pos = indexOf(out, theirs.get(j).key());
        if (pos >= 0) {
          at = pos + 1;
          break;
        }
      }
      while (at < out.size() && oursOnly.contains(out.get(at).key())) {
        at++;
      }
      out.add(at, new Keyed(key, kept.get(key)));
    }
    int trailing = indexOf(out, Unit.TRAILING);
    if (trailing >= 0) {
      out.add(out.remove(trailing));
    }
    return Optional.of(List.copyOf(out));
  }

  private static Choice decide(String key, String b, String o, String t, Policy policy) {
    if (b == null) {
      if (o == null) {
        return Choice.of(t);
      }
      if (t == null) {
        return Choice.of(o);
      }
      if (o.equals(t)) {
        return policy.sameChangeConflicts(key) ? Choice.CONFLICT : Choice.of(o);
      }
      return policy.bothChanged(key, null, o, t).map(Choice::of).orElse(Choice.CONFLICT);
    }
    boolean oursChanged = !b.equals(o);
    boolean theirsChanged = !b.equals(t);
    if (!theirsChanged) {
      return Choice.of(o); // untouched, or ours' change (null: ours deleted it)
    }
    if (!oursChanged) {
      return Choice.of(t);
    }
    if (o == null && t == null) {
      return Choice.of(null); // deleted on both sides
    }
    if (o == null || t == null) {
      return Choice.CONFLICT; // deleted on one side, changed on the other
    }
    if (o.equals(t)) {
      return policy.sameChangeConflicts(key) ? Choice.CONFLICT : Choice.of(o);
    }
    return policy.bothChanged(key, b, o, t).map(Choice::of).orElse(Choice.CONFLICT);
  }

  /** Key to text, or null when a key occurs twice. */
  private static Map<String, String> index(List<Keyed> units) {
    Map<String, String> m = new HashMap<>();
    for (Keyed k : units) {
      if (m.put(k.key(), k.text()) != null) {
        return null;
      }
    }
    return m;
  }

  /** The keys base and {@code side} both hold are in a different relative order. */
  private static boolean moved(List<Keyed> base, List<Keyed> side) {
    Set<String> inSide = new HashSet<>();
    side.forEach(k -> inSide.add(k.key()));
    Set<String> inBase = new HashSet<>();
    base.forEach(k -> inBase.add(k.key()));
    List<String> a = base.stream().map(Keyed::key).filter(inSide::contains).toList();
    List<String> c = side.stream().map(Keyed::key).filter(inBase::contains).toList();
    return !a.equals(c);
  }

  private static int indexOf(List<Keyed> list, String key) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).key().equals(key)) {
        return i;
      }
    }
    return -1;
  }
}
