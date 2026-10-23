package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preferences design §2: one font for the panes, the line numbers and the origin column, changed live. */
class MergeFontTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  private static FontRegistry fonts() {
    return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getFontRegistry();
  }

  private static boolean same(Font a, Font b) {
    return Arrays.equals(a.getFontData(), b.getFontData());
  }

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    fonts().put(MergeFont.ID, JFaceResources.getTextFont().getFontData());
    fonts().put(MergeFont.JDT_FONT, JFaceResources.getTextFont().getFontData());
    UiDriver.flush();
    repos.dispose();
  }

  private MergeEditorInput openCalc(String project) throws Exception {
    repos.conflict("src/demo/Calc.java", UiDriver.calc("base"), UiDriver.calc("ours"), UiDriver.calc("theirs"));
    IFile file = repos.javaProject(project, "src/demo/Calc.java");
    return UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static void assertMergeFont(MergeViewer v) {
    for (PaneKind k : PaneKind.values()) {
      MergePane p = v.pane(k);
      assertTrue(same(MergeFont.get(), p.text().getFont()), k + " text");
      assertTrue(same(MergeFont.get(), p.ruler().getControl().getFont()), k + " line numbers");
    }
  }

  @Test
  void untilChangedTheMergeFontIsTheTextFont() {
    assertTrue(same(JFaceResources.getTextFont(), MergeFont.get()));
  }

  @Test
  void everyPaneAndItsRulerUseTheMergeFont() throws Exception {
    assertMergeFont(openCalc("font-panes").viewer());
  }

  @Test
  void aChangedMergeFontReachesAnOpenResolver() throws Exception {
    MergeViewer v = openCalc("font-live").viewer();
    FontData d = JFaceResources.getTextFont().getFontData()[0];
    fonts().put(MergeFont.ID, new FontData[] {new FontData(d.getName(), d.getHeight() + 5, SWT.NORMAL)});
    ConflictRepos.waitUntil(() -> v.pane(PaneKind.RESULT).text().getFont().getFontData()[0].getHeight()
        == d.getHeight() + 5, 10, "the new font in the result");
    UiDriver.flush();
    assertMergeFont(v);
  }

  private static Slider slider(Control c) {
    if (c instanceof Slider s) {
      return s;
    }
    if (c instanceof Composite parent) {
      for (Control child : parent.getChildren()) {
        Slider s = slider(child);
        if (s != null) {
          return s;
        }
      }
    }
    return null;
  }

  @Test
  void aChangedMergeFontRebuildsTheScrollingAndRedrawsTheConnectors() throws Exception {
    MergeViewer v = openCalc("font-sync").viewer();
    UiDriver.flush();
    Slider slider = slider(v.getControl());
    assertNotNull(slider);
    assertEquals(v.pane(PaneKind.RESULT).text().getLineHeight(), slider.getIncrement());
    int before = v.decorationRedraws();
    FontData d = JFaceResources.getTextFont().getFontData()[0];
    fonts().put(MergeFont.ID, new FontData[] {new FontData(d.getName(), d.getHeight() + 9, SWT.NORMAL)});
    ConflictRepos.waitUntil(() -> v.pane(PaneKind.RESULT).text().getFont().getFontData()[0].getHeight()
        == d.getHeight() + 9, 10, "the new font in the result");
    UiDriver.flush();
    assertEquals(v.pane(PaneKind.RESULT).text().getLineHeight(), slider.getIncrement(), "the slider's step is a line");
    assertTrue(v.decorationRedraws() > before, "the connectors and stripes are redrawn");
  }

  @Test
  void theJavaEditorsOwnFontDoesNotTakeTheResultAway() throws Exception {
    MergeViewer v = openCalc("font-jdt").viewer();
    FontData d = JFaceResources.getTextFont().getFontData()[0];
    fonts().put(MergeFont.JDT_FONT, new FontData[] {new FontData(d.getName(), d.getHeight() + 7, SWT.NORMAL)});
    UiDriver.flush();
    UiDriver.flush();
    assertMergeFont(v);
  }
}
