package net.aftergit.merge.ui;

import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;

/**
 * Block colors from the current Eclipse theme (category "AfterGit" of Colors and Fonts). Callers must not keep the
 * returned colors: a theme switch replaces them in the registry.
 */
public final class Palette {
  private Palette() {}

  public static Color strong(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.NONE ? null : color(ColorIds.strongId(kind));
  }

  public static Color light(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.NONE ? null : color(ColorIds.lightId(kind));
  }

  /** The darker stripes of an auto-resolved block that needs review (block-states design §4). */
  public static Color autoHatch() {
    return color(ColorIds.AUTO_HATCH);
  }

  /**
   * The background of the words in which a text differs from the one it is compared with (visual checklist row 44):
   * the strong shade of its kind. Where the strong shade is a text, bar or stripe colour, a shade between the light
   * and the strong one: on a dropped side and an AI block a fixed part of the way, on the auto colour the first one
   * that stands out from the auto fill by {@link #AUTO_WORD_CONTRAST} (in every scheme, light and dark), so the
   * words never look like the review stripes of auto.hatch.
   */
  public static Color word(LineStyle.Kind kind) {
    return switch (kind) {
      case NONE -> null;
      case AUTO -> contrasted(light(kind), strong(kind), AUTO_WORD_CONTRAST);
      case DROPPED -> mix(light(kind), strong(kind), 0.3);
      case AI -> mix(light(kind), strong(kind), 0.35);
      default -> strong(kind);
    };
  }

  /** The least contrast of an auto block's changed words against the auto fill. */
  static final double AUTO_WORD_CONTRAST = 2.0;

  /**
   * The text of the words in which a dropped side differs: darker than the side's struck text on a light theme,
   * lighter on a dark one, as far again past dropped.text as most of the way from the dropped fill to it; null for
   * other kinds, whose words keep their syntax colours.
   */
  public static Color wordText(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.DROPPED ? mix(light(kind), strong(kind), 1.6) : null;
  }

  /** The thin mark where a text of {@code kind} lacks words: the taken bar on yellow, else the strong shade. */
  public static Color insertion(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.MODIFIED ? takenStrong() : strong(kind);
  }

  /** The first shade from {@code from} towards {@code to}, in steps of 5%, with {@code min} contrast to it. */
  static Color contrasted(Color from, Color to, double min) {
    for (int step = 6; step < 20; step++) {
      Color c = mix(from, to, step / 20.0);
      if (contrast(c.getRGB(), from.getRGB()) >= min) {
        return c;
      }
    }
    return to;
  }

  /** {@code from} moved {@code part} of the way to {@code to}; a part above 1 goes past it, up to black or white. */
  static Color mix(Color from, Color to, double part) {
    return new Color(new org.eclipse.swt.graphics.RGB(mix(from.getRed(), to.getRed(), part),
        mix(from.getGreen(), to.getGreen(), part), mix(from.getBlue(), to.getBlue(), part)));
  }

  private static int mix(int from, int to, double part) {
    return Math.max(0, Math.min(255, (int) Math.round(from + (to - from) * part)));
  }

  /** WCAG 2 contrast ratio of two colours, 1 to 21. */
  static double contrast(org.eclipse.swt.graphics.RGB a, org.eclipse.swt.graphics.RGB b) {
    double x = luminance(a);
    double y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }

  private static double luminance(org.eclipse.swt.graphics.RGB c) {
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue);
  }

  private static double channel(int v) {
    double s = v / 255.0;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }

  /** The bar of a conflict the user edited by hand. */
  public static Color editedStrong() {
    return color(ColorIds.EDITED_STRONG);
  }

  /** The bar and the source glyph of a side that was taken. */
  public static Color takenStrong() {
    return color(ColorIds.TAKEN_STRONG);
  }

  /** The current block's frame: the platform's list selection colour unless the theme or the user set another. */
  public static Color current() {
    ColorRegistry r = registry();
    if (!r.hasValueFor(ColorIds.CURRENT)) {
      r.put(ColorIds.CURRENT, Display.getDefault().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB());
    }
    return r.get(ColorIds.CURRENT);
  }

  /** The editors' preference of the error annotation color. */
  static final String ERROR_COLOR_KEY = "errorIndicationColor";

  /** The error color of the editors' annotation settings. */
  static Color error() {
    return EditorsUI.getSharedTextColors()
        .getColor(PreferenceConverter.getColor(EditorsUI.getPreferenceStore(), ERROR_COLOR_KEY));
  }

  private static Color color(String id) {
    ColorRegistry r = registry();
    if (!r.hasValueFor(id)) {
      r.put(id, ColorIds.DEFAULTS.get(id));
    }
    return r.get(id);
  }

  static ColorRegistry registry() {
    return PlatformUI.isWorkbenchRunning()
        ? PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry()
        : JFaceResources.getColorRegistry();
  }
}
