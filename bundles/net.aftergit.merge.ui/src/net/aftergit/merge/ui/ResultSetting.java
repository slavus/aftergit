package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ManifestResolver;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import org.eclipse.osgi.util.NLS;

/**
 * A setting that shapes results (preferences design §4, §6) as the workspace and the project pages show it: its key,
 * label and an optional explanation under it.
 */
record ResultSetting(String key, String label, String help) {
  /** The contribution of net.aftergit.merge.jdt shown among the built-in resolvers. */
  static final String MEMBERS = "members";

  boolean def() {
    return MergePreferences.defaultOf(key);
  }

  static List<ResultSetting> opening() {
    return List.of(new ResultSetting(MergePreferences.AUTO_RESOLVE_ON_OPEN, Messages.prefAutoResolveOnOpen, null),
        new ResultSetting(MergePreferences.GREEDY, Messages.prefGreedy, null),
        new ResultSetting(MergePreferences.IGNORE_WHITESPACE, Messages.prefIgnoreWhitespace, null));
  }

  /** Java imports, Java members (when contributed), .properties, MANIFEST.MF, Newer version wins, the others. */
  static List<ResultSetting> resolvers() {
    List<ResultSetting> out = new ArrayList<>();
    out.add(resolver(ImportsResolver.ID, Messages.prefResolverImports, null));
    boolean members = ResolverContributions.all().stream().anyMatch(c -> MEMBERS.equals(c.id()));
    if (members) {
      out.add(resolver(MEMBERS, Messages.prefResolverMembers, null));
    }
    out.add(resolver(PropertiesResolver.ID, Messages.prefResolverProperties, null));
    out.add(resolver(ManifestResolver.ID, Messages.prefResolverManifest, null));
    out.add(resolver(NewerVersionResolver.ID, Messages.prefResolverNewerVersion, Messages.prefResolverNewerVersionHelp));
    for (ResolverContributions.Contribution c : ResolverContributions.all()) {
      if (!MEMBERS.equals(c.id())) {
        out.add(resolver(c.id(), NLS.bind(Messages.prefResolverContributed, c.name()), null));
      }
    }
    return out;
  }

  static List<ResultSetting> all() {
    List<ResultSetting> out = new ArrayList<>(opening());
    out.addAll(resolvers());
    return out;
  }

  private static ResultSetting resolver(String id, String label, String help) {
    return new ResultSetting(MergePreferences.RESOLVER_PREFIX + id, label, help);
  }
}
