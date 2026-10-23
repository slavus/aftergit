package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;

/**
 * Screenshots of resolver controls for the visual comparison (spec §10.7), saved under target/screenshots. Taken
 * only on the virtual display of releng/ui-tests.sh; never committed.
 */
final class Screens {
  private Screens() {}

  /** Paints {@code control} into an image, saves it as {@code name}.png and returns its pixels. */
  static ImageData capture(Control control, String name) throws IOException {
    assertNull(System.getenv("WAYLAND_DISPLAY"), "screenshots are taken on Xvfb only: run releng/ui-tests.sh");
    UiDriver.flush();
    Point size = control.getSize();
    Image image = new Image(control.getDisplay(), Math.max(1, size.x), Math.max(1, size.y));
    try {
      GC gc = new GC(image);
      try {
        assertTrue(control.print(gc), "the control painted itself into the image");
      } finally {
        gc.dispose();
      }
      ImageData data = image.getImageData();
      Path dir = Path.of(System.getProperty("aftergit.screenshots", "target/screenshots"));
      Files.createDirectories(dir);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
      return data;
    } finally {
      image.dispose();
    }
  }

  static RGB pixel(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  /**
   * Some pixel in columns [x0, x1) of rows [y0, y1] is {@code color} to within {@code tolerance} per channel: an
   * antialiased slanted line never hits its exact colour.
   */
  static boolean contains(ImageData data, RGB color, int tolerance, int x0, int x1, int y0, int y1) {
    for (int y = Math.max(0, y0); y <= Math.min(data.height - 1, y1); y++) {
      for (int x = Math.max(0, x0); x < Math.min(data.width, x1); x++) {
        RGB p = pixel(data, x, y);
        if (Math.abs(p.red - color.red) <= tolerance && Math.abs(p.green - color.green) <= tolerance
            && Math.abs(p.blue - color.blue) <= tolerance) {
          return true;
        }
      }
    }
    return false;
  }

  /** The image is not one flat color: the paint reached it. */
  static boolean painted(ImageData data) {
    int first = data.getPixel(0, 0);
    for (int y = 0; y < data.height; y += 7) {
      for (int x = 0; x < data.width; x += 7) {
        if (data.getPixel(x, y) != first) {
          return true;
        }
      }
    }
    return false;
  }
}
