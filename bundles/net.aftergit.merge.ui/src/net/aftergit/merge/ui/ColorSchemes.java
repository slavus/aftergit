package net.aftergit.merge.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.ui.css.swt.theme.ITheme;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;
import org.osgi.service.prefs.BackingStoreException;

/**
 * The colour schemes of Preferences › AfterGit › Appearance (preferences design §3). A scheme writes the AfterGit
 * colours of the current theme exactly as Colors and Fonts does (Ruling 10), so they can be fine-tuned there; it is
 * not remembered (Ruling 9).
 */
final class ColorSchemes {
  enum Scheme { DEFAULT, COLOR_BLIND, HIGH_CONTRAST }

  /** The only theme css/dark.css styles (Ruling 11). */
  static final String DARK_THEME = "org.eclipse.e4.ui.css.theme.e4_dark";
  /** The preference node the Colors and Fonts page writes to. */
  static final String WORKBENCH_NODE = "org.eclipse.ui.workbench";

  private static final Map<String, RGB> COLOR_BLIND_LIGHT = table(
      "modified", "240,214,90", "252,243,196", "added", "150,198,240", "226,240,252",
      "removed", "250,184,130", "254,234,214", "conflict", "236,140,50", "253,210,165");
  private static final Map<String, RGB> COLOR_BLIND_DARK = table(
      "modified", "110,96,20", "62,58,30", "added", "30,84,140", "30,50,72",
      "removed", "130,70,20", "74,52,36", "conflict", "150,74,10", "92,58,26");
  private static final Map<String, RGB> HIGH_CONTRAST_LIGHT = table(
      "modified", "255,200,60", "255,226,140", "added", "120,210,120", "196,240,196",
      "removed", "255,120,120", "255,200,200", "conflict", "230,90,60", "255,180,165",
      "auto", "0,110,95", "170,230,220", "ai", "100,40,210", "222,205,255");
  private static final Map<String, RGB> HIGH_CONTRAST_DARK = table(
      "modified", "100,76,0", "80,62,0", "added", "20,100,20", "16,70,16",
      "removed", "140,20,20", "96,20,20", "conflict", "150,40,10", "110,34,10",
      "auto", "80,230,205", "10,70,62", "ai", "190,160,255", "60,30,110");

  static {
    COLOR_BLIND_LIGHT.put(ColorIds.DROPPED_TEXT, ColorIds.rgb("140,140,140"));
    COLOR_BLIND_LIGHT.put(ColorIds.TAKEN_STRONG, ColorIds.rgb("176,120,0"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.AUTO_HATCH, ColorIds.rgb("120,205,190"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.lightId(LineStyle.Kind.DROPPED), ColorIds.rgb("222,222,222"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.DROPPED_TEXT, ColorIds.rgb("80,80,80"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.EDITED_STRONG, ColorIds.rgb("60,60,100"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.TAKEN_STRONG, ColorIds.rgb("150,100,0"));
    HIGH_CONTRAST_LIGHT.put(ColorIds.CURRENT, ColorIds.rgb("0,70,200"));
    HIGH_CONTRAST_DARK.put(ColorIds.AUTO_HATCH, ColorIds.rgb("18,88,78"));
    HIGH_CONTRAST_DARK.put(ColorIds.lightId(LineStyle.Kind.DROPPED), ColorIds.rgb("60,60,60"));
    HIGH_CONTRAST_DARK.put(ColorIds.DROPPED_TEXT, ColorIds.rgb("200,200,200"));
    HIGH_CONTRAST_DARK.put(ColorIds.EDITED_STRONG, ColorIds.rgb("190,190,240"));
    HIGH_CONTRAST_DARK.put(ColorIds.TAKEN_STRONG, ColorIds.rgb("255,200,40"));
    HIGH_CONTRAST_DARK.put(ColorIds.CURRENT, ColorIds.rgb("110,170,255"));
  }

  private ColorSchemes() {}

  /** kind, strong, light, kind, strong, light, … */
  private static Map<String, RGB> table(String... v) {
    Map<String, RGB> m = new LinkedHashMap<>();
    for (int i = 0; i < v.length; i += 3) {
      LineStyle.Kind k = LineStyle.Kind.valueOf(v[i].toUpperCase(java.util.Locale.ROOT));
      m.put(ColorIds.strongId(k), ColorIds.rgb(v[i + 1]));
      m.put(ColorIds.lightId(k), ColorIds.rgb(v[i + 2]));
    }
    return m;
  }

  /**
   * Every AfterGit colour of {@code scheme} for the light or the dark theme (Ruling 13); null for the current block
   * where it is the platform's selection colour (light Default and Colour-blind safe).
   */
  static Map<String, RGB> values(Scheme scheme, boolean dark) {
    Map<String, RGB> m = new LinkedHashMap<>(dark ? ColorIds.DARK : ColorIds.DEFAULTS);
    if (!dark) {
      m.put(ColorIds.CURRENT, null);
    }
    switch (scheme) {
      case DEFAULT -> { }
      case COLOR_BLIND -> m.putAll(dark ? COLOR_BLIND_DARK : COLOR_BLIND_LIGHT);
      case HIGH_CONTRAST -> m.putAll(dark ? HIGH_CONTRAST_DARK : HIGH_CONTRAST_LIGHT);
    }
    return m;
  }

  private static IThemeEngine engine() {
    Display d = Display.getCurrent();
    return d != null && d.getData("org.eclipse.e4.ui.css.swt.theme") instanceof IThemeEngine e ? e : null;
  }

  private static ITheme cssTheme() {
    IThemeEngine e = engine();
    return e == null ? null : e.getActiveTheme();
  }

  /** The active CSS theme's id; null before the theme engine runs. */
  static String cssThemeId() {
    ITheme t = cssTheme();
    return t == null ? null : t.getId();
  }

  static boolean darkTheme() {
    ITheme t = cssTheme();
    return t != null && DARK_THEME.equals(t.getId());
  }

  /**
   * The key Colors and Fonts stores {@code id} under in the current theme ({@code
   * ThemeElementHelper.createPreferenceKey(ITheme, ITheme, String)}, Ruling 10): the AfterGit colours are colour
   * definitions of the CSS themes (css/light.css, css/dark.css), so each CSS theme has its own key.
   */
  static String preferenceKey(String cssThemeId, String themeId, String id) {
    String base = IThemeManager.DEFAULT_THEME.equals(themeId) ? id : themeId + "." + id;
    return cssThemeId == null ? base : cssThemeId + "." + base;
  }

  private static RGB platformSelection() {
    return Display.getDefault().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB();
  }

  /** Writes {@code scheme} for the current theme; Default removes what a scheme or the user wrote. */
  static void apply(Scheme scheme) {
    boolean dark = darkTheme();
    org.eclipse.ui.themes.ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(WORKBENCH_NODE);
    Map<String, RGB> base = values(Scheme.DEFAULT, dark);
    for (Map.Entry<String, RGB> e : values(scheme, dark).entrySet()) {
      String id = e.getKey();
      RGB rgb = e.getValue() == null ? platformSelection() : e.getValue();
      String key = preferenceKey(keyTheme(), theme.getId(), id);
      if (scheme == Scheme.DEFAULT || Objects.equals(e.getValue(), base.get(id))) {
        prefs.remove(key);
      } else {
        prefs.put(key, StringConverter.asString(rgb));
      }
      theme.getColorRegistry().put(id, rgb);
    }
    try {
      prefs.flush();
    } catch (BackingStoreException ex) {
      // the colours stay for this session
    }
    Appearance.setFrames(scheme == Scheme.HIGH_CONTRAST ? Appearance.Frames.THICK : Appearance.Frames.NORMAL);
  }

  /**
   * At the start of a session in a light CSS theme that css/light.css does not know (another plug-in's theme): the
   * colour registry wrote the dark theme's values under the plain keys while the dark theme was active, and such a
   * theme reads them from there at a start. A plain key holding one of AfterGit's dark values (of any scheme) and
   * none of its light ones goes, and the colour shows its light default; any other value is the user's and stays.
   */
  static void dropDarkLeftovers() {
    ITheme css = cssTheme();
    if (css == null || darkTheme() || styled(css.getId())) {
      return;
    }
    org.eclipse.ui.themes.ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(WORKBENCH_NODE);
    Map<String, RGB> light = values(Scheme.DEFAULT, false);
    boolean changed = false;
    for (String id : ColorIds.all()) {
      String key = preferenceKey(null, theme.getId(), id);
      String value = prefs.get(key, null);
      RGB rgb = value == null ? null : StringConverter.asRGB(value, null);
      if (rgb == null || !darkOnly(id, rgb)) {
        continue;
      }
      prefs.remove(key);
      RGB want = light.get(id);
      theme.getColorRegistry().put(id, want == null ? platformSelection() : want);
      changed = true;
    }
    if (changed) {
      try {
        prefs.flush();
      } catch (BackingStoreException e) {
        // the colours are right for this session; the next start looks again
      }
    }
  }

  /** {@code rgb} is a dark value of {@code id} in some scheme and no light one. */
  private static boolean darkOnly(String id, RGB rgb) {
    boolean dark = false;
    for (Scheme s : Scheme.values()) {
      if (rgb.equals(values(s, false).get(id))) {
        return false;
      }
      dark |= rgb.equals(values(s, true).get(id));
    }
    return dark;
  }

  /** The CSS themes AfterGit's stylesheets are registered for (plugin.xml). */
  private static java.util.Set<String> styledThemes;

  /**
   * Whether css/light.css or css/dark.css styles the AfterGit colours in the CSS theme {@code cssThemeId}. In any
   * other theme nothing overrides the colour definitions, and Colors and Fonts keeps them under the plain key.
   */
  static synchronized boolean styled(String cssThemeId) {
    if (styledThemes == null) {
      java.util.Set<String> ids = new java.util.HashSet<>();
      for (var e : org.eclipse.core.runtime.Platform.getExtensionRegistry()
          .getConfigurationElementsFor("org.eclipse.e4.ui.css.swt.theme")) {
        if ("stylesheet".equals(e.getName()) && MergePreferences.NODE.equals(e.getContributor().getName())) {
          for (var t : e.getChildren("themeid")) {
            ids.add(t.getAttribute("refid"));
          }
        }
      }
      styledThemes = ids;
    }
    return styledThemes.contains(cssThemeId);
  }

  /**
   * The CSS theme part of the key Colors and Fonts uses in the current theme: the active CSS theme's id where
   * AfterGit's stylesheets override the colour definitions, else null (the plain key).
   */
  static String keyTheme() {
    ITheme css = cssTheme();
    return css != null && styled(css.getId()) ? css.getId() : null;
  }

  /** The colour the resolver paints {@code id} in: the registry's, or its default before anything asked for it. */
  private static RGB shown(ColorRegistry r, String id, boolean dark) {
    RGB rgb = r.hasValueFor(id) ? r.getRGB(id) : values(Scheme.DEFAULT, dark).get(id);
    return rgb == null ? platformSelection() : rgb;
  }

  /** Every AfterGit colour as the registry holds it now: what a Custom page previews. */
  static Map<String, RGB> registryValues() {
    boolean dark = darkTheme();
    ColorRegistry r = Palette.registry();
    Map<String, RGB> m = new LinkedHashMap<>();
    for (String id : ColorIds.all()) {
      m.put(id, shown(r, id, dark));
    }
    return m;
  }

  /** The scheme the current colours are, or empty: "Custom" (Ruling 9). */
  static Optional<Scheme> current() {
    boolean dark = darkTheme();
    ColorRegistry r = Palette.registry();
    for (Scheme s : Scheme.values()) {
      boolean all = true;
      for (Map.Entry<String, RGB> e : values(s, dark).entrySet()) {
        RGB want = e.getValue() == null ? platformSelection() : e.getValue();
        if (!want.equals(shown(r, e.getKey(), dark))) {
          all = false;
          break;
        }
      }
      if (all) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
