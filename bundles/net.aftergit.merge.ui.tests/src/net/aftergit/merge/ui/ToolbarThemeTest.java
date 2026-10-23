package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ui-polish design §1: the toolbar's icon set follows its background, with contrast, on the light and dark theme. */
class ToolbarThemeTest {
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

  /** The most frequent opaque color of an icon: its glyph color. */
  private static RGB glyph(ImageDescriptor d) {
    ImageData data = d.getImageData(100);
    Map<RGB, Integer> counts = new HashMap<>();
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        if (data.getAlpha(x, y) > 200) {
          counts.merge(data.palette.getRGB(data.getPixel(x, y)), 1, Integer::sum);
        }
      }
    }
    return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
  }

  /** What the eye sees of a translucent icon on {@code bg}: its most frequent most-opaque color blended onto it. */
  private static RGB seenOn(ImageDescriptor d, RGB bg) {
    ImageData data = d.getImageData(100);
    int max = 0;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        max = Math.max(max, data.getAlpha(x, y));
      }
    }
    Map<RGB, Integer> counts = new HashMap<>();
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        if (max > 0 && data.getAlpha(x, y) == max) {
          counts.merge(data.palette.getRGB(data.getPixel(x, y)), 1, Integer::sum);
        }
      }
    }
    RGB c = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    double a = max / 255.0;
    return new RGB((int) Math.round(bg.red + (c.red - bg.red) * a), (int) Math.round(bg.green + (c.green - bg.green) * a),
        (int) Math.round(bg.blue + (c.blue - bg.blue) * a));
  }

  private static boolean followsBackground(MergeToolbar t) {
    boolean dark = Icons.darkBackground(t.manager().getControl().getBackground().getRGB());
    return Icons.descriptor(Icons.NEXT_CHANGE, dark).equals(t.action(Messages.nextChange).getImageDescriptor());
  }

  private static void check(MergeToolbar t, String theme) {
    t.manager().getControl().redraw();
    UiDriver.flush();
    ConflictRepos.waitUntil(() -> followsBackground(t), 10, theme + ": the icon set of the toolbar background");
    RGB bg = t.manager().getControl().getBackground().getRGB();
    RGB glyph = glyph(Icons.descriptor(Icons.NEXT_CHANGE, Icons.darkBackground(bg)));
    double ratio = DarkThemeTest.contrast(glyph, bg);
    assertTrue(ratio >= 3.0, theme + ": icon " + glyph + " on toolbar " + bg + " has contrast " + ratio);
    // a disabled icon looks disabled on either background: a dimmed glyph, clearly less contrast than an enabled one
    boolean dark = Icons.darkBackground(bg);
    assertEquals(Icons.disabled(Icons.NEXT_CHANGE, dark), t.action(Messages.nextChange).getDisabledImageDescriptor(),
        theme + ": the dimmed icon of the current set");
    RGB dimmed = seenOn(Icons.disabled(Icons.NEXT_CHANGE, dark), bg);
    double dimmedRatio = DarkThemeTest.contrast(dimmed, bg);
    assertTrue(dimmedRatio <= ratio / 2 && dimmedRatio > 1.2,
        theme + ": disabled icon " + dimmed + " on " + bg + " has contrast " + dimmedRatio + " against " + ratio);
    assertEquals(Display.getCurrent().getSystemColor(SWT.COLOR_LIST_SELECTION), t.primaryButton().getBackground(),
        theme + ": the primary button keeps the selection colors");
  }

  @Test
  void theIconSetFollowsTheToolbarBackgroundInBothThemes() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("toolbar-theme", "a.txt");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeToolbar t = input.viewer().toolbar();
    check(t, "light");
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      check(t, "dark");
    }
    check(t, "light again");
  }
}
