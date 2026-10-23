package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Locale;
import org.eclipse.core.resources.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preferences design §7: the Calc example in each scheme, light and dark; each shot checks that its panes and line
 * numbers are as light or dark as its theme ({@link ThemeSwitch} lets GTK follow the theme).
 */
class DarkSchemesScreenshotTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
    repos.dispose();
    MergePreferences.reset();
  }

  private void shoot(String project, String name, boolean dark) throws Exception {
    IFile file = repos.javaProject(project, "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    UiDriver.flush();
    // a shot of the light theme is light and one of the dark theme dark, in every pane and its line numbers
    DarkThemeTest.lineNumbersFollowTheText(input, name + "-numbers", dark);
    assertTrue(Screens.painted(Screens.capture(input.viewer().getControl(), name)));
    ConflictRepos.page().closeAllEditors(false);
    repos.dispose(); // the next shot makes a project on the same folder
  }

  @Test
  void everySchemeLightAndDark() throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    int n = 0;
    for (ColorSchemes.Scheme s : ColorSchemes.Scheme.values()) {
      String scheme = s.name().toLowerCase(Locale.ROOT).replace('_', '-');
      ColorSchemes.apply(s);
      shoot("scheme-light-" + n, "scheme-" + scheme + "-light", false);
      try (ThemeSwitch dark = ThemeSwitch.dark()) {
        ColorSchemes.apply(s);
        shoot("scheme-dark-" + n, "scheme-" + scheme + "-dark", true);
        ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT); // ThemeSwitch.close waits for the light colours
      }
      n++;
    }
  }
}
