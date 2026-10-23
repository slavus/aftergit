package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

/** Preferences design §1, §2: the AfterGit part of Colors and Fonts. */
class ColorsAndFontsTest {
  private static List<IConfigurationElement> ours(String name) {
    List<IConfigurationElement> out = new ArrayList<>();
    for (IConfigurationElement e : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.themes")) {
      if (name.equals(e.getName()) && "net.aftergit.merge.ui".equals(e.getNamespaceIdentifier())) {
        out.add(e);
      }
    }
    return out;
  }

  private static String description(IConfigurationElement e) {
    IConfigurationElement[] d = e.getChildren("description");
    return d.length == 1 && d[0].getValue() != null ? d[0].getValue().trim() : "";
  }

  @Test
  void theAfterGitCategoryHasFourSubCategories() {
    Map<String, IConfigurationElement> byId = new HashMap<>();
    for (IConfigurationElement e : ours("themeElementCategory")) {
      byId.put(e.getAttribute("id"), e);
    }
    IConfigurationElement root = byId.get(ColorIds.CATEGORY);
    assertNotNull(root);
    assertEquals("AfterGit", root.getAttribute("label"));
    assertNull(root.getAttribute("parentId"));
    Map<String, String> labels = Map.of(ColorIds.CATEGORY_CHANGES, "Changes", ColorIds.CATEGORY_STATES,
        "Conflicts and states", ColorIds.CATEGORY_CURRENT, "Current block", ColorIds.CATEGORY_AI, "AI");
    assertEquals(5, byId.size());
    labels.forEach((id, label) -> {
      assertEquals(label, byId.get(id).getAttribute("label"), id);
      assertEquals(ColorIds.CATEGORY, byId.get(id).getAttribute("parentId"), id);
      assertFalse(description(byId.get(id)).isEmpty(), id);
    });
  }

  @Test
  void everyColourSitsInItsSubCategoryAndSaysWhereItShows() {
    Set<String> seen = new HashSet<>();
    for (IConfigurationElement e : ours("colorDefinition")) {
      String id = e.getAttribute("id");
      seen.add(id);
      assertEquals(ColorIds.categoryOf(id), e.getAttribute("categoryId"), id);
      assertTrue(description(e).length() > 20, id + ": a description says where the colour shows");
    }
    assertEquals(ColorIds.all(), seen);
    assertEquals("Background of an open conflict in all three panes and its connector.",
        description(ours("colorDefinition").stream()
            .filter(e -> ColorIds.lightId(LineStyle.Kind.CONFLICT).equals(e.getAttribute("id"))).findFirst()
            .orElseThrow()));
  }

  @Test
  void theMergePanesFontFollowsTheTextFontByDefault() {
    List<IConfigurationElement> fonts = ours("fontDefinition");
    assertEquals(1, fonts.size());
    IConfigurationElement f = fonts.get(0);
    assertEquals(MergeFont.ID, f.getAttribute("id"));
    assertEquals("AfterGit merge panes", f.getAttribute("label"));
    assertEquals(ColorIds.CATEGORY, f.getAttribute("categoryId"));
    assertEquals("org.eclipse.jface.textfont", f.getAttribute("defaultsTo"));
    assertFalse(description(f).isEmpty());
  }
}
