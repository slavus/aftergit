package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

class PaletteTest {
  private ColorRegistry registry() {
    return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry();
  }

  @AfterEach
  void restore() {
    registry().put(ColorIds.strongId(LineStyle.Kind.CONFLICT), new RGB(0xF2, 0x8B, 0x7A));
  }

  @Test
  void defaultsAreTheMediumLightValues() {
    assertEquals(new RGB(242, 139, 122), Palette.strong(LineStyle.Kind.CONFLICT).getRGB());
    assertEquals(new RGB(251, 205, 197), Palette.light(LineStyle.Kind.CONFLICT).getRGB());
    assertEquals(new RGB(255, 223, 153), Palette.strong(LineStyle.Kind.MODIFIED).getRGB());
    assertEquals(new RGB(255, 238, 196), Palette.light(LineStyle.Kind.MODIFIED).getRGB());
    assertEquals(new RGB(191, 232, 191), Palette.strong(LineStyle.Kind.ADDED).getRGB());
    assertEquals(new RGB(237, 248, 237), Palette.light(LineStyle.Kind.ADDED).getRGB());
    assertEquals(new RGB(31, 157, 139), Palette.strong(LineStyle.Kind.AUTO).getRGB(), "block-states §4: teal");
    assertEquals(new RGB(217, 242, 238), Palette.light(LineStyle.Kind.AUTO).getRGB());
    assertEquals(new RGB(195, 233, 226), Palette.autoHatch().getRGB());
    assertEquals(new RGB(154, 154, 154), Palette.strong(LineStyle.Kind.DROPPED).getRGB(), "dropped.text");
    assertEquals(new RGB(241, 241, 241), Palette.light(LineStyle.Kind.DROPPED).getRGB());
    assertEquals(new RGB(107, 107, 138), Palette.editedStrong().getRGB());
    assertEquals(new RGB(224, 168, 0), Palette.takenStrong().getRGB());
    assertEquals(org.eclipse.swt.widgets.Display.getCurrent().getSystemColor(org.eclipse.swt.SWT.COLOR_LIST_SELECTION)
        .getRGB(), Palette.current().getRGB(), "the current block: the platform's selection colour");
    assertEquals(new RGB(255, 153, 153), Palette.strong(LineStyle.Kind.REMOVED).getRGB(), "removed keeps its value");
    assertEquals(new RGB(131, 77, 240), Palette.strong(LineStyle.Kind.AI).getRGB(), "AI keeps its value");
    assertNull(Palette.strong(LineStyle.Kind.NONE));
  }

  @Test
  void pluginXmlHasTheSameLightDefaults() {
    int n = 0;
    String current = null;
    for (org.eclipse.core.runtime.IConfigurationElement e : org.eclipse.core.runtime.Platform.getExtensionRegistry()
        .getConfigurationElementsFor("org.eclipse.ui.themes")) {
      if (!"colorDefinition".equals(e.getName())) {
        continue;
      }
      if (ColorIds.CURRENT.equals(e.getAttribute("id"))) {
        current = e.getAttribute("value");
        continue;
      }
      if (!ColorIds.DEFAULTS.containsKey(e.getAttribute("id"))) {
        continue;
      }
      String[] v = e.getAttribute("value").split(",");
      RGB rgb = new RGB(Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim()));
      assertEquals(ColorIds.DEFAULTS.get(e.getAttribute("id")), rgb, e.getAttribute("id"));
      n++;
    }
    assertEquals(17, n);
    assertEquals("COLOR_LIST_SELECTION", current, "the current block follows the platform's selection colour");
  }

  @Test
  void allDefaultsMatchPalette() {
    int n = 0;
    for (LineStyle.Kind k : LineStyle.Kind.values()) {
      if (k == LineStyle.Kind.NONE) {
        continue;
      }
      assertEquals(ColorIds.DEFAULTS.get(ColorIds.strongId(k)), Palette.strong(k).getRGB(), k.name());
      assertEquals(ColorIds.DEFAULTS.get(ColorIds.lightId(k)), Palette.light(k).getRGB(), k.name());
      n += 2;
    }
    assertEquals(14, n);
    assertEquals(ColorIds.DEFAULTS.get(ColorIds.AUTO_HATCH), Palette.autoHatch().getRGB());
    assertEquals(ColorIds.DEFAULTS.get(ColorIds.EDITED_STRONG), Palette.editedStrong().getRGB());
    assertEquals(ColorIds.DEFAULTS.get(ColorIds.TAKEN_STRONG), Palette.takenStrong().getRGB());
    assertEquals(17, ColorIds.DEFAULTS.size());
    assertEquals(18, ColorIds.all().size(), "the defaults and the current block");
  }

  @Test
  void paletteFollowsRegistryChanges() {
    registry().put(ColorIds.strongId(LineStyle.Kind.CONFLICT), new RGB(1, 2, 3));
    assertEquals(new RGB(1, 2, 3), Palette.strong(LineStyle.Kind.CONFLICT).getRGB());
  }

  @Test
  void lightCssIsPluginXml() throws Exception {
    try (InputStream in = FrameworkUtil.getBundle(Palette.class).getEntry("css/light.css").openStream()) {
      String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      java.util.Map<String, RGB> light = new java.util.HashMap<>();
      java.util.regex.Matcher m = java.util.regex.Pattern
          .compile("ColorDefinition#(net-aftergit-merge-color-[a-z-]+) \\{\\s*color: rgb\\((\\d+), (\\d+), (\\d+)\\);")
          .matcher(css);
      while (m.find()) {
        light.put(m.group(1).replace('-', '.'),
            new RGB(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
      }
      assertEquals(ColorIds.DEFAULTS, light, "css/light.css is plugin.xml");
      assertTrue(css.contains("ColorDefinition#net-aftergit-merge-color-current {\n  color: COLOR-LIST-SELECTION;"),
          "the current block follows the platform's selection colour");
    }
  }

  @Test
  void darkCssOverridesEveryColor() throws Exception {
    try (InputStream in = FrameworkUtil.getBundle(Palette.class).getEntry("css/dark.css").openStream()) {
      String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String id : ColorIds.all()) {
        assertTrue(css.contains("ColorDefinition#" + id.replace('.', '-') + " {"), id);
      }
      java.util.Map<String, String> dark = new java.util.HashMap<>();
      java.util.regex.Matcher m = java.util.regex.Pattern
          .compile("ColorDefinition#(net-aftergit-merge-color-[a-z-]+) \\{\\s*color: rgb\\((\\d+), (\\d+), (\\d+)\\);")
          .matcher(css);
      while (m.find()) {
        dark.put(m.group(1).replace('-', '.'), m.group(2) + "," + m.group(3) + "," + m.group(4));
      }
      java.util.Map<String, String> expected = new java.util.HashMap<>();
      String[][] table = {{"modified", "122,81,0", "64,56,34"}, {"added", "30,96,30", "42,68,42"},
          {"removed", "128,0,0", "77,58,58"}, {"conflict", "140,40,24", "92,52,46"},
          {"auto", "60,194,173", "33,66,62"}, {"ai", "160,124,245", "48,38,74"}};
      for (String[] r : table) {
        expected.put("net.aftergit.merge.color." + r[0] + ".strong", r[1]);
        expected.put("net.aftergit.merge.color." + r[0] + ".light", r[2]);
      }
      expected.put("net.aftergit.merge.color.auto.hatch", "42,82,76");
      expected.put("net.aftergit.merge.color.dropped.light", "51,51,51");
      expected.put("net.aftergit.merge.color.dropped.text", "119,119,119");
      expected.put("net.aftergit.merge.color.edited.strong", "150,150,190");
      expected.put("net.aftergit.merge.color.taken.strong", "201,150,10");
      expected.put("net.aftergit.merge.color.current", "88,150,240");
      assertEquals(ColorIds.all(), expected.keySet());
      assertEquals(expected, dark);
      java.util.Map<String, String> fromIds = new java.util.HashMap<>();
      ColorIds.DARK.forEach((id, rgb) -> fromIds.put(id, rgb.red + "," + rgb.green + "," + rgb.blue));
      assertEquals(dark, fromIds, "ColorIds.DARK is css/dark.css");
    }
  }
}
