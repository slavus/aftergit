package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ui-polish design §2: on the light theme text stays readable on every light fill (contrast at least 4.5). */
class LightThemeTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
  }

  @Test
  void lineNumbersTakeTheLightEditorColoursInJavaPanes() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject("calc-light-numbers", "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    DarkThemeTest.lineNumbersFollowTheText(input, "light-line-numbers-java", false);
  }

  @Test
  void lineNumbersTakeTheLightEditorColoursInPlainPanes() throws Exception {
    repos.conflict("notes.txt", "a\nb\nc\n", "a\nours\nc\n", "a\ntheirs\nc\n");
    IFile file = repos.plainProject("plain-light-numbers", "notes.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    DarkThemeTest.lineNumbersFollowTheText(input, "light-line-numbers-plain", false);
  }

  @Test
  void lineNumbersFollowASwitchFromDarkToLight() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    MergeEditorInput input;
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      IFile file = repos.javaProject("calc-switch-numbers", "src/demo/Calc.java");
      input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
      DarkThemeTest.lineNumbersFollowTheText(input, "switch-line-numbers-dark", true);
    }
    DarkThemeTest.lineNumbersFollowTheText(input, "switch-line-numbers-light", false);
  }

  @Test
  void textStaysReadableOnEveryLightFill() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject("calc-light", "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    StyledText t = input.viewer().pane(PaneKind.RESULT).text();
    RGB bg = t.getBackground().getRGB();
    RGB fg = t.getForeground().getRGB();
    assertTrue(DarkThemeTest.luminance(bg) > 0.5, "the light theme is active, editor background " + bg);
    List<String> low = new ArrayList<>();
    for (LineStyle.Kind k : LineStyle.Kind.values()) {
      if (k == LineStyle.Kind.NONE) {
        continue;
      }
      double ratio = DarkThemeTest.contrast(fg, Palette.light(k).getRGB());
      if (ratio < 4.5) {
        low.add(String.format(Locale.ROOT, "text on %s light %.2f < 4.5", k, ratio));
      }
    }
    assertTrue(low.isEmpty(), "too little contrast on the light theme (fg " + fg + "): " + low);
  }

  @Test
  void barsFramesAndTheCurrentColourStandOutOnTheLightEditor() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject("calc-light-bars", "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    RGB bg = input.viewer().pane(PaneKind.RESULT).text().getBackground().getRGB();
    List<String> low = new ArrayList<>();
    check(low, "auto bar and frame", DarkThemeTest.contrast(Palette.strong(LineStyle.Kind.AUTO).getRGB(), bg), 3.0);
    check(low, "edited bar", DarkThemeTest.contrast(Palette.editedStrong().getRGB(), bg), 3.0);
    check(low, "current block frame", DarkThemeTest.contrast(Palette.current().getRGB(), bg), 3.0);
    check(low, "dropped text on dropped light", DarkThemeTest.contrast(Palette.strong(LineStyle.Kind.DROPPED).getRGB(),
        Palette.light(LineStyle.Kind.DROPPED).getRGB()), 2.4);
    assertTrue(low.isEmpty(), "too little contrast on the light theme (bg " + bg + "): " + low);
  }

  /** The CSS themes css/light.css is registered for. */
  private static java.util.Set<String> lightStylesheetThemes() {
    var themes = new java.util.TreeSet<String>();
    for (var e : org.eclipse.core.runtime.Platform.getExtensionRegistry()
        .getConfigurationElementsFor("org.eclipse.e4.ui.css.swt.theme")) {
      if ("stylesheet".equals(e.getName()) && "css/light.css".equals(e.getAttribute("uri"))
          && "net.aftergit.merge.ui".equals(e.getContributor().getName())) {
        for (var t : e.getChildren("themeid")) {
          themes.add(t.getAttribute("refid"));
        }
      }
    }
    return themes;
  }

  /** Eclipse's own CSS themes of this installation other than dark. */
  private static List<String> eclipseLightThemes() {
    List<String> ids = new ArrayList<>();
    for (var t : ThemeSwitch.engine().getThemes()) {
      if (t.getId().startsWith("org.eclipse.e4.ui.css.theme.") && !ThemeSwitch.DARK.equals(t.getId())) {
        ids.add(t.getId());
      }
    }
    return ids;
  }

  @Test
  void theLightColoursAreRegisteredForEveryLightThemeOfEclipse() {
    var themes = lightStylesheetThemes();
    // System and High contrast are gone from newer Eclipse versions, still there in older ones
    assertTrue(themes.containsAll(List.of("org.eclipse.e4.ui.css.theme.e4_default",
        "org.eclipse.e4.ui.css.theme.e4_classic", "org.eclipse.e4.ui.css.theme.e4_system",
        "org.eclipse.e4.ui.css.theme.high-contrast")), themes.toString());
    assertTrue(themes.containsAll(eclipseLightThemes()), eclipseLightThemes() + " in " + themes);
    assertFalse(themes.contains(ThemeSwitch.DARK));
  }

  @Test
  void everyLightThemeShowsTheLightColoursAfterADarkSession() {
    RGB light = ColorIds.DEFAULTS.get(ColorIds.strongId(LineStyle.Kind.CONFLICT));
    for (String id : eclipseLightThemes()) {
      try (ThemeSwitch dark = ThemeSwitch.dark()) {
        ThemeSwitch.engine().setTheme(id, false);
        UiDriver.flush();
        ConflictRepos.waitUntil(() -> light.equals(Palette.strong(LineStyle.Kind.CONFLICT).getRGB()), 10,
            "the light AfterGit colours in " + id);
      }
    }
  }

  /** A light CSS theme of another plug-in, which css/light.css does not know (registered once per test run). */
  static final String OTHER_THEME = "net.aftergit.test.theme.other-light";

  private static String otherTheme() {
    var engine = ThemeSwitch.engine();
    if (engine.getThemes().stream().noneMatch(t -> OTHER_THEME.equals(t.getId()))) {
      engine.registerTheme(OTHER_THEME, "Another light theme",
          "platform:/plugin/org.eclipse.ui.themes/css/e4_default_gtk.css");
    }
    return OTHER_THEME;
  }

  private static org.eclipse.core.runtime.preferences.IEclipsePreferences workbench() {
    return org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(ColorSchemes.WORKBENCH_NODE);
  }

  private static final String CONFLICT_STRONG = ColorIds.strongId(LineStyle.Kind.CONFLICT);

  @Test
  void aThemeOfAnotherPlugInShowsTheLightColoursAfterADarkSession() {
    String other = otherTheme();
    RGB light = ColorIds.DEFAULTS.get(CONFLICT_STRONG);
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      ThemeSwitch.engine().setTheme(other, false);
      UiDriver.flush();
      ConflictRepos.waitUntil(() -> light.equals(Palette.strong(LineStyle.Kind.CONFLICT).getRGB()), 10,
          "the light AfterGit colours in another plug-in's theme");
      // what a restart in that theme reads: Colors and Fonts' plain key, as nothing overrides the definition there
      String plain = workbench().get(CONFLICT_STRONG, null);
      assertTrue(plain == null || light.equals(org.eclipse.jface.resource.StringConverter.asRGB(plain)), plain);
    }
  }

  @Test
  void aStartInAThemeOfAnotherPlugInDropsTheValuesTheDarkThemeLeft() {
    String other = otherTheme();
    RGB light = ColorIds.DEFAULTS.get(CONFLICT_STRONG);
    String previous = ThemeSwitch.engine().getActiveTheme().getId();
    try {
      ThemeSwitch.engine().setTheme(other, false);
      UiDriver.flush();
      // as the dark theme's colour registry left them: its values under the plain keys, read at a start
      String darkValue = org.eclipse.jface.resource.StringConverter.asString(ColorIds.DARK.get(CONFLICT_STRONG));
      String mine = ColorIds.lightId(LineStyle.Kind.ADDED);
      workbench().put(CONFLICT_STRONG, darkValue);
      workbench().put(mine, "1,2,3");
      Palette.registry().put(CONFLICT_STRONG, ColorIds.DARK.get(CONFLICT_STRONG));
      ColorSchemes.dropDarkLeftovers();
      assertEquals(light, Palette.strong(LineStyle.Kind.CONFLICT).getRGB());
      String plain = workbench().get(CONFLICT_STRONG, null);
      assertTrue(plain == null || light.equals(org.eclipse.jface.resource.StringConverter.asRGB(plain)), plain);
      assertEquals("1,2,3", workbench().get(mine, null), "a colour of the user stays");
    } finally {
      workbench().remove(ColorIds.lightId(LineStyle.Kind.ADDED));
      ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
      ThemeSwitch.engine().setTheme(previous, false);
      UiDriver.flush();
    }
  }

  @Test
  void aSchemeInAThemeOfAnotherPlugInIsWrittenWhereColorsAndFontsReadsIt() {
    String other = otherTheme();
    String previous = ThemeSwitch.engine().getActiveTheme().getId();
    RGB hc = ColorSchemes.values(ColorSchemes.Scheme.HIGH_CONTRAST, false).get(CONFLICT_STRONG);
    try {
      ThemeSwitch.engine().setTheme(other, false);
      UiDriver.flush();
      ColorSchemes.apply(ColorSchemes.Scheme.HIGH_CONTRAST);
      assertEquals(org.eclipse.jface.resource.StringConverter.asString(hc), workbench().get(CONFLICT_STRONG, null),
          "the plain key: the definition is not overridden by CSS in that theme");
      assertNull(workbench().get(other + "." + CONFLICT_STRONG, null), "no key that nobody reads");
      assertEquals(java.util.Optional.of(ColorSchemes.Scheme.HIGH_CONTRAST), ColorSchemes.current());
      ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
      assertNull(workbench().get(CONFLICT_STRONG, null));
    } finally {
      ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
      ThemeSwitch.engine().setTheme(previous, false);
      UiDriver.flush();
    }
  }

  private static void check(List<String> low, String what, double ratio, double min) {
    if (ratio < min) {
      low.add(String.format(Locale.ROOT, "%s %.2f < %.1f", what, ratio, min));
    }
  }
}
