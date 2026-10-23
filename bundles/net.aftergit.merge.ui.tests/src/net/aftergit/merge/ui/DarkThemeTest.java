package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.model.BlockState;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.FrameworkUtil;

/** Spec §6.4 dark colors and §13 contrast on the Eclipse dark theme, measured on the opened result. */
class DarkThemeTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  ThemeSwitch theme;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    theme = ThemeSwitch.dark();
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      repos.dispose();
    } finally {
      if (theme != null) {
        theme.close();
      }
    }
  }

  private static Map<String, RGB> darkCss() throws Exception {
    try (InputStream in = FrameworkUtil.getBundle(Palette.class).getEntry("css/dark.css").openStream()) {
      String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Map<String, RGB> out = new HashMap<>();
      Matcher m = Pattern.compile(
          "ColorDefinition#(net-aftergit-merge-color-[a-z-]+) \\{\\s*color: rgb\\((\\d+), (\\d+), (\\d+)\\);").matcher(css);
      while (m.find()) {
        out.put(m.group(1).replace('-', '.'), new RGB(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4))));
      }
      return out;
    }
  }

  static double luminance(RGB c) {
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue);
  }

  private static double channel(int v) {
    double s = v / 255.0;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }

  /** WCAG 2 contrast ratio, 1 to 21. */
  static double contrast(RGB a, RGB b) {
    double x = luminance(a);
    double y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }

  private static void need(List<String> low, String what, double ratio, double min) {
    if (ratio < min) {
      low.add(String.format(Locale.ROOT, "%s %.2f < %.1f", what, ratio, min));
    }
  }

  private MergeEditorInput openCalc(String project) throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject(project, "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    UiDriver.activate(input.viewer().getControl());
    return input;
  }

  /** The line numbers of every pane sit on the editor's background, which is dark or light as the theme is. */
  static void lineNumbersFollowTheText(MergeEditorInput input, String name, boolean dark) throws Exception {
    UiDriver.flush();
    for (PaneKind kind : PaneKind.values()) {
      MergePane pane = input.viewer().pane(kind);
      org.eclipse.jface.text.source.LineNumberRulerColumn numbers = null;
      for (java.util.Iterator<?> it = pane.ruler().getDecoratorIterator(); it.hasNext();) {
        if (it.next() instanceof org.eclipse.jface.text.source.LineNumberRulerColumn c) {
          numbers = c;
        }
      }
      assertNotNull(numbers, kind + ": line numbers");
      RGB bg = pane.text().getBackground().getRGB();
      assertTrue(dark ? luminance(bg) < 0.2 : luminance(bg) > 0.8,
          kind + ": the " + (dark ? "dark" : "light") + " editor background, got " + bg);
      org.eclipse.swt.graphics.ImageData shot = Screens.capture(numbers.getControl(), name + "-" + kind.name()
          .toLowerCase(Locale.ROOT));
      assertEquals(bg, Screens.pixel(shot, 1, 1), kind + ": the line numbers' background");
      boolean digits = false;
      for (int x = 0; x < shot.width && !digits; x++) {
        for (int y = 0; y < Math.min(shot.height, 40) && !digits; y++) {
          digits = contrast(Screens.pixel(shot, x, y), bg) >= 3.0;
        }
      }
      assertTrue(digits, kind + ": the numbers stand out on " + bg);
    }
  }

  @Test
  void lineNumbersTakeTheDarkEditorColoursInJavaPanes() throws Exception {
    lineNumbersFollowTheText(openCalc("calc-numbers"), "dark-line-numbers-java", true);
  }

  @Test
  void lineNumbersTakeTheDarkEditorColoursInPlainPanes() throws Exception {
    repos.conflict("notes.txt", "a\nb\nc\n", "a\nours\nc\n", "a\ntheirs\nc\n");
    IFile file = repos.plainProject("plain-numbers", "notes.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    lineNumbersFollowTheText(input, "dark-line-numbers-plain", true);
  }

  @Test
  void ourColorsAreTheDarkCss() throws Exception {
    Map<String, RGB> css = darkCss();
    assertEquals(ColorIds.all(), css.keySet());
    for (Map.Entry<String, RGB> e : css.entrySet()) {
      assertEquals(e.getValue(), Palette.registry().getRGB(e.getKey()), e.getKey());
    }
  }

  @Test
  void blockColorsStayReadableOnTheDarkEditor() throws Exception {
    MergeEditorInput input = openCalc("calc-dark");
    StyledText t = input.viewer().pane(PaneKind.RESULT).text();
    RGB bg = t.getBackground().getRGB();
    RGB fg = t.getForeground().getRGB();
    assertTrue(luminance(bg) < 0.2, "the result has the dark editor background, got " + bg);
    List<String> low = new ArrayList<>();
    for (LineStyle.Kind k : LineStyle.Kind.values()) {
      if (k != LineStyle.Kind.NONE) {
        need(low, "text on " + k + " light", contrast(fg, Palette.light(k).getRGB()), 4.5);
      }
    }
    for (LineStyle.Kind k : List.of(LineStyle.Kind.MODIFIED, LineStyle.Kind.ADDED, LineStyle.Kind.REMOVED)) {
      need(low, "text on " + k + " strong (word highlights)", contrast(fg, Palette.strong(k).getRGB()), 3.0);
    }
    need(low, "text on CONFLICT strong", contrast(fg, Palette.strong(LineStyle.Kind.CONFLICT).getRGB()), 3.0);
    for (LineStyle.Kind k : List.of(LineStyle.Kind.AUTO, LineStyle.Kind.AI)) {
      need(low, k + " border on the background", contrast(Palette.strong(k).getRGB(), bg), 3.0);
    }
    need(low, "edited bar on the background", contrast(Palette.editedStrong().getRGB(), bg), 3.0);
    need(low, "taken bar on the background", contrast(Palette.takenStrong().getRGB(), bg), 3.0);
    need(low, "current block frame on the background", contrast(Palette.current().getRGB(), bg), 3.0);
    need(low, "current block frame on conflict light",
        contrast(Palette.current().getRGB(), Palette.light(LineStyle.Kind.CONFLICT).getRGB()), 3.0);
    need(low, "current block frame on auto light",
        contrast(Palette.current().getRGB(), Palette.light(LineStyle.Kind.AUTO).getRGB()), 3.0);
    // block-states Ruling 5: the text of a side not taken is meant to fade, but must stay legible
    need(low, "dropped text on dropped light", contrast(Palette.strong(LineStyle.Kind.DROPPED).getRGB(),
        Palette.light(LineStyle.Kind.DROPPED).getRGB()), 2.4);
    var shot = Screens.capture(input.viewer().getControl(), "calc-all-dark");
    assertTrue(low.isEmpty(), "too little contrast on the dark theme (fg " + fg + ", bg " + bg + "): " + low);
    assertTrue(Screens.painted(shot));
  }

  @Test
  void darkConflictBandIsFilledWithTheDarkConflictLight() throws Exception {
    MergeEditorInput input = openCalc("calc-dark-band");
    MergeViewer v = input.viewer();
    int open = -1;
    for (var b : input.session().model().blocks()) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        open = b.index();
        break;
      }
    }
    assertTrue(open >= 0, "Calc keeps one open conflict");
    v.selectBlock(open);
    ConnectorCanvas c = v.connector(PaneKind.LEFT);
    c.redraw();
    c.update();
    UiDriver.flush();
    int block = open;
    ConnectorGeometry.Band band = c.bands().stream().filter(b -> b.block() == block).findFirst().orElseThrow();
    int y = ((band.lt() + band.rt()) / 2 + (band.lb() + band.rb()) / 2) / 2;
    assertEquals(Palette.light(LineStyle.Kind.CONFLICT).getRGB(),
        Screens.pixel(Screens.capture(c, "calc-left-connector-dark"), c.getClientArea().width / 2, y));
  }

  @Test
  void anAllResolvedMergeShowsTheGreenBarOnTheDarkTheme() throws Exception {
    String base = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n";
    repos.conflict("src/demo/Plain.txt", base, base.replace("two", "2"), base.replace("nine", "9"));
    IFile file = repos.javaProject("plain-dark", "src/demo/Plain.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertEquals(0, input.session().model().unresolvedConflicts(), "both changes merge without a conflict");
    UiDriver.flush();
    assertTrue(input.viewer().allResolvedBarShown(), "the bar is green, text area included");
  }

  @Test
  void everyBlockStateDark() throws Exception {
    ScreenshotTest.checkEveryBlockState(UiDriver.openEveryBlockState(repos, "states-dark"), "block-states-dark");
  }
}
