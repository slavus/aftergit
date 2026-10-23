package net.aftergit.merge.ui;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Resolver settings kept in the workspace preferences (spec §6.2 layout, §6.6 ⚙). The settings that shape results
 * are also looked up for a project ({@link ProjectSettings}); the setters write the workspace.
 */
final class MergePreferences {
  static final String NODE = net.aftergit.merge.core.ai.AiConsent.NODE; // the AI page forgets the consents here
  static final String LAYOUT = "layout";
  static final String GREEDY = "greedy";
  static final String INNER_LINE = "innerLine";
  static final String AUTO_RESOLVE_ON_OPEN = "autoResolveOnOpen";
  static final String IGNORE_WHITESPACE = "ignoreWhitespace";
  static final String STRUCTURE_SHOW_ALL = "structureShowAll";
  static final String STRUCTURE_SHOWN = "structureShown";
  static final String STRUCTURE_WIDTH = "structureWidth";
  static final String USE_AS_MERGE_TOOL = "useAsMergeTool";
  static final String MARK_ATTENTION = "markAttention";
  static final String SEMANTIC_SIDES = "semanticSides";
  static final String SYNTAX_COLOURS = "syntaxColours";
  static final String RESOLVER_PREFIX = "resolver.";
  static final String SAVE_ACTIONS = "saveActions";
  static final String FINISH_WITH_CONFLICTS = "finishWithConflicts";
  static final String CLOSE_AFTER_FINISH = "closeAfterFinish";
  static final String AI_REMOTE_ALLOWED_PREFIX = net.aftergit.merge.core.ai.AiConsent.PREFIX;

  /** What Save & Mark Resolved does with open conflicts (preferences design §4). */
  enum FinishPolicy { ASK, SAVE_WITH_MARKERS, SAVE_WITHOUT_STAGING }

  private MergePreferences() {}

  /**
   * The default of every boolean key (preferences design §3, §4); a resolver's switch by its id. Whitespace stays
   * significant and Save Actions stay off unless switched on, as before the settings had a page.
   */
  static boolean defaultOf(String key) {
    if (key.startsWith(RESOLVER_PREFIX)) {
      return ResolverPipeline.enabledByDefault(key.substring(RESOLVER_PREFIX.length()));
    }
    return switch (key) {
      case GREEDY, STRUCTURE_SHOWN, IGNORE_WHITESPACE, SAVE_ACTIONS -> false;
      case AUTO_RESOLVE_ON_OPEN, INNER_LINE, CLOSE_AFTER_FINISH, USE_AS_MERGE_TOOL, MARK_ATTENTION, SEMANTIC_SIDES,
          SYNTAX_COLOURS ->
          true;
      default -> throw new IllegalArgumentException("no boolean setting " + key); // a misspelt key reads no default
    };
  }

  /** {@code key} in the workspace, or its default. */
  static boolean bool(String key) { return node().getBoolean(key, defaultOf(key)); }
  static void setBool(String key, boolean v) { node().putBoolean(key, v); flush(); }

  private static IEclipsePreferences node() {
    return InstanceScope.INSTANCE.getNode(NODE);
  }

  static PaneLayout layout() {
    try {
      return PaneLayout.valueOf(node().get(LAYOUT, PaneLayout.ALL.name()));
    } catch (IllegalArgumentException e) {
      return PaneLayout.ALL;
    }
  }

  static void setLayout(PaneLayout layout) {
    node().put(LAYOUT, layout.name());
    flush();
  }

  static boolean greedy() { return greedy(null); }
  static boolean greedy(IProject p) { return ProjectSettings.bool(p, GREEDY, defaultOf(GREEDY)); }
  static void setGreedy(boolean v) { setBool(GREEDY, v); }
  static boolean innerLine() { return bool(INNER_LINE); }
  static void setInnerLine(boolean v) { setBool(INNER_LINE, v); }
  static boolean autoResolveOnOpen() { return autoResolveOnOpen(null); }
  static boolean autoResolveOnOpen(IProject p) {
    return ProjectSettings.bool(p, AUTO_RESOLVE_ON_OPEN, defaultOf(AUTO_RESOLVE_ON_OPEN));
  }
  static void setAutoResolveOnOpen(boolean v) { setBool(AUTO_RESOLVE_ON_OPEN, v); }
  static boolean ignoreWhitespace() { return ignoreWhitespace(null); }
  static boolean ignoreWhitespace(IProject p) {
    return ProjectSettings.bool(p, IGNORE_WHITESPACE, defaultOf(IGNORE_WHITESPACE));
  }
  static void setIgnoreWhitespace(boolean v) { setBool(IGNORE_WHITESPACE, v); }

  /** "Apply Java Save Actions when saving" (preferences design §4); off by default. */
  static boolean saveActions() { return bool(SAVE_ACTIONS); }
  static void setSaveActions(boolean v) { setBool(SAVE_ACTIONS, v); }

  /** "Close the editor after Save & Mark Resolved"; on by default. */
  static boolean closeAfterFinish() { return bool(CLOSE_AFTER_FINISH); }
  static void setCloseAfterFinish(boolean v) { setBool(CLOSE_AFTER_FINISH, v); }

  /** What Save & Mark Resolved does when open conflicts are left; it asks by default. */
  static FinishPolicy finishPolicy() {
    try {
      return FinishPolicy.valueOf(node().get(FINISH_WITH_CONFLICTS, FinishPolicy.ASK.name()));
    } catch (IllegalArgumentException e) {
      return FinishPolicy.ASK;
    }
  }

  static void setFinishPolicy(FinishPolicy p) {
    node().put(FINISH_WITH_CONFLICTS, p.name());
    flush();
  }

  /** The comparison of {@code p}'s files (null: the workspace's). */
  static ComparisonPolicy policy(IProject p) {
    return ignoreWhitespace(p) ? ComparisonPolicy.IGNORE_WHITESPACES : ComparisonPolicy.DEFAULT;
  }

  /** "Show all members" of the Structure panel and the Outline (Ruling 11); off by default. */
  static boolean structureShowAll() { return node().getBoolean(STRUCTURE_SHOW_ALL, false); }
  static void setStructureShowAll(boolean v) { node().putBoolean(STRUCTURE_SHOW_ALL, v); flush(); }

  /** The Structure column (structure-compare design §2): hidden by default, its width for every resolver. */
  static boolean structureShown() { return bool(STRUCTURE_SHOWN); }
  static void setStructureShown(boolean v) { setBool(STRUCTURE_SHOWN, v); }
  static int structureWidth() { return node().getInt(STRUCTURE_WIDTH, MergeLayout.STRUCTURE_DEFAULT); }
  static void setStructureWidth(int v) { node().putInt(STRUCTURE_WIDTH, v); flush(); }

  /** "Use AfterGit as the Merge Tool": EGit's Merge Tool command opens AfterGit on a text conflict. */
  static boolean useAsMergeTool() { return bool(USE_AS_MERGE_TOOL); }
  static void setUseAsMergeTool(boolean v) { setBool(USE_AS_MERGE_TOOL, v); }

  /** "Mark files that still need attention": AfterGit's warning markers on files it saved (§4). */
  static boolean markAttention() { return bool(MARK_ATTENTION); }
  static void setMarkAttention(boolean v) { setBool(MARK_ATTENTION, v); }

  /** Semantic colours in the side panes of Java files (Preferences > AfterGit); on by default. */
  static boolean semanticSides() { return bool(SEMANTIC_SIDES); }
  static void setSemanticSides(boolean v) { setBool(SEMANTIC_SIDES, v); }

  /** TextMate colours in the three panes of files of other languages (Preferences > AfterGit); on by default. */
  static boolean syntaxColours() { return bool(SYNTAX_COLOURS); }
  static void setSyntaxColours(boolean v) { setBool(SYNTAX_COLOURS, v); }

  /** Runs {@code listener} (on the thread that changed it) whenever {@code key} changes; the result stops it. */
  static Runnable onChange(String key, Runnable listener) {
    IEclipsePreferences n = node();
    IEclipsePreferences.IPreferenceChangeListener l = e -> {
      if (key.equals(e.getKey())) {
        listener.run();
      }
    };
    n.addPreferenceChangeListener(l);
    return () -> n.removePreferenceChangeListener(l);
  }

  /**
   * The switch of the resolver {@code id} (resolvers 6c design); every resolver is on by default but "Newer version
   * wins" ({@link ResolverPipeline#enabledByDefault}).
   */
  static boolean resolverEnabled(String id) { return resolverEnabled(null, id); }

  /** The switch of the resolver {@code id} for the files of {@code p} (null: the workspace's). */
  static boolean resolverEnabled(IProject p, String id) {
    return ProjectSettings.bool(p, RESOLVER_PREFIX + id, defaultOf(RESOLVER_PREFIX + id));
  }
  static void setResolverEnabled(String id, boolean v) { setBool(RESOLVER_PREFIX + id, v); }

  /**
   * True once the user said "Do not ask again" for conflicts sent to {@code destination} (resolve-with-ai §5), such as
   * "https://api.anthropic.com:443"; a new destination is asked about again.
   */
  static boolean aiRemoteAllowed(String destination) { return node().getBoolean(remoteKey(destination), false); }
  static void setAiRemoteAllowed(String destination, boolean v) { node().putBoolean(remoteKey(destination), v); flush(); }

  /** The key of a destination: encoded, since a '/' in a key would name a child node in the preference file. */
  private static String remoteKey(String destination) {
    return AI_REMOTE_ALLOWED_PREFIX + java.net.URLEncoder.encode(destination, java.nio.charset.StandardCharsets.UTF_8);
  }

  static void reset() {
    try {
      // the colours were migrated once and for all (ColorMigration): that stays
      boolean migrated = node().getBoolean(ColorMigration.MIGRATED, false);
      node().clear();
      if (migrated) {
        node().putBoolean(ColorMigration.MIGRATED, true);
      }
      node().flush();
    } catch (BackingStoreException e) {
      // in-memory values are cleared anyway
    }
  }

  private static void flush() {
    try {
      node().flush();
    } catch (BackingStoreException e) {
      // the value stays in memory for this session
    }
  }
}
