package net.aftergit.merge.ui;

import java.util.List;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;

/**
 * Our icons, drawn by {@code releng/icons/IconGen.java}: a light set in {@code icons/} and a dark set in
 * {@code icons/dark/}, each at 16 px with an {@code @2x} file that JFace picks at 200 % zoom.
 */
final class Icons {
  static final String PREV_CONFLICT = "prev_conflict";
  static final String NEXT_CONFLICT = "next_conflict";
  static final String PREV_CHANGE = "prev_change";
  static final String NEXT_CHANGE = "next_change";
  static final String TAKE_LEFT = "take_left";
  static final String TAKE_RIGHT = "take_right";
  static final String TAKE_BOTH = "take_both";
  static final String IGNORE = "ignore";
  static final String AUTO_RESOLVE = "auto_resolve";
  static final String LAYOUT_ALL = "layout_all";
  static final String LAYOUT_LEFT_RESULT = "layout_left_result";
  static final String LAYOUT_RESULT_RIGHT = "layout_result_right";
  static final String LAYOUT_RESULT_BELOW = "layout_result_below";
  static final String MORE = "more";
  static final String RESOLVED = "resolved";
  static final String RESOLVE = "resolve";
  static final String AGENTS = "agents";

  static final List<String> ALL = List.of(PREV_CONFLICT, NEXT_CONFLICT, PREV_CHANGE, NEXT_CHANGE, TAKE_LEFT,
      TAKE_RIGHT, TAKE_BOTH, IGNORE, AUTO_RESOLVE, LAYOUT_ALL, LAYOUT_LEFT_RESULT, LAYOUT_RESULT_RIGHT,
      LAYOUT_RESULT_BELOW, MORE, RESOLVED, RESOLVE, AGENTS);

  private Icons() {}

  /** The icon {@code name} of the dark or the light set. */
  static ImageDescriptor descriptor(String name, boolean dark) {
    String path = (dark ? "icons/dark/" : "icons/") + name + ".png";
    if (!ALL.contains(name)) {
      throw new IllegalStateException("Unknown icon " + name);
    }
    return ResourceLocator.imageDescriptorFromBundle(Icons.class, path)
        .orElseThrow(() -> new IllegalStateException("Missing icon " + path));
  }

  /** Opacity of a disabled icon: the glyph at about a third, as the approved mock-up greys unavailable items. */
  static final double DISABLED_ALPHA = 0.35;

  /**
   * The disabled form of icon {@code name}: the same glyph with its opacity scaled by {@link #DISABLED_ALPHA}. SWT's own
   * greyscale keeps a light glyph light, so on a dark bar a disabled item would look active.
   */
  static ImageDescriptor disabled(String name, boolean dark) {
    return new Dimmed(descriptor(name, dark));
  }

  /** An icon with its opacity scaled down; equal for equal sources, so resource managers share the image. */
  private static final class Dimmed extends ImageDescriptor {
    private final ImageDescriptor source;

    Dimmed(ImageDescriptor source) {
      this.source = source;
    }

    @Override
    public ImageData getImageData(int zoom) {
      ImageData in = source.getImageData(zoom);
      if (in == null) {
        return null;
      }
      ImageData mask = in.getTransparencyType() == SWT.TRANSPARENCY_MASK
          || in.getTransparencyType() == SWT.TRANSPARENCY_PIXEL ? in.getTransparencyMask() : null;
      ImageData out = (ImageData) in.clone();
      byte[] alpha = new byte[in.width * in.height];
      for (int y = 0; y < in.height; y++) {
        for (int x = 0; x < in.width; x++) {
          int a = mask != null ? (mask.getPixel(x, y) == 0 ? 0 : 255) : in.getAlpha(x, y);
          alpha[y * in.width + x] = (byte) Math.round(a * DISABLED_ALPHA);
        }
      }
      out.maskData = null;
      out.transparentPixel = -1;
      out.alpha = -1;
      out.alphaData = alpha;
      return out;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Dimmed d && d.source.equals(source);
    }

    @Override
    public int hashCode() {
      return source.hashCode() * 31 + 7;
    }
  }

  static String layout(PaneLayout pl) {
    return switch (pl) {
      case ALL -> LAYOUT_ALL;
      case LEFT_RESULT -> LAYOUT_LEFT_RESULT;
      case RESULT_RIGHT -> LAYOUT_RESULT_RIGHT;
      case RESULT_BELOW -> LAYOUT_RESULT_BELOW;
    };
  }

  /** The dark set suits {@code background}: perceived luminance below one half, as the status bar decides. */
  static boolean darkBackground(RGB background) {
    return (0.299 * background.red + 0.587 * background.green + 0.114 * background.blue) / 255.0 < 0.5;
  }
}
