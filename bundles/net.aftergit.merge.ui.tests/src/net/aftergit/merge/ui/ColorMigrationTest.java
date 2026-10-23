package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Colours changed in Colors and Fonts before the AfterGit colours became CSS colour definitions were stored under
 * the plain key; the old css/dark.css wrote its values there too. The migration moves the first and drops the second.
 */
class ColorMigrationTest {
  static final String CONFLICT_LIGHT = ColorIds.lightId(LineStyle.Kind.CONFLICT);
  static final String CONFLICT_STRONG = ColorIds.strongId(LineStyle.Kind.CONFLICT);
  static final String ADDED_LIGHT = ColorIds.lightId(LineStyle.Kind.ADDED);

  private static IEclipsePreferences workbench() {
    return InstanceScope.INSTANCE.getNode(ColorSchemes.WORKBENCH_NODE);
  }

  private static String perTheme(String id) {
    return ColorSchemes.preferenceKey(ThemeSwitch.engine().getActiveTheme().getId(),
        PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getId(), id);
  }

  private static void forget() {
    for (String id : new String[] {CONFLICT_LIGHT, CONFLICT_STRONG, ADDED_LIGHT}) {
      workbench().remove(id);
      workbench().remove(perTheme(id));
    }
  }

  @BeforeEach
  void setUp() {
    forget();
    InstanceScope.INSTANCE.getNode(MergePreferences.NODE).remove(ColorMigration.MIGRATED);
  }

  @AfterEach
  void cleanup() {
    ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
    forget();
    MergePreferences.reset();
    InstanceScope.INSTANCE.getNode(MergePreferences.NODE).putBoolean(ColorMigration.MIGRATED, true);
  }

  @Test
  void aColourCustomisedBeforeStaysInTheThemeOfNow() {
    workbench().put(CONFLICT_LIGHT, "255,0,255");
    assertTrue(ColorMigration.run());
    assertEquals("255,0,255", workbench().get(perTheme(CONFLICT_LIGHT), null), "the key Colors and Fonts reads now");
    assertEquals(new RGB(255, 0, 255), Palette.light(LineStyle.Kind.CONFLICT).getRGB(), "shown at once");
  }

  @Test
  void theValuesTheOldDarkCssWroteAreRemoved() {
    workbench().put(CONFLICT_STRONG, "140,40,24");
    workbench().put(ADDED_LIGHT, "42,68,42");
    assertTrue(ColorMigration.run());
    assertNull(workbench().get(CONFLICT_STRONG, null));
    assertNull(workbench().get(ADDED_LIGHT, null));
    assertNull(workbench().get(perTheme(CONFLICT_STRONG), null), "not a colour of the user");
    assertEquals(ColorIds.DEFAULTS.get(CONFLICT_STRONG), Palette.strong(LineStyle.Kind.CONFLICT).getRGB());
  }

  @Test
  void theMigrationRunsOnce() {
    workbench().put(CONFLICT_LIGHT, "255,0,255");
    assertTrue(ColorMigration.run());
    workbench().put(CONFLICT_LIGHT, "1,2,3");
    assertFalse(ColorMigration.run(), "already migrated");
    assertEquals("255,0,255", workbench().get(perTheme(CONFLICT_LIGHT), null));
  }

  @Test
  void resettingTheSettingsKeepsTheMarker() {
    InstanceScope.INSTANCE.getNode(MergePreferences.NODE).putBoolean(ColorMigration.MIGRATED, true);
    MergePreferences.reset();
    assertTrue(InstanceScope.INSTANCE.getNode(MergePreferences.NODE).getBoolean(ColorMigration.MIGRATED, false),
        "the colours are not migrated again");
  }

  @Test
  void withoutACssThemeTheMigrationIsTriedAgainNextTime() {
    workbench().put(CONFLICT_LIGHT, "255,0,255");
    ColorMigration.forgetForTests();
    ColorMigration.cssThemeForTests(() -> null);
    try {
      ColorMigration.ensure();
      assertEquals("255,0,255", workbench().get(CONFLICT_LIGHT, null), "nothing done before the theme engine runs");
    } finally {
      ColorMigration.cssThemeForTests(null);
    }
    ColorMigration.ensure();
    assertEquals("255,0,255", workbench().get(perTheme(CONFLICT_LIGHT), null), "migrated once the theme is there");
  }

  @Test
  void inAThemeOfAnotherPlugInAColourStaysUnderThePlainKey() {
    workbench().put(CONFLICT_LIGHT, "255,0,255");
    ColorMigration.cssThemeForTests(() -> "net.aftergit.test.theme.unknown");
    try {
      assertTrue(ColorMigration.run());
    } finally {
      ColorMigration.cssThemeForTests(null);
    }
    assertEquals("255,0,255", workbench().get(CONFLICT_LIGHT, null), "where Colors and Fonts reads it there");
  }

  @Test
  void otherPlugInsKeysStay() {
    workbench().put("org.example.color.something", "1,2,3");
    try {
      assertTrue(ColorMigration.run());
      assertEquals("1,2,3", workbench().get("org.example.color.something", null));
    } finally {
      workbench().remove("org.example.color.something");
    }
  }
}
