package net.aftergit.merge.ui;

import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * The behaviour settings a project may carry for its team (preferences design §6), in
 * .settings/net.aftergit.merge.ui.prefs. As in Eclipse, a project has its own settings while any of them is set
 * there; a value is looked up in the project, then in the workspace, then its default. A file reached through a
 * link or a nested project follows the project of its handle.
 */
final class ProjectSettings {
  /** Project › Properties › AfterGit. */
  static final String PROPERTY_PAGE_ID = "net.aftergit.merge.properties";

  private ProjectSettings() {}

  /** The keys that shape results; everything else (colours, layout, inner-line …) stays personal. */
  static boolean shapesResults(String key) {
    return MergePreferences.AUTO_RESOLVE_ON_OPEN.equals(key) || MergePreferences.GREEDY.equals(key)
        || MergePreferences.IGNORE_WHITESPACE.equals(key) || key.startsWith(MergePreferences.RESOLVER_PREFIX);
  }

  private static IEclipsePreferences node(IProject project) {
    return new ProjectScope(project).getNode(MergePreferences.NODE);
  }

  /** True while {@code project} has its own settings ("Enable project specific settings"). */
  static boolean enabled(IProject project) {
    if (project == null || !project.isAccessible()) {
      return false;
    }
    try {
      for (String k : node(project).keys()) {
        if (shapesResults(k)) {
          return true;
        }
      }
    } catch (BackingStoreException e) {
      log(project, e); // unreadable: the workspace settings apply
    }
    return false;
  }

  /** {@code key} for the files of {@code project} (null: the workspace alone). */
  static boolean bool(IProject project, String key, boolean def) {
    boolean workspace = InstanceScope.INSTANCE.getNode(MergePreferences.NODE).getBoolean(key, def);
    return shapesResults(key) && enabled(project) ? node(project).getBoolean(key, workspace) : workspace;
  }

  /**
   * Makes {@code values} the project's own settings, replacing those it had; personal keys are left out. Empty:
   * the project has none any more and the workspace applies.
   */
  static void store(IProject project, Map<String, Boolean> values) {
    if (!project.isAccessible()) {
      return; // a closed project has no settings to write
    }
    IEclipsePreferences n = node(project);
    try {
      for (String k : n.keys()) {
        if (shapesResults(k)) {
          n.remove(k);
        }
      }
      values.forEach((k, v) -> {
        if (shapesResults(k)) {
          n.putBoolean(k, v); // also a default value: its presence says the project has its own settings
        }
      });
      n.flush();
    } catch (BackingStoreException e) {
      log(project, e);
    }
  }

  private static void log(IProject project, BackingStoreException e) {
    ILog.of(ProjectSettings.class).log(new Status(IStatus.WARNING, MergePreferences.NODE,
        "The AfterGit settings of " + project.getName() + " could not be read or written", e));
  }
}
