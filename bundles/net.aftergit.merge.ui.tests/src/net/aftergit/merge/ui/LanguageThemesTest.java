package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 1 and Testing: six languages of the target in the three panes, light and dark. */
class LanguageThemesTest {
  static final String TS = "import { x } from './x';\nconst total: number = 1;\nconst name = \"BASE\";\n"
      + "export function run(): void {\n  console.log(total, x); // done\n}\n";
  static final String JSON = "{\n  \"name\": \"BASE\",\n  \"count\": 3,\n  \"on\": true,\n  \"list\": [1, null]\n}\n";
  static final String YAML = "# settings\nname: BASE\ncount: 3\nitems:\n  - one\n  - \"two\"\non: true\n";
  static final String CSS = "/* page */\nbody {\n  color: #112233;\n  margin: 0 auto;\n}\n"
      + ".BASE > a:hover { width: 10px; }\n";
  static final String XML = "<?xml version=\"1.0\"?>\n<!-- doc -->\n<root attr=\"BASE\">\n"
      + "  <child id=\"1\">text</child>\n</root>\n";
  static final String MD = "# Title\n\nSome *BASE* text with `code` and a [link](http://x).\n\n- item\n- **bold**\n";

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    MergePreferences.reset();
  }

  @AfterEach
  void tearDown() throws Exception {
    MergePreferences.reset();
    repos.dispose();
  }

  private void shoot(String file, String scope, String base, boolean dark) throws Exception {
    String ext = file.substring(file.indexOf('.') + 1);
    MergeViewer v = LanguageColoursTest.open(repos, "themes-" + ext, file, base, base.replace("BASE", "ours"),
        base.replace("BASE", "theirs"));
    if (scope == null) {
      // the Markdown grammar of the target does not load under this regex engine, so the panes stay plain: this
      // checks that the resolver opens without an error and shows the left text; below the conflict colour and
      // the pictures are checked as for the other languages
      assertEquals(base.replace("BASE", "ours"), v.pane(PaneKind.LEFT).viewer().getDocument().get(), file);
    } else {
      assertInstanceOf(LspPaneSetup.class, v.paneSetup(), file);
      assertEquals(scope, ((LspPaneSetup) v.paneSetup()).colours().scopeName());
      for (PaneKind k : PaneKind.values()) {
        TextMateOracle.waitUntilColoured(v.pane(k).text(), file, dark, file + " in " + k + (dark ? " dark" : " light"));
      }
    }
    ImageData shot = Screens.capture(v.getControl(), "languages-" + ext + (dark ? "-dark" : "-light"));
    assertTrue(Screens.painted(shot));
    // the conflict's colour still fills its lines under the TextMate colours
    StyledText left = v.pane(PaneKind.LEFT).text();
    int line = left.getLineAtOffset(left.getText().indexOf("ours"));
    int top = v.getControl().toControl(left.toDisplay(0, left.getLinePixel(line))).y;
    Point size = v.getControl().getSize();
    assertTrue(
        Screens.contains(shot, Palette.light(LineStyle.Kind.CONFLICT).getRGB(), 8, 0, size.x, top,
            top + left.getLineHeight(line) - 1),
        "the conflict colour is visible on the conflict line of " + file);
  }

  private void light(String file, String scope, String base) throws Exception {
    shoot(file, scope, base, false);
  }

  private void dark(String file, String scope, String base) throws Exception {
    if (scope != null) {
      assertNotEquals(TextMateOracle.expected(base, file, false), TextMateOracle.expected(base, file, true),
          file + ": the dark theme colours differently");
    }
    try (ThemeSwitch theme = ThemeSwitch.dark()) {
      shoot(file, scope, base, true);
    }
  }

  @Test
  void typeScriptLight() throws Exception {
    light("a.ts", "source.ts", TS);
  }

  @Test
  void typeScriptDark() throws Exception {
    dark("a.ts", "source.ts", TS);
  }

  @Test
  void jsonLight() throws Exception {
    light("a.json", "source.json", JSON);
  }

  @Test
  void jsonDark() throws Exception {
    dark("a.json", "source.json", JSON);
  }

  @Test
  void yamlLight() throws Exception {
    light("a.yaml", "source.yaml", YAML);
  }

  @Test
  void yamlDark() throws Exception {
    dark("a.yaml", "source.yaml", YAML);
  }

  @Test
  void cssLight() throws Exception {
    light("a.css", "source.css", CSS);
  }

  @Test
  void cssDark() throws Exception {
    dark("a.css", "source.css", CSS);
  }

  @Test
  void xmlLight() throws Exception {
    light("a.xml", "text.xml", XML);
  }

  @Test
  void xmlDark() throws Exception {
    dark("a.xml", "text.xml", XML);
  }

  @Test
  void markdownLight() throws Exception {
    light("a.md", null, MD);
  }

  @Test
  void markdownDark() throws Exception {
    dark("a.md", null, MD);
  }
}
