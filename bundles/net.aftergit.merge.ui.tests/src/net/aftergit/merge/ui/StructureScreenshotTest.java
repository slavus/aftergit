package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.PageBookView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Structure-compare design §2, §3, §4: Calc in the real editor with JDT, the key, the dark theme, screenshots. */
class StructureScreenshotTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().setBounds(0, 0, 1600, 1100);
  }

  @AfterEach
  void cleanup() throws Exception {
    IViewPart outline = ConflictRepos.page().findView(IPageLayout.ID_OUTLINE);
    if (outline != null) {
      ConflictRepos.page().hideView(outline);
    }
    repos.dispose();
    MergePreferences.reset();
  }

  private MergeEditorInput openCalc(String project) throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject(project, "src/demo/Calc.java");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    IWorkbenchPage page = ConflictRepos.page();
    page.setPartState(page.getActivePartReference(), IWorkbenchPage.STATE_MAXIMIZED);
    UiDriver.activate(input.viewer().getControl());
    return input;
  }

  private static void showByKey(MergeViewer v) throws Exception {
    UiDriver.command(SiteServices.TOGGLE_STRUCTURE);
    ConflictRepos.waitUntil(() -> v.structureShown() && v.structure().computations() > 0, 10, "the structure");
    UiDriver.flush();
  }

  /** The rows without their chips: the states depend on auto-resolution at open, who and names do not. */
  private static List<String> namesAndWho(MergeViewer v) {
    return v.structurePanel().tree().rows().stream().map(r -> r.substring(0, r.lastIndexOf(" | "))).toList();
  }

  @Test
  void theKeyShowsTheCalcStructureWithJdtIcons() throws Exception {
    MergeEditorInput input = openCalc("structure-light");
    MergeViewer v = input.viewer();
    assertFalse(v.structureShown(), "hidden by default");
    showByKey(v);
    assertEquals(List.of(
        "imports | ours",
        "Calc | theirs",
        "  precision | both",
        "  multiply(int, int) (removed) | removed by ours",
        "  divide(int, int) | both",
        "  sum(List) | ours",
        "  describe() | theirs"), namesAndWho(v));
    assertNotNull(v.structure().images(), "JDT icons");
    Tree t = v.structurePanel().tree().tree();
    ImageData shot = Screens.capture(t, "structure-tree-light");
    Screens.capture(v.getControl(), "structure-calc-light");
    if (v.structurePanel().tree().rows().stream().anyMatch(r -> r.endsWith("conflict"))) {
      assertTrue(Screens.contains(shot, Palette.strong(LineStyle.Kind.CONFLICT).getRGB(), 0, 0, shot.width, 0,
          shot.height), "an open conflict's chip is filled with the conflict colour");
    }
    UiDriver.command(SiteServices.TOGGLE_STRUCTURE);
    UiDriver.flush();
    assertFalse(v.structureShown(), "the key hides it again");
  }

  @Test
  void theStructureIsReadableOnTheDarkTheme() throws Exception {
    ThemeSwitch theme = ThemeSwitch.dark();
    try {
      MergeEditorInput input = openCalc("structure-dark");
      MergeViewer v = input.viewer();
      showByKey(v);
      Tree t = v.structurePanel().tree().tree();
      Screens.capture(v.getControl(), "structure-calc-dark");
      RGB bg = t.getBackground().getRGB();
      assertTrue(DarkThemeTest.contrast(t.getForeground().getRGB(), bg) >= 4.5, "the names");
      for (StructureLabels.ChipStyle s : List.of(StructureLabels.ChipStyle.CONFLICT,
          StructureLabels.ChipStyle.AUTO_FILLED, StructureLabels.ChipStyle.AI_FILLED)) {
        StructureTree.ChipColors c = StructureTree.colors(s, t);
        assertTrue(DarkThemeTest.contrast(c.foreground().getRGB(), c.background().getRGB()) >= 3.0, s.name());
      }
      for (StructureLabels.ChipStyle s : List.of(StructureLabels.ChipStyle.GREY, StructureLabels.ChipStyle.TAKEN,
          StructureLabels.ChipStyle.EDITED, StructureLabels.ChipStyle.AUTO_OUTLINED)) {
        assertTrue(DarkThemeTest.contrast(StructureTree.colors(s, t).foreground().getRGB(), bg) >= 2.4, s.name());
      }
      assertEquals(ThemeSwitch.DARK_CONFLICT, StructureTree.colors(StructureLabels.ChipStyle.CONFLICT, t)
          .background().getRGB(), "the theme's own conflict colour");
    } finally {
      theme.close();
    }
  }

  @Test
  void theOutlineShowsTheCalcStructure() throws Exception {
    openCalc("structure-outline");
    IViewPart view = ConflictRepos.page().showView(IPageLayout.ID_OUTLINE);
    StructureOutlinePage page = (StructureOutlinePage) ((PageBookView) view).getCurrentPage();
    ConflictRepos.waitUntil(() -> page.tree() != null && !page.tree().rows().isEmpty(), 10, "the outline tree");
    assertTrue(page.tree().rows().stream().anyMatch(r -> r.startsWith("  describe() |  | ")),
        "compact, no who: " + page.tree().rows());
    Screens.capture(page.getControl(), "structure-outline");
  }

  @Test
  void aPropertiesFileShowsNeutralIcons() throws Exception {
    repos.conflict("messages.properties", StructureControllerTest.BASE, StructureControllerTest.OURS,
        StructureControllerTest.THEIRS);
    IFile file = repos.plainProject("structure-props", "messages.properties");
    MergeEditorInput input = UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    UiDriver.activate(input.viewer().getControl());
    showByKey(input.viewer());
    assertNull(input.viewer().structure().images(), "no JDT icons for .properties");
    Screens.capture(input.viewer().getControl(), "structure-properties");
  }
}
