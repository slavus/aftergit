package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Preferences design §3 and §7: the colour schemes, their contrasts and how they are written. */
class ColorSchemesTest {
  static final RGB LIGHT_BG = new RGB(255, 255, 255);
  static final RGB LIGHT_FG = new RGB(0, 0, 0);
  /** The dark theme's editor (org.eclipse.ui.themes, e4-dark_linux.css). */
  static final RGB DARK_BG = new RGB(30, 31, 34);
  static final RGB DARK_FG = new RGB(204, 204, 204);

  @AfterEach
  void restore() {
    ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
    MergePreferences.reset();
  }

  private static RGB c(Map<String, RGB> m, String id) {
    return m.get(id);
  }

  private static void need(List<String> low, String what, RGB a, RGB b, double min) {
    double r = DarkThemeTest.contrast(a, b);
    if (r < min) {
      low.add(String.format(Locale.ROOT, "%s %.2f < %.1f", what, r, min));
    }
  }

  @Test
  void everySchemeNamesEveryColourForLightAndDark() {
    for (ColorSchemes.Scheme s : ColorSchemes.Scheme.values()) {
      assertEquals(ColorIds.all(), ColorSchemes.values(s, false).keySet(), s + " light");
      assertEquals(ColorIds.all(), ColorSchemes.values(s, true).keySet(), s + " dark");
    }
  }

  @Test
  void theDefaultSchemeIsThePluginXmlAndTheDarkCss() {
    Map<String, RGB> light = ColorSchemes.values(ColorSchemes.Scheme.DEFAULT, false);
    ColorIds.DEFAULTS.forEach((id, rgb) -> assertEquals(rgb, light.get(id), id));
    assertNull(light.get(ColorIds.CURRENT), "light Default: the platform's selection colour");
    assertEquals(ColorIds.DARK, ColorSchemes.values(ColorSchemes.Scheme.DEFAULT, true));
  }

  @Test
  void highContrastMeetsItsContrasts() {
    for (boolean dark : new boolean[] {false, true}) {
      Map<String, RGB> m = ColorSchemes.values(ColorSchemes.Scheme.HIGH_CONTRAST, dark);
      RGB bg = dark ? DARK_BG : LIGHT_BG;
      RGB fg = dark ? DARK_FG : LIGHT_FG;
      List<String> low = new ArrayList<>();
      for (LineStyle.Kind k : LineStyle.Kind.values()) {
        if (k != LineStyle.Kind.NONE) {
          need(low, "text on " + k + " light", fg, c(m, ColorIds.lightId(k)), 4.5);
        }
      }
      need(low, "text on the hatching", fg, c(m, ColorIds.AUTO_HATCH), 4.5);
      for (LineStyle.Kind k : List.of(LineStyle.Kind.MODIFIED, LineStyle.Kind.ADDED, LineStyle.Kind.REMOVED,
          LineStyle.Kind.CONFLICT)) {
        need(low, "text on " + k + " strong", fg, c(m, ColorIds.strongId(k)), 4.5);
      }
      for (String id : List.of(ColorIds.strongId(LineStyle.Kind.AUTO), ColorIds.strongId(LineStyle.Kind.AI),
          ColorIds.EDITED_STRONG, ColorIds.TAKEN_STRONG, ColorIds.CURRENT)) {
        need(low, id + " on the background", c(m, id), bg, 3.0);
      }
      need(low, "current on conflict light", c(m, ColorIds.CURRENT), c(m, ColorIds.lightId(LineStyle.Kind.CONFLICT)), 3.0);
      need(low, "current on auto light", c(m, ColorIds.CURRENT), c(m, ColorIds.lightId(LineStyle.Kind.AUTO)), 3.0);
      need(low, "dropped text", c(m, ColorIds.DROPPED_TEXT), c(m, ColorIds.lightId(LineStyle.Kind.DROPPED)), 4.5);
      assertTrue(low.isEmpty(), (dark ? "dark: " : "light: ") + low);
    }
  }

  @Test
  void colourBlindSafeKeepsTheContrastsOfTheDefault() {
    for (boolean dark : new boolean[] {false, true}) {
      Map<String, RGB> m = ColorSchemes.values(ColorSchemes.Scheme.COLOR_BLIND, dark);
      RGB bg = dark ? DARK_BG : LIGHT_BG;
      RGB fg = dark ? DARK_FG : LIGHT_FG;
      List<String> low = new ArrayList<>();
      for (LineStyle.Kind k : LineStyle.Kind.values()) {
        if (k != LineStyle.Kind.NONE) {
          need(low, "text on " + k + " light", fg, c(m, ColorIds.lightId(k)), 4.5);
        }
      }
      for (String id : List.of(ColorIds.strongId(LineStyle.Kind.AUTO), ColorIds.strongId(LineStyle.Kind.AI),
          ColorIds.EDITED_STRONG)) {
        need(low, id + " on the background", c(m, id), bg, 3.0);
      }
      need(low, "dropped text", c(m, ColorIds.DROPPED_TEXT), c(m, ColorIds.lightId(LineStyle.Kind.DROPPED)), 2.4);
      assertTrue(low.isEmpty(), (dark ? "dark: " : "light: ") + low);
      assertNotEquals(ColorIds.DEFAULTS.get(ColorIds.lightId(LineStyle.Kind.ADDED)),
          m.get(ColorIds.lightId(LineStyle.Kind.ADDED)), "added is blue, not green");
    }
  }

  @Test
  void applyingASchemeWritesWhatColorsAndFontsWouldAndDefaultTakesItBack() {
    assertFalse(ColorSchemes.darkTheme());
    ColorSchemes.apply(ColorSchemes.Scheme.HIGH_CONTRAST);
    String id = ColorIds.lightId(LineStyle.Kind.CONFLICT);
    String key = ColorSchemes.preferenceKey(ThemeSwitch.engine().getActiveTheme().getId(),
        PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getId(), id);
    assertEquals("255,180,165", InstanceScope.INSTANCE.getNode(ColorSchemes.WORKBENCH_NODE).get(key, null));
    assertEquals(new RGB(255, 180, 165), Palette.light(LineStyle.Kind.CONFLICT).getRGB());
    assertEquals(Optional.of(ColorSchemes.Scheme.HIGH_CONTRAST), ColorSchemes.current());
    assertEquals(Appearance.Frames.THICK, Appearance.frames(), "Ruling 12");
    ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
    assertNull(InstanceScope.INSTANCE.getNode(ColorSchemes.WORKBENCH_NODE).get(key, null));
    assertEquals(ColorIds.DEFAULTS.get(id), Palette.light(LineStyle.Kind.CONFLICT).getRGB());
    assertEquals(Optional.of(ColorSchemes.Scheme.DEFAULT), ColorSchemes.current());
    assertEquals(Appearance.Frames.NORMAL, Appearance.frames());
  }

  @Test
  void thePreferenceKeyIsTheOneColorsAndFontsReads() throws Exception {
    Class<?> helper = Platform.getBundle("org.eclipse.ui.workbench")
        .loadClass("org.eclipse.ui.internal.themes.ThemeElementHelper");
    Method m = helper.getMethod("createPreferenceKey", org.eclipse.e4.ui.css.swt.theme.ITheme.class,
        org.eclipse.ui.themes.ITheme.class, String.class);
    org.eclipse.e4.ui.css.swt.theme.ITheme css = ThemeSwitch.engine().getActiveTheme();
    org.eclipse.ui.themes.ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
    assertEquals(m.invoke(null, css, theme, ColorIds.CURRENT),
        ColorSchemes.preferenceKey(css.getId(), theme.getId(), ColorIds.CURRENT));
  }

  @Test
  void aColourChangedByHandShowsCustom() {
    ColorSchemes.apply(ColorSchemes.Scheme.COLOR_BLIND);
    assertEquals(Optional.of(ColorSchemes.Scheme.COLOR_BLIND), ColorSchemes.current());
    Palette.registry().put(ColorIds.lightId(LineStyle.Kind.CONFLICT), new RGB(1, 2, 3));
    assertEquals(Optional.empty(), ColorSchemes.current());
  }

  @Test
  void aSchemeBelongsToTheThemeItWasChosenIn() {
    ColorSchemes.apply(ColorSchemes.Scheme.HIGH_CONTRAST);
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      assertTrue(ColorSchemes.darkTheme());
      assertEquals(Optional.of(ColorSchemes.Scheme.DEFAULT), ColorSchemes.current(), "dark has its own values");
    }
    UiDriver.flush();
    assertEquals(new RGB(255, 180, 165), Palette.light(LineStyle.Kind.CONFLICT).getRGB(), "back in light");
  }

  @Test
  void theWordsOfResolvedBlocksStandOutInEverySchemeLightAndDark() {
    for (ColorSchemes.Scheme scheme : ColorSchemes.Scheme.values()) {
      for (boolean dark : new boolean[] {false, true}) {
        Map<String, RGB> v = ColorSchemes.values(scheme, dark);
        if (v.isEmpty()) {
          continue; // Custom: the user's own colours
        }
        String where = scheme + (dark ? " dark" : " light");
        RGB autoLight = v.get(ColorIds.lightId(LineStyle.Kind.AUTO));
        RGB autoWord = Palette.contrasted(new Color(autoLight), new Color(v.get(ColorIds.strongId(LineStyle.Kind.AUTO))),
            Palette.AUTO_WORD_CONTRAST).getRGB();
        assertTrue(Palette.contrast(autoWord, autoLight) >= 2.0, where + ": auto words on the auto fill");
        assertTrue(Palette.contrast(autoWord, v.get(ColorIds.AUTO_HATCH)) >= 1.5, where + ": unlike the stripes");
        Color droppedLight = new Color(v.get(ColorIds.lightId(LineStyle.Kind.DROPPED)));
        Color droppedText = new Color(v.get(ColorIds.DROPPED_TEXT));
        RGB wordBg = Palette.mix(droppedLight, droppedText, 0.3).getRGB();
        RGB wordFg = Palette.mix(droppedLight, droppedText, 1.6).getRGB();
        assertTrue(Palette.contrast(wordFg, wordBg) >= 3.0, where + ": a dropped side's words readable, "
            + wordFg + " on " + wordBg);
        assertTrue(Palette.contrast(wordFg, wordBg) > Palette.contrast(droppedText.getRGB(), wordBg),
            where + ": darker than the struck text");
      }
    }
  }
}
