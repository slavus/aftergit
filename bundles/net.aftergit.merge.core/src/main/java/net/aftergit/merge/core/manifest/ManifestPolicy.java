package net.aftergit.merge.core.manifest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.aftergit.merge.core.units.Keyed;
import net.aftergit.merge.core.units.UnitMerge;

/**
 * How manifest headers both sides changed are merged (resolvers 6c design §3): a list header merges per clause with the
 * same rules as units (a clause changed differently on both sides is a conflict); Bundle-Version changed on both sides
 * is always a conflict; every other header changed on both sides is a conflict.
 */
public final class ManifestPolicy implements UnitMerge.Policy {
  public static final ManifestPolicy INSTANCE = new ManifestPolicy();

  /** Lower-case names of the headers whose value is a list of clauses. */
  public static final Set<String> LIST_HEADERS = Set.of("require-bundle", "import-package", "export-package",
      "bundle-classpath", "provide-capability", "require-capability", "dynamicimport-package");

  private ManifestPolicy() {}

  @Override
  public boolean sameChangeConflicts(String key) {
    return headerName(key).equals("bundle-version");
  }

  @Override
  public Optional<String> bothChanged(String key, String base, String ours, String theirs) {
    if (base == null || !LIST_HEADERS.contains(headerName(key))) {
      return Optional.empty();
    }
    Optional<Header> b = Header.parse(base);
    Optional<Header> o = Header.parse(ours);
    Optional<Header> t = Header.parse(theirs);
    if (b.isEmpty() || o.isEmpty() || t.isEmpty() || !b.get().gap().equals(o.get().gap())
        || !b.get().gap().equals(t.get().gap())) {
      return Optional.empty();
    }
    Optional<List<String>> bc = ManifestClauses.split(b.get().value());
    Optional<List<String>> oc = ManifestClauses.split(o.get().value());
    Optional<List<String>> tc = ManifestClauses.split(t.get().value());
    if (bc.isEmpty() || oc.isEmpty() || tc.isEmpty()) {
      return Optional.empty();
    }
    Optional<List<Keyed>> merged = UnitMerge.merge(keyed(bc.get()), keyed(oc.get()), keyed(tc.get()),
        UnitMerge.Policy.STRICT);
    if (merged.isEmpty() || merged.get().isEmpty()) {
      return Optional.empty(); // a list header without clauses is no header: the user decides
    }
    List<String> clauses = merged.get().stream().map(Keyed::text).toList();
    if (clauses.equals(oc.get())) {
      return Optional.of(ours);
    }
    return Optional.of(ManifestWriter.listHeader(o.get(), clauses));
  }

  /** The lower-case header name of a unit key (section, U+0000, name). */
  static String headerName(String key) {
    return key.substring(key.lastIndexOf('\u0000') + 1);
  }

  private static List<Keyed> keyed(List<String> clauses) {
    return clauses.stream().map(c -> new Keyed(ManifestClauses.key(c), c)).toList();
  }
}
