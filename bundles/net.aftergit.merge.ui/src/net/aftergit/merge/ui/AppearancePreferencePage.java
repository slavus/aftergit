package net.aftergit.merge.ui;

import java.text.MessageFormat;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Preferences › AfterGit › Appearance (preferences design §3). */
public final class AppearancePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  static final String ID = "net.aftergit.merge.preferences.appearance";
  private static final int WRAP = 460;

  private final Map<ColorSchemes.Scheme, Button> schemes = new EnumMap<>(ColorSchemes.Scheme.class);
  private final Map<Appearance.ConnectorWidth, Button> connectors = new EnumMap<>(Appearance.ConnectorWidth.class);
  private Label custom;
  private SchemePreview preview;
  private Button signs;
  private Button hatch;
  private Link font;
  /** The scheme the page showed when it opened; empty: Custom. */
  private Optional<ColorSchemes.Scheme> shown;

  @Override
  public void init(IWorkbench workbench) {
    // the values are read when the controls are made
  }

  @Override
  protected Control createContents(Composite parent) {
    ColorMigration.ensure();
    Composite c = new Composite(parent, SWT.NONE);
    GridLayoutFactory.fillDefaults().applyTo(c);
    shown = ColorSchemes.current();
    boolean dark = ColorSchemes.darkTheme();

    Group scheme = group(c, Messages.appearanceScheme);
    radio(scheme, ColorSchemes.Scheme.DEFAULT, Messages.schemeDefault, dark);
    radio(scheme, ColorSchemes.Scheme.COLOR_BLIND, Messages.schemeColorBlind, dark);
    radio(scheme, ColorSchemes.Scheme.HIGH_CONTRAST, Messages.schemeHighContrast, dark);
    custom = new Label(scheme, SWT.WRAP);
    custom.setText(Messages.schemeCustom);
    custom.setVisible(shown.isEmpty());
    GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).applyTo(custom);
    Label note = new Label(scheme, SWT.WRAP);
    note.setText(Messages.schemeNote);
    GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).applyTo(note);
    preview = new SchemePreview(scheme);
    GridDataFactory.fillDefaults().grab(true, false).applyTo(preview);
    preview.show(shown.orElse(null), shown.map(s -> ColorSchemes.values(s, dark)).orElseGet(ColorSchemes::registryValues));

    Group signGroup = group(c, Messages.appearanceSigns);
    signs = new Button(signGroup, SWT.CHECK);
    signs.setText(Messages.appearanceShowSigns);
    signs.setSelection(Appearance.signs());
    hatch = new Button(signGroup, SWT.CHECK);
    hatch.setText(Messages.appearanceHatch);
    hatch.setSelection(Appearance.hatch());
    Composite row = new Composite(signGroup, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(4).applyTo(row);
    new Label(row, SWT.NONE).setText(Messages.appearanceConnector);
    connector(row, Appearance.ConnectorWidth.NORMAL, Messages.connectorNormal);
    connector(row, Appearance.ConnectorWidth.NARROW, Messages.connectorNarrow);
    connector(row, Appearance.ConnectorWidth.WIDE, Messages.connectorWide);

    Group fontGroup = group(c, Messages.appearanceFont);
    font = new Link(fontGroup, SWT.WRAP);
    GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).applyTo(font);
    showFont();
    font.addListener(SWT.Selection, e -> PreferenceLinks.open(this, PreferenceLinks.COLORS_AND_FONTS,
        "selectFont:" + MergeFont.ID));

    Link colors = new Link(c, SWT.NONE);
    colors.setText(Messages.appearanceColorsLink);
    colors.addListener(SWT.Selection, e -> PreferenceLinks.open(this, PreferenceLinks.COLORS_AND_FONTS,
        "selectCategory:" + ColorIds.CATEGORY));
    return c;
  }

  /** Names the merge font: again when the page is shown, since Colors and Fonts may have changed it meanwhile. */
  private void showFont() {
    FontData fd = MergeFont.get().getFontData()[0];
    font.setText(MessageFormat.format(Messages.appearanceFontText, fd.getName(), fd.getHeight()));
  }

  @Override
  public void setVisible(boolean visible) {
    if (visible && font != null && !font.isDisposed()) {
      showFont();
    }
    super.setVisible(visible);
  }

  private static Group group(Composite parent, String text) {
    Group g = new Group(parent, SWT.NONE);
    g.setText(text);
    GridLayoutFactory.swtDefaults().applyTo(g);
    GridDataFactory.fillDefaults().grab(true, false).indent(0, 6).applyTo(g);
    return g;
  }

  private void radio(Composite parent, ColorSchemes.Scheme s, String label, boolean dark) {
    Button b = new Button(parent, SWT.RADIO);
    b.setText(label);
    b.setSelection(shown.isPresent() && shown.get() == s);
    b.addListener(SWT.Selection, e -> {
      if (b.getSelection()) {
        preview.show(s, ColorSchemes.values(s, dark));
      }
    });
    schemes.put(s, b);
  }

  private void connector(Composite parent, Appearance.ConnectorWidth w, String label) {
    Button b = new Button(parent, SWT.RADIO);
    b.setText(label);
    b.setSelection(Appearance.connector() == w);
    connectors.put(w, b);
  }

  Button schemeButton(ColorSchemes.Scheme s) { return schemes.get(s); }
  Label customLabel() { return custom; }
  SchemePreview preview() { return preview; }
  Button signsButton() { return signs; }
  Button hatchButton() { return hatch; }
  Button connectorButton(Appearance.ConnectorWidth w) { return connectors.get(w); }
  Link fontLink() { return font; }

  @Override
  protected void performDefaults() {
    schemes.forEach((s, b) -> b.setSelection(s == ColorSchemes.Scheme.DEFAULT));
    preview.show(ColorSchemes.Scheme.DEFAULT, ColorSchemes.values(ColorSchemes.Scheme.DEFAULT, ColorSchemes.darkTheme()));
    signs.setSelection(true);
    hatch.setSelection(true);
    connectors.forEach((w, b) -> b.setSelection(w == Appearance.ConnectorWidth.NORMAL));
    super.performDefaults();
  }

  @Override
  public boolean performOk() {
    schemes.forEach((s, b) -> {
      // only a choice the user made: a Custom page with no radio chosen keeps the hand-made colours
      if (b.getSelection() && !(shown.isPresent() && shown.get() == s)) {
        ColorSchemes.apply(s);
        shown = Optional.of(s);
        custom.setVisible(false);
      }
    });
    // the frames go with the scheme shown, also when its colours are already there (chosen in the other theme)
    schemes.forEach((s, b) -> {
      Appearance.Frames frames =
          s == ColorSchemes.Scheme.HIGH_CONTRAST ? Appearance.Frames.THICK : Appearance.Frames.NORMAL;
      if (b.getSelection() && Appearance.frames() != frames) {
        Appearance.setFrames(frames);
      }
    });
    if (signs.getSelection() != Appearance.signs()) {
      Appearance.setSigns(signs.getSelection());
    }
    if (hatch.getSelection() != Appearance.hatch()) {
      Appearance.setHatch(hatch.getSelection());
    }
    connectors.forEach((w, b) -> {
      if (b.getSelection() && w != Appearance.connector()) {
        Appearance.setConnector(w);
      }
    });
    return true;
  }
}
