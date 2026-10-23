package net.aftergit.merge.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Once per workspace, at the first use of the colours: before the AfterGit colours were CSS colour definitions,
 * Colors and Fonts stored a changed colour under the plain key {@code net.aftergit.merge.color.<x>}, and the old
 * css/dark.css wrote its values there. A changed colour moves to the key of the current theme ({@link
 * ColorSchemes#preferenceKey}), the theme it was most likely set in; the old dark values go. Only AfterGit's keys
 * are touched.
 */
final class ColorMigration {
  /** In AfterGit's preferences: the plain keys were looked at. */
  static final String MIGRATED = "colors.migrated";

  private static boolean checked;
  /** The active CSS theme's id; replaceable for tests. */
  private static java.util.function.Supplier<String> cssTheme = ColorSchemes::cssThemeId;

  private ColorMigration() {}

  /** Runs the migration the first time it is asked for in this session. */
  static void ensure() {
    if (!checked && PlatformUI.isWorkbenchRunning()) {
      // before the theme engine runs there is no theme to migrate to: look again next time
      checked = run() || InstanceScope.INSTANCE.getNode(MergePreferences.NODE).getBoolean(MIGRATED, false);
      if (checked) {
        ColorSchemes.dropDarkLeftovers();
      }
    }
  }

  /** The next {@link #ensure} looks again; for tests. */
  static void forgetForTests() {
    checked = false;
  }

  /** The CSS theme the migration sees; null puts the real one back. For tests. */
  static void cssThemeForTests(java.util.function.Supplier<String> theme) {
    cssTheme = theme != null ? theme : ColorSchemes::cssThemeId;
  }

  /** Migrates unless it was done; false when it was done before or no CSS theme is active yet. */
  static boolean run() {
    IEclipsePreferences own = InstanceScope.INSTANCE.getNode(MergePreferences.NODE);
    String css = cssTheme.get();
    if (own.getBoolean(MIGRATED, false) || css == null) {
      return false;
    }
    boolean dark = ColorSchemes.darkTheme();
    org.eclipse.ui.themes.ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    ColorRegistry registry = theme.getColorRegistry();
    IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(ColorSchemes.WORKBENCH_NODE);
    Map<String, RGB> defaults = ColorSchemes.values(ColorSchemes.Scheme.DEFAULT, dark);
    Map<String, RGB> shown = new LinkedHashMap<>();
    for (String id : ColorIds.all()) {
      String plain = IThemeManager.DEFAULT_THEME.equals(theme.getId()) ? id : theme.getId() + "." + id;
      String value = prefs.get(plain, null);
      RGB rgb = value == null ? null : StringConverter.asRGB(value, null);
      if (rgb == null) {
        continue;
      }
      boolean oldDark = rgb.equals(ColorIds.DARK.get(id));
      if (oldDark && dark) {
        continue; // the dark theme's own value: the registry writes it there again on every change
      }
      String key = ColorSchemes.preferenceKey(ColorSchemes.styled(css) ? css : null, theme.getId(), id);
      if (key.equals(plain)) {
        continue; // a theme AfterGit's stylesheets do not know reads the plain key: nothing to move
      }
      RGB lightDefault = ColorIds.CURRENT.equals(id)
          ? Display.getCurrent().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB() : ColorIds.DEFAULTS.get(id);
      if (!oldDark && !rgb.equals(lightDefault) && prefs.get(key, null) == null) {
        prefs.put(key, value); // changed by the user: kept for the theme of now
      }
      prefs.remove(plain);
      // what the theme shows now: its own key, else its default (the platform's selection colour for the current
      // block in light themes, which the theme engine keeps)
      String mine = prefs.get(key, null);
      RGB want = mine != null ? StringConverter.asRGB(mine, null) : defaults.get(id);
      if (want != null) {
        shown.put(id, want);
      }
    }
    shown.forEach(registry::put);
    own.putBoolean(MIGRATED, true);
    try {
      prefs.flush();
      own.flush();
    } catch (BackingStoreException e) {
      // done for this session; the next one looks again
    }
    return true;
  }
}
