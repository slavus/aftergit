package net.aftergit.merge.core.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.core.structure.Visibility;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.Unit;

/**
 * One node per manifest header (structure-compare design §1); a list header (ManifestPolicy.LIST_HEADERS) has its
 * clauses as children, on the lines their text stands on (Ruling 22). An unreadable manifest has no nodes.
 */
public final class ManifestStructure implements StructureLocator {
  public static final ManifestStructure INSTANCE = new ManifestStructure();

  private ManifestStructure() {}

  @Override
  public List<StructureItem> locate(String text) {
    Optional<Body> body = ManifestUnits.INSTANCE.segment(text, 0, 0);
    List<StructureItem> out = new ArrayList<>();
    if (body.isEmpty()) {
      return out;
    }
    LineText lines = LineText.of(text);
    for (Unit u : body.get().units()) {
      if (Unit.TRAILING.equals(u.key())) {
        continue;
      }
      Optional<Header> h = Header.parse(lines.chunk(u.start(), u.end()));
      if (h.isEmpty()) {
        continue;
      }
      int headerLine = u.start() + h.get().gap().length();
      out.add(new StructureItem("header:" + u.key(), NodeKind.HEADER, h.get().name(), Visibility.PACKAGE, u.start(),
          u.end(), clauses(h.get(), headerLine)));
    }
    return out;
  }

  private static List<StructureItem> clauses(Header h, int headerLine) {
    if (!ManifestPolicy.LIST_HEADERS.contains(h.name().toLowerCase(Locale.ROOT))) {
      return List.of();
    }
    Optional<List<String>> split = ManifestClauses.split(h.value());
    if (split.isEmpty()) {
      return List.of();
    }
    // where each line's part of the joined value starts
    List<Integer> starts = new ArrayList<>();
    String first = h.headerLine().substring(h.name().length() + 1);
    int at = first.startsWith(" ") ? first.length() - 1 : first.length();
    starts.add(0);
    for (String c : h.continuations()) {
      starts.add(at);
      at += c.length() - 1;
    }
    List<StructureItem> out = new ArrayList<>();
    int from = 0;
    for (String clause : split.get()) {
      int s = h.value().indexOf(clause, from);
      int e = s + clause.length();
      from = e;
      String key = ManifestClauses.key(clause);
      out.add(new StructureItem("clause:" + key, NodeKind.CLAUSE, key, Visibility.PACKAGE,
          headerLine + lineOf(starts, s), headerLine + lineOf(starts, e - 1) + 1, List.of()));
    }
    return out;
  }

  private static int lineOf(List<Integer> starts, int offset) {
    int line = 0;
    for (int i = 1; i < starts.size(); i++) {
      if (starts.get(i) <= offset) {
        line = i;
      }
    }
    return line;
  }
}
