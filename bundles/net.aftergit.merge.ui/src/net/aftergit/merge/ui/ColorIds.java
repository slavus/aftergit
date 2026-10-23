package net.aftergit.merge.ui;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.swt.graphics.RGB;

/** Theme color definition ids ({@code plugin.xml}) and their light defaults (block-states design §4). */
final class ColorIds {
  private ColorIds() {}

  private static final String PREFIX = "net.aftergit.merge.color.";

  /** Colors and Fonts: the category "AfterGit" and its sub-categories (preferences design §1). */
  static final String CATEGORY = "net.aftergit.merge.colors";
  static final String CATEGORY_CHANGES = CATEGORY + ".changes";
  static final String CATEGORY_STATES = CATEGORY + ".states";
  static final String CATEGORY_CURRENT = CATEGORY + ".current";
  static final String CATEGORY_AI = CATEGORY + ".ai";

  /** The sub-category of the colour {@code id}. */
  static String categoryOf(String id) {
    String name = id.substring(PREFIX.length());
    if (name.startsWith("added.") || name.startsWith("removed.") || name.startsWith("modified.")) {
      return CATEGORY_CHANGES;
    }
    if (name.equals("current")) {
      return CATEGORY_CURRENT;
    }
    return name.startsWith("ai.") ? CATEGORY_AI : CATEGORY_STATES;
  }

  /** The darker stripes of an auto-resolved block that needs review. */
  static final String AUTO_HATCH = PREFIX + "auto.hatch";
  /** The bar of a conflict the user edited by hand. */
  static final String EDITED_STRONG = PREFIX + "edited.strong";
  /** The bar and the source glyph of a side that was taken. */
  static final String TAKEN_STRONG = PREFIX + "taken.strong";
  /** Text and outline of a side whose text is not in the result. */
  static final String DROPPED_TEXT = PREFIX + "dropped.text";
  /** The current block's frame; its light default is the platform's list selection colour, so it has no RGB here. */
  static final String CURRENT = PREFIX + "current";

  static final Map<String, RGB> DEFAULTS = new LinkedHashMap<>();

  static {
    put(LineStyle.Kind.MODIFIED, new RGB(255, 223, 153), new RGB(255, 238, 196));
    put(LineStyle.Kind.ADDED, new RGB(191, 232, 191), new RGB(237, 248, 237));
    put(LineStyle.Kind.REMOVED, new RGB(0xFF, 0x99, 0x99), new RGB(0xFF, 0xEB, 0xEB));
    put(LineStyle.Kind.CONFLICT, new RGB(242, 139, 122), new RGB(251, 205, 197));
    put(LineStyle.Kind.AUTO, new RGB(31, 157, 139), new RGB(217, 242, 238));
    put(LineStyle.Kind.AI, new RGB(0x83, 0x4D, 0xF0), new RGB(0xEF, 0xE6, 0xFF));
    put(LineStyle.Kind.DROPPED, new RGB(154, 154, 154), new RGB(241, 241, 241));
    DEFAULTS.put(AUTO_HATCH, new RGB(195, 233, 226));
    DEFAULTS.put(EDITED_STRONG, new RGB(107, 107, 138));
    DEFAULTS.put(TAKEN_STRONG, new RGB(224, 168, 0));
  }

  /** The dark theme's values (css/dark.css; PaletteTest keeps the two the same). */
  static final Map<String, RGB> DARK = new LinkedHashMap<>();

  static {
    String[][] table = {{"modified", "122,81,0", "64,56,34"}, {"added", "30,96,30", "42,68,42"},
        {"removed", "128,0,0", "77,58,58"}, {"conflict", "140,40,24", "92,52,46"},
        {"auto", "60,194,173", "33,66,62"}, {"ai", "160,124,245", "48,38,74"}};
    for (String[] r : table) {
      DARK.put(PREFIX + r[0] + ".strong", rgb(r[1]));
      DARK.put(PREFIX + r[0] + ".light", rgb(r[2]));
    }
    DARK.put(AUTO_HATCH, rgb("42,82,76"));
    DARK.put(PREFIX + "dropped.light", rgb("51,51,51"));
    DARK.put(DROPPED_TEXT, rgb("119,119,119"));
    DARK.put(EDITED_STRONG, rgb("150,150,190"));
    DARK.put(TAKEN_STRONG, rgb("201,150,10"));
    DARK.put(CURRENT, rgb("88,150,240"));
  }

  static RGB rgb(String s) {
    String[] v = s.split(",");
    return new RGB(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
  }

  private static void put(LineStyle.Kind kind, RGB strong, RGB light) {
    DEFAULTS.put(strongId(kind), strong);
    DEFAULTS.put(lightId(kind), light);
  }

  /** The strong shade of {@code kind}; for a dropped side its text colour. */
  static String strongId(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.DROPPED ? DROPPED_TEXT : PREFIX + kind.name().toLowerCase(Locale.ROOT) + ".strong";
  }

  static String lightId(LineStyle.Kind kind) {
    return PREFIX + kind.name().toLowerCase(Locale.ROOT) + ".light";
  }

  /** Every theme colour of the resolver: the defaults and {@link #CURRENT}. */
  static Set<String> all() {
    Set<String> ids = new LinkedHashSet<>(DEFAULTS.keySet());
    ids.add(CURRENT);
    return ids;
  }
}
