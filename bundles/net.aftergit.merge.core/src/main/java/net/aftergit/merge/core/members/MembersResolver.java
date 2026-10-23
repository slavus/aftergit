package net.aftergit.merge.core.members;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.BlockContext;
import net.aftergit.merge.core.resolve.ConflictResolver;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.BlockRegion;
import net.aftergit.merge.core.units.Keyed;
import net.aftergit.merge.core.units.Unit;
import net.aftergit.merge.core.units.UnitMerge;

/**
 * Conflicts in Java type bodies, merged per member (resolvers 6c design §1). A member's unit is the blank lines above
 * it and its own lines; members are matched by key, a field declaration by the set of its variables (sets that
 * overlap without being equal are a conflict), an initializer by its position among the initializers, so a block where
 * the sides do not have as many initializers as base is left alone. Enum constants are compared without their ',' or ';' and get them back
 * in the merged order; constants always stay ahead of the other members, and a constant whose last line does not end
 * exactly with its separator (a trailing comment or blank) leaves the block alone.
 *
 * <p>Not exclusive: a block it leaves alone (for example two edits inside one method) is still offered to the text
 * resolvers, as before 6c. Known consequence: a member both sides added under the same key with different texts is a
 * conflict here, but a text resolver may then keep both copies; that duplicate is what happened before 6c too, and
 * the compile check reports it.
 */
public class MembersResolver implements ConflictResolver {
  public static final String ID = "members";
  private static final String KEY_SEPARATOR = "\u0001";
  private static final String INIT_PREFIX = "init:";
  private static final Logger LOG = Logger.getLogger(MembersResolver.class.getName());

  private final MemberLocator locator;

  public MembersResolver(MemberLocator locator) {
    this.locator = Objects.requireNonNull(locator, "locator");
  }

  @Override
  public final String id() {
    return ID;
  }

  @Override
  public final Strategy strategy() {
    return Strategy.SEMANTIC;
  }

  /** Needs the whole file: see {@link #tryResolve(BlockContext)}. */
  @Override
  public final Optional<String> tryResolve(String left, String base, String right) {
    return Optional.empty();
  }

  @Override
  public final boolean appliesTo(String fileName) {
    return fileName != null && fileName.endsWith(".java");
  }

  @Override
  public final boolean isApplicable(BlockContext ctx) {
    return ctx.block().region() == Region.BODY;
  }

  @Override
  public final Optional<Resolution> tryResolve(BlockContext ctx) {
    return BlockRegion.resolve(ctx, this::segment, MembersResolver::merge).map(text -> new Resolution(text, false));
  }

  private Optional<Body> segment(String text, int from, int to) {
    Optional<TypeBody> found;
    try {
      found = locator.locate(text, from, to);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Member locator failed; the block stays a conflict", e);
      return Optional.empty();
    } catch (VirtualMachineError e) {
      return Optional.empty(); // a parser that is overwhelmed: not logged, the block stays a conflict
    }
    if (found.isEmpty()) {
      return Optional.empty();
    }
    TypeBody t = found.get();
    if (from < t.start() || to > t.end()) {
      return Optional.empty();
    }
    List<Unit> units = new ArrayList<>();
    int at = t.start();
    for (Member m : t.members()) {
      if (m.keys().isEmpty() || m.start() < at || m.end() <= m.start() || m.end() > t.end()) {
        return Optional.empty(); // members out of order or sharing a line
      }
      units.add(new Unit(m.keys().stream().sorted().collect(Collectors.joining(KEY_SEPARATOR)), at, m.end()));
      at = m.end();
    }
    if (at < t.end()) {
      units.add(new Unit(Unit.TRAILING, at, t.end()));
    }
    return Optional.of(new Body(t.start(), t.end(), units));
  }

  static Optional<List<Keyed>> merge(List<Keyed> base, List<Keyed> ours, List<Keyed> theirs) {
    List<List<Keyed>> sides = List.of(base, ours, theirs);
    if (!consistent(sides) || !sides.stream().allMatch(MembersResolver::wellFormed) || !sameInitializerCount(sides)) {
      return Optional.empty();
    }
    String last = lastSeparator(ours).or(() -> lastSeparator(theirs)).orElse("");
    return UnitMerge.merge(strip(base), strip(ours), strip(theirs), UnitMerge.Policy.STRICT)
        .map(MembersResolver::constantsFirst).map(list -> separate(list, last));
  }

  /**
   * No constant follows another member, and each constant's last line ends exactly with its ',' or ';' (or holds no
   * separator): no trailing blanks, no blank before the separator, no comment on it.
   */
  private static boolean wellFormed(List<Keyed> units) {
    boolean other = false;
    for (Keyed k : units) {
      if (!constant(k)) {
        other |= !k.key().equals(Unit.TRAILING);
        continue;
      }
      if (other) {
        return false;
      }
      String line = lastLine(k.text());
      String bare = line.endsWith(",") || line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
      if (bare.isBlank() || !bare.equals(bare.stripTrailing()) || line.contains("//") || line.contains("/*")
          || line.contains("*/")) {
        return false;
      }
    }
    return true;
  }

  /**
   * The merged units with every constant moved ahead of the other members, keeping both orders: a constant new in
   * theirs that the merge put after a member new in ours goes back next to its neighbour constant.
   */
  private static List<Keyed> constantsFirst(List<Keyed> units) {
    List<Keyed> out = new ArrayList<>(units.stream().filter(MembersResolver::constant).toList());
    units.stream().filter(k -> !constant(k)).forEach(out::add);
    return out;
  }

  /**
   * Initializers are keyed by their position among the initializers ("init:" index), which matches them only while
   * no side adds or removes one: with one fewer on a side, its "init:0" may be base's second initializer.
   */
  private static boolean sameInitializerCount(List<List<Keyed>> sides) {
    return sides.stream().map(side -> side.stream().filter(k -> k.key().startsWith(INIT_PREFIX)).count()).distinct()
        .count() == 1;
  }

  /** Every single key belongs to the same unit key in all three versions. */
  private static boolean consistent(List<List<Keyed>> sides) {
    Map<String, String> unitOf = new HashMap<>();
    for (List<Keyed> side : sides) {
      for (Keyed k : side) {
        for (String single : k.key().split(KEY_SEPARATOR)) {
          String before = unitOf.putIfAbsent(single, k.key());
          if (before != null && !before.equals(k.key())) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean constant(Keyed k) {
    return k.key().startsWith(Member.CONSTANT_PREFIX);
  }

  /** The ',' or ';' (or "") after the last enum constant of {@code units}. */
  private static Optional<String> lastSeparator(List<Keyed> units) {
    for (int i = units.size() - 1; i >= 0; i--) {
      if (constant(units.get(i))) {
        String last = lastLine(units.get(i).text());
        return Optional.of(last.endsWith(",") || last.endsWith(";") ? last.substring(last.length() - 1) : "");
      }
    }
    return Optional.empty();
  }

  private static List<Keyed> strip(List<Keyed> units) {
    return units.stream().map(k -> constant(k) ? new Keyed(k.key(), withSeparator(k.text(), "")) : k).toList();
  }

  /** Each constant ends with ',' except the last one of the list, which gets {@code last}. */
  private static List<Keyed> separate(List<Keyed> units, String last) {
    int lastConstant = -1;
    for (int i = 0; i < units.size(); i++) {
      if (constant(units.get(i))) {
        lastConstant = i;
      }
    }
    List<Keyed> out = new ArrayList<>();
    for (int i = 0; i < units.size(); i++) {
      Keyed k = units.get(i);
      out.add(constant(k) ? new Keyed(k.key(), withSeparator(k.text(), i == lastConstant ? last : ",")) : k);
    }
    return out;
  }

  /** {@code text} (see {@link #wellFormed}) with the ',' or ';' at the end of its last line replaced by {@code separator}. */
  private static String withSeparator(String text, String separator) {
    List<String> lines = new ArrayList<>(LineText.chunkLines(text));
    String last = lines.get(lines.size() - 1);
    if (last.endsWith(",") || last.endsWith(";")) {
      last = last.substring(0, last.length() - 1);
    }
    lines.set(lines.size() - 1, last + separator);
    StringBuilder sb = new StringBuilder();
    lines.forEach(l -> sb.append(l).append('\n'));
    return sb.toString();
  }

  private static String lastLine(String text) {
    List<String> lines = LineText.chunkLines(text);
    return lines.get(lines.size() - 1);
  }
}
