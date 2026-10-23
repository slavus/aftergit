package net.aftergit.merge.ui;

import java.util.Map;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * The small three-pane sample of the Appearance page (preferences design §3): a taken, an automatic, a conflicting
 * and a current block in the colours of a scheme, before they are written.
 */
final class SchemePreview extends Canvas {
  static final int PADDING = 4;
  static final int ROW = 18;
  static final int GAP = 18;
  static final int TAKEN_ROW = 0;
  static final int AUTO_ROW = 1;
  static final int CONFLICT_ROW = 2;
  static final int CURRENT_ROW = 3;
  private static final String[][] TEXT = {{"int p = 3;", "◀ int p = 3;", "int p = 4;"},
      {"import a.B;", "A import a.B;", "import a.C;"}, {"x = a;", "! x = …", "x = b;"},
      {"int q = 4;", "int q = 4;", "int q = 4;"}};

  private ColorSchemes.Scheme scheme;
  private Map<String, RGB> colors;

  SchemePreview(Composite parent) {
    super(parent, SWT.DOUBLE_BUFFERED);
    addPaintListener(e -> paint(e.gc));
    // a picture only: a screen reader hears which scheme it shows and what is in it
    getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
      @Override
      public void getName(org.eclipse.swt.accessibility.AccessibleEvent e) {
        e.result = accessibleName();
      }

      @Override
      public void getDescription(org.eclipse.swt.accessibility.AccessibleEvent e) {
        e.result = Messages.previewAccessibleDescription;
      }
    });
  }

  /** "Preview of the colour scheme …", naming the scheme shown or Custom. */
  String accessibleName() {
    String label = switch (scheme) {
      case null -> Messages.previewCustom;
      case DEFAULT -> Messages.schemeDefault;
      case COLOR_BLIND -> Messages.schemeColorBlind;
      case HIGH_CONTRAST -> Messages.schemeHighContrast;
    };
    return org.eclipse.osgi.util.NLS.bind(Messages.previewAccessibleName,
        org.eclipse.jface.action.LegacyActionTools.removeMnemonics(label));
  }

  static int rowY(int row) {
    return PADDING + row * ROW;
  }

  /** {@code s} null: Custom, the colours of Colors and Fonts. */
  void show(ColorSchemes.Scheme s, Map<String, RGB> c) {
    scheme = s;
    colors = c;
    redraw();
    getAccessible().sendEvent(org.eclipse.swt.accessibility.ACC.EVENT_NAME_CHANGED, null);
  }

  ColorSchemes.Scheme scheme() {
    return scheme;
  }

  @Override
  public Point computeSize(int wHint, int hHint, boolean changed) {
    return new Point(wHint == SWT.DEFAULT ? 480 : wHint, 4 * ROW + 2 * PADDING);
  }

  private Color c(String id) {
    RGB rgb = colors.get(id);
    return rgb == null ? getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION) : new Color(rgb);
  }

  /** The text editors' colour {@code key}, or the system colour when they follow the system. */
  private Color editor(String key, int system) {
    IPreferenceStore st = EditorsUI.getPreferenceStore();
    return st.getBoolean(key + ".SystemDefault") ? getDisplay().getSystemColor(system)
        : new Color(PreferenceConverter.getColor(st, key));
  }

  private void paint(GC gc) {
    if (colors == null) {
      return;
    }
    // the samples sit on the editors' colours, as the panes do
    Color text = editor(AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND, SWT.COLOR_LIST_FOREGROUND);
    gc.setBackground(editor(AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND, SWT.COLOR_LIST_BACKGROUND));
    gc.fillRectangle(getClientArea());
    int w = (getClientArea().width - 2 * PADDING - 2 * GAP) / 3;
    String[][] fills = {
        {ColorIds.lightId(LineStyle.Kind.MODIFIED), ColorIds.lightId(LineStyle.Kind.MODIFIED),
            ColorIds.lightId(LineStyle.Kind.DROPPED)},
        {ColorIds.lightId(LineStyle.Kind.AUTO), ColorIds.lightId(LineStyle.Kind.AUTO),
            ColorIds.lightId(LineStyle.Kind.DROPPED)},
        {ColorIds.lightId(LineStyle.Kind.CONFLICT), ColorIds.lightId(LineStyle.Kind.CONFLICT),
            ColorIds.lightId(LineStyle.Kind.CONFLICT)},
        {ColorIds.lightId(LineStyle.Kind.MODIFIED), ColorIds.lightId(LineStyle.Kind.MODIFIED),
            ColorIds.lightId(LineStyle.Kind.MODIFIED)}};
    for (int row = 0; row < 4; row++) {
      for (int col = 0; col < 3; col++) {
        int x = PADDING + col * (w + GAP);
        int y = rowY(row);
        gc.setBackground(c(fills[row][col]));
        gc.fillRectangle(x, y, w, ROW);
        boolean dropped = col == 2 && row < 2;
        gc.setForeground(dropped ? c(ColorIds.DROPPED_TEXT) : text);
        gc.drawString(TEXT[row][col], x + 4, y + 1, true);
        if (dropped) {
          gc.drawLine(x + 4, y + ROW / 2, x + w - 4, y + ROW / 2);
        }
      }
      int rx = PADDING + w + GAP;
      if (row == TAKEN_ROW) {
        gc.setBackground(c(ColorIds.TAKEN_STRONG));
        gc.fillRectangle(rx, rowY(row), 4, ROW);
      } else if (row == AUTO_ROW) {
        gc.setForeground(c(ColorIds.AUTO_HATCH));
        for (int x = rx + w / 2; x < rx + w; x += 8) {
          gc.drawLine(x, rowY(row) + ROW, x + ROW, rowY(row));
        }
        gc.setBackground(c(ColorIds.strongId(LineStyle.Kind.AUTO)));
        gc.fillRectangle(rx, rowY(row), 4, ROW);
      }
    }
    gc.setForeground(c(ColorIds.CURRENT));
    // High contrast thickens the frames (Ruling 12); a Custom page keeps the frames it has
    gc.setLineWidth(scheme == null ? Appearance.currentFrame() : scheme == ColorSchemes.Scheme.HIGH_CONTRAST ? 3 : 2);
    for (int col = 0; col < 3; col++) {
      gc.drawRectangle(PADDING + col * (w + GAP) + 1, rowY(CURRENT_ROW) + 1, w - 2, ROW - 2);
    }
    gc.setLineWidth(1);
    gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
    Rectangle a = getClientArea();
    gc.drawRectangle(0, 0, a.width - 1, a.height - 1);
  }
}
