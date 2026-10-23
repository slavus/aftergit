package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** ui-polish design §1: our own icons, 16 px and @2x, a light and a dark set, nothing else in the icon folders. */
class IconsTest {
  private static Bundle bundle() {
    return FrameworkUtil.getBundle(Icons.class);
  }

  @Test
  void everyIconHasALightAndADarkSetAt16And32Pixels() {
    for (String name : Icons.ALL) {
      for (boolean dark : new boolean[] {false, true}) {
        String dir = dark ? "icons/dark/" : "icons/";
        assertNotNull(bundle().getEntry(dir + name + ".png"), dir + name + ".png");
        assertNotNull(bundle().getEntry(dir + name + "@2x.png"), dir + name + "@2x.png");
        ImageDescriptor d = Icons.descriptor(name, dark);
        assertEquals(16, d.getImageData(100).width, name + " at 100%");
        assertEquals(32, d.getImageData(200).width, name + " at 200% comes from @2x");
      }
    }
  }

  @Test
  void theIconFoldersHoldOnlyWhatTheGeneratorDraws() {
    Set<String> expected = new HashSet<>();
    for (String name : Icons.ALL) {
      for (String dir : new String[] {"/icons/", "/icons/dark/"}) {
        expected.add(dir + name + ".png");
        expected.add(dir + name + "@2x.png");
      }
    }
    Set<String> found = new HashSet<>();
    Enumeration<URL> e = bundle().findEntries("icons", "*.*", true);
    while (e != null && e.hasMoreElements()) {
      found.add(e.nextElement().getPath());
    }
    assertEquals(expected, found);
  }

  @Test
  void everyLayoutHasAnIconAndUnknownNamesFail() {
    for (PaneLayout pl : PaneLayout.values()) {
      assertTrue(Icons.ALL.contains(Icons.layout(pl)), pl.name());
    }
    assertThrows(IllegalStateException.class, () -> Icons.descriptor("no_such_icon", false));
  }

  @Test
  void theDarkSetIsForDarkBackgrounds() {
    assertTrue(Icons.darkBackground(new RGB(0x2F, 0x2F, 0x2F)));
    assertTrue(Icons.darkBackground(new RGB(0x48, 0x48, 0x4C)));
    assertFalse(Icons.darkBackground(new RGB(0xF0, 0xF0, 0xF0)));
    assertFalse(Icons.darkBackground(new RGB(0xFF, 0xFF, 0xFF)));
  }

  @Test
  void theAgentsIconIsPurpleInBothSets() {
    for (boolean dark : new boolean[] {false, true}) {
      org.eclipse.swt.graphics.ImageData d = Icons.descriptor(Icons.AGENTS, dark).getImageData(100);
      RGB center = d.palette.getRGB(d.getPixel(7, 8));
      assertTrue(center.blue > center.red && center.red > center.green, (dark ? "dark " : "light ") + center);
      assertTrue(d.getAlpha(0, 15) == 0, "the corner stays transparent");
    }
  }
}
