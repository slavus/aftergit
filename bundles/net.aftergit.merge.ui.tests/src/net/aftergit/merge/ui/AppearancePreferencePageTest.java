package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Preferences design §3: Preferences › AfterGit › Appearance. */
class AppearancePreferencePageTest {
  Shell shell;
  AppearancePreferencePage page;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    shell = new Shell(Display.getCurrent());
    shell.setSize(700, 700);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout()); // the page fills the shell, as in the dialog
  }

  @AfterEach
  void cleanup() {
    if (page != null) {
      page.dispose();
    }
    shell.dispose();
    ColorSchemes.apply(ColorSchemes.Scheme.DEFAULT);
    MergePreferences.reset();
  }

  private AppearancePreferencePage page() {
    page = new AppearancePreferencePage();
    page.init(PlatformUI.getWorkbench());
    page.createControl(shell);
    shell.layout(true, true);
    return page;
  }

  private static void choose(org.eclipse.swt.widgets.Button radio) {
    for (Control c : radio.getParent().getChildren()) {
      if (c instanceof org.eclipse.swt.widgets.Button b && (b.getStyle() & SWT.RADIO) != 0) {
        b.setSelection(b == radio);
      }
    }
    radio.notifyListeners(SWT.Selection, new Event());
  }

  @Test
  void thePageShowsTheCurrentSchemeOrCustom() {
    AppearancePreferencePage p = page();
    assertTrue(p.schemeButton(ColorSchemes.Scheme.DEFAULT).getSelection());
    assertFalse(p.customLabel().getVisible());
    p.dispose();
    Palette.registry().put(ColorIds.lightId(LineStyle.Kind.ADDED), new RGB(1, 2, 3));
    AppearancePreferencePage custom = page();
    for (ColorSchemes.Scheme s : ColorSchemes.Scheme.values()) {
      assertFalse(custom.schemeButton(s).getSelection(), s.name());
    }
    assertTrue(custom.customLabel().getVisible());
  }

  @Test
  void thePreviewPaintsTheChosenSchemeBeforeOk() throws Exception {
    AppearancePreferencePage p = page();
    choose(p.schemeButton(ColorSchemes.Scheme.COLOR_BLIND));
    assertEquals(ColorSchemes.Scheme.COLOR_BLIND, p.preview().scheme());
    assertEquals(Optional.of(ColorSchemes.Scheme.DEFAULT), ColorSchemes.current(), "nothing written before OK");
    shell.open(); // an unopened shell paints nothing into the screenshot
    UiDriver.flush();
    ImageData d = Screens.capture(p.preview(), "appearance-preview-colour-blind");
    RGB conflict = ColorSchemes.values(ColorSchemes.Scheme.COLOR_BLIND, false).get(ColorIds.lightId(LineStyle.Kind.CONFLICT));
    int y = SchemePreview.rowY(SchemePreview.CONFLICT_ROW) + 2;
    assertEquals(conflict, d.palette.getRGB(d.getPixel(SchemePreview.PADDING + 2, y)));
  }

  @Test
  void everyMnemonicOfThePageIsItsOwnWithRestoreDefaultsAndApply() {
    page();
    java.util.List<String> texts = new java.util.ArrayList<>();
    AfterGitPreferencePageTest.collect(shell, texts);
    java.util.Map<Character, String> seen = new java.util.HashMap<>();
    for (String t : texts) {
      Character m = AfterGitPreferencePageTest.mnemonic(t);
      if (m != null) {
        String other = seen.put(m, t);
        assertNull(other, "Alt+" + m + " is taken by \"" + other + "\" and \"" + t + "\"");
      }
    }
    for (char c : "FCHSVDANRW".toCharArray()) {
      assertTrue(seen.containsKey(c), "Alt+" + c + " in " + seen);
    }
  }

  @Test
  void thePreviewTellsAScreenReaderWhatItShows() {
    AppearancePreferencePage p = page();
    assertEquals(org.eclipse.osgi.util.NLS.bind(Messages.previewAccessibleName, "Default"),
        p.preview().accessibleName());
    choose(p.schemeButton(ColorSchemes.Scheme.HIGH_CONTRAST));
    assertEquals(org.eclipse.osgi.util.NLS.bind(Messages.previewAccessibleName, "High contrast"),
        p.preview().accessibleName());
    p.dispose();
    Palette.registry().put(ColorIds.lightId(LineStyle.Kind.ADDED), new RGB(1, 2, 3));
    AppearancePreferencePage custom = page();
    assertEquals(org.eclipse.osgi.util.NLS.bind(Messages.previewAccessibleName, Messages.previewCustom),
        custom.preview().accessibleName());
    assertFalse(Messages.previewAccessibleDescription.isBlank());
  }

  @Test
  void okWritesTheSchemeAndTheSigns() {
    AppearancePreferencePage p = page();
    choose(p.schemeButton(ColorSchemes.Scheme.HIGH_CONTRAST));
    p.signsButton().setSelection(false);
    p.hatchButton().setSelection(false);
    choose(p.connectorButton(Appearance.ConnectorWidth.WIDE));
    assertTrue(p.performOk());
    assertEquals(Optional.of(ColorSchemes.Scheme.HIGH_CONTRAST), ColorSchemes.current());
    assertFalse(Appearance.signs());
    assertFalse(Appearance.hatch());
    assertEquals(Appearance.ConnectorWidth.WIDE, Appearance.connector());
    p.performDefaults();
    assertTrue(p.performOk());
    assertEquals(Optional.of(ColorSchemes.Scheme.DEFAULT), ColorSchemes.current());
    assertTrue(Appearance.signs());
    assertEquals(Appearance.ConnectorWidth.NORMAL, Appearance.connector());
  }

  @Test
  void okGivesTheShownSchemeItsFrames() {
    Appearance.setFrames(Appearance.Frames.THICK); // High contrast chosen in the other theme
    AppearancePreferencePage p = page();
    assertTrue(p.schemeButton(ColorSchemes.Scheme.DEFAULT).getSelection());
    assertTrue(p.performOk());
    assertEquals(Appearance.Frames.NORMAL, Appearance.frames(), "Default draws normal frames");
  }

  @Test
  void theFontRowNamesTheMergeFont() {
    AppearancePreferencePage p = page();
    assertTrue(p.fontLink().getText().contains(MergeFont.get().getFontData()[0].getName()), p.fontLink().getText());
  }

  @Test
  void theLinkOpensColorsAndFontsOnTheAfterGitCategory() {
    PreferenceDialog d = PreferenceLinks.colorsAndFontsDialog(shell, "selectCategory:" + ColorIds.CATEGORY);
    try {
      Tree tree = find(((IPreferencePage) d.getSelectedPage()).getControl());
      assertNotNull(tree);
      assertEquals(1, tree.getSelectionCount());
      assertEquals("AfterGit", tree.getSelection()[0].getText());
      assertTrue(tree.getSelection()[0].getItemCount() >= 5, "four sub-categories and the font");
    } finally {
      d.close();
    }
  }

  private static Tree find(Control c) {
    if (c instanceof Tree t) {
      return t;
    }
    if (c instanceof Composite p) {
      for (Control child : p.getChildren()) {
        Tree t = find(child);
        if (t != null) {
          return t;
        }
      }
    }
    return null;
  }
}
