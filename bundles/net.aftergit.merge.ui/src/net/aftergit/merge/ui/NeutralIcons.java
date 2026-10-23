package net.aftergit.merge.ui;

import java.util.EnumMap;
import java.util.Map;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.Visibility;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

/** The structure tree's own icons (Ruling 15): a letter in the colour of its kind, for any file and without JDT. */
final class NeutralIcons implements StructureImages {
  static final NeutralIcons INSTANCE = new NeutralIcons();

  private final Map<NodeKind, ImageDescriptor> icons = new EnumMap<>(NodeKind.class);

  private NeutralIcons() {}

  @Override
  public synchronized ImageDescriptor descriptor(NodeKind kind, Visibility visibility) {
    return icons.computeIfAbsent(kind, k -> ImageDescriptor.createFromImageDataProvider(zoom -> draw(k, zoom)));
  }

  static String letter(NodeKind k) {
    return switch (k) {
      case IMPORTS -> "i";
      case CLASS -> "C";
      case INTERFACE -> "I";
      case ENUM -> "E";
      case RECORD -> "R";
      case ANNOTATION -> "@";
      case FIELD -> "f";
      case CONSTANT -> "c";
      case CONSTRUCTOR, METHOD -> "m";
      case INITIALIZER -> "{";
      case PROPERTY -> "k";
      case HEADER -> "H";
      case CLAUSE -> "·";
    };
  }

  static RGB color(NodeKind k) {
    if (k.isType()) {
      return new RGB(66, 120, 200);
    }
    return switch (k) {
      case METHOD, CONSTRUCTOR, INITIALIZER -> new RGB(52, 150, 92);
      case FIELD, CONSTANT -> new RGB(196, 128, 32);
      default -> new RGB(128, 128, 128);
    };
  }

  /** The letter drawn black on white gives the alpha; every pixel has the kind's colour. */
  private static ImageData draw(NodeKind k, int zoom) {
    int size = 16 * zoom / 100;
    Display d = Display.getCurrent();
    Image img = new Image(d, size, size);
    GC gc = new GC(img);
    FontData fd = JFaceResources.getDefaultFont().getFontData()[0];
    fd.setStyle(SWT.BOLD);
    fd.setHeight(Math.max(6, 8 * zoom / 100));
    Font font = new Font(d, fd);
    try {
      gc.setBackground(d.getSystemColor(SWT.COLOR_WHITE));
      gc.fillRectangle(0, 0, size, size);
      gc.setForeground(d.getSystemColor(SWT.COLOR_BLACK));
      gc.setAntialias(SWT.ON);
      gc.setTextAntialias(SWT.ON);
      gc.setFont(font);
      String letter = letter(k);
      Point p = gc.stringExtent(letter);
      gc.drawString(letter, (size - p.x) / 2, (size - p.y) / 2, true);
      ImageData mask = img.getImageData();
      ImageData out = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
      int pixel = out.palette.getPixel(color(k));
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          out.setPixel(x, y, pixel);
          out.setAlpha(x, y, 255 - mask.palette.getRGB(mask.getPixel(x, y)).red);
        }
      }
      return out;
    } finally {
      font.dispose();
      gc.dispose();
      img.dispose();
    }
  }
}
