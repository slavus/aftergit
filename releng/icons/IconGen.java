import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Draws the icons of the AfterGit resolver with Java2D: every icon at 16 px and at 32 px ("@2x"), as a light set
 * (icons/) and a dark set (icons/dark/). The drawings are our own.
 *
 * <pre>
 *   java releng/icons/IconGen.java bundles/net.aftergit.merge.ui/icons           # writes every PNG
 *   java releng/icons/IconGen.java --check bundles/net.aftergit.merge.ui/icons   # compares; exit 1 on a difference
 * </pre>
 *
 * Every glyph colour of a set keeps a contrast of at least 3.0 (WCAG 2, graphics) to the toolbar background the set
 * is made for; the generator refuses to draw otherwise.
 */
public final class IconGen {
  /** Colours of one set; {@code background} is the toolbar background the set is checked against. */
  record Palette(Color grey, Color red, Color orange, Color green, Color blue, Color purple, Color white,
      Color shade, Color background) {}

  static final Palette LIGHT = new Palette(rgb(0x6B6B6B), rgb(0xD23B2A), rgb(0xB86200), rgb(0x2E8540),
      rgb(0x3D6FD6), rgb(0x834DF0), rgb(0xFFFFFF), new Color(0x6B, 0x6B, 0x6B, 0x59), rgb(0xF0F0F0));
  static final Palette DARK = new Palette(rgb(0xC8C8C8), rgb(0xFF7B6B), rgb(0xFFB340), rgb(0x6CCB7A),
      rgb(0x8AB0FF), rgb(0xB794FF), rgb(0xFFFFFF), new Color(0xC8, 0xC8, 0xC8, 0x59), rgb(0x48484C));

  /** Per-channel tolerance of --check, for anti-aliasing differences between JDK builds. */
  static final int TOLERANCE = 24;

  /** Draws one icon in a 16 x 16 unit space. */
  interface Icon {
    void draw(Graphics2D g, Palette p);
  }

  static final Map<String, Icon> ICONS = new LinkedHashMap<>();

  static {
    ICONS.put("prev_conflict", (g, p) -> fill(g, p.red(), arrow(true)));
    ICONS.put("next_conflict", (g, p) -> fill(g, p.red(), arrow(false)));
    ICONS.put("prev_change", (g, p) -> fill(g, p.grey(), arrow(true)));
    ICONS.put("next_change", (g, p) -> fill(g, p.grey(), arrow(false)));
    ICONS.put("take_left", (g, p) -> {
      box(g, p);
      fill(g, p.orange(), triangle(10, 5.5, 8));
    });
    ICONS.put("take_right", (g, p) -> {
      box(g, p);
      fill(g, p.orange(), triangle(6, 10.5, 8));
    });
    ICONS.put("take_both", (g, p) -> {
      box(g, p);
      fill(g, p.orange(), triangle(7.2, 3.8, 8));
      fill(g, p.orange(), triangle(8.8, 12.2, 8));
    });
    ICONS.put("ignore", (g, p) -> stroke(g, p.grey(), 2, new Line2D.Double(4, 4, 12, 12),
        new Line2D.Double(12, 4, 4, 12)));
    ICONS.put("auto_resolve", (g, p) -> fill(g, p.green(), star()));
    ICONS.put("layout_all", (g, p) -> {
      frame(g, p);
      stroke(g, p.grey(), 1.2, new Line2D.Double(5.83, 3.5, 5.83, 12.5), new Line2D.Double(10.17, 3.5, 10.17, 12.5));
    });
    ICONS.put("layout_left_result", (g, p) -> {
      fill(g, p.shade(), new Rectangle2D.Double(2.1, 4.1, 5.9, 7.8));
      frame(g, p);
      stroke(g, p.grey(), 1.2, new Line2D.Double(8, 3.5, 8, 12.5));
    });
    ICONS.put("layout_result_right", (g, p) -> {
      fill(g, p.shade(), new Rectangle2D.Double(8, 4.1, 5.9, 7.8));
      frame(g, p);
      stroke(g, p.grey(), 1.2, new Line2D.Double(8, 3.5, 8, 12.5));
    });
    ICONS.put("layout_result_below", (g, p) -> {
      frame(g, p);
      stroke(g, p.grey(), 1.2, new Line2D.Double(1.5, 8, 14.5, 8), new Line2D.Double(8, 3.5, 8, 8));
    });
    ICONS.put("more", (g, p) -> {
      for (double x : new double[] {3.5, 8, 12.5}) {
        fill(g, p.grey(), new Ellipse2D.Double(x - 1.4, 6.6, 2.8, 2.8));
      }
    });
    ICONS.put("resolved", (g, p) -> {
      fill(g, p.green(), new Ellipse2D.Double(1.5, 1.5, 13, 13));
      Path2D tick = new Path2D.Double();
      tick.moveTo(4.8, 8.3);
      tick.lineTo(7.1, 10.6);
      tick.lineTo(11.2, 5.9);
      stroke(g, p.white(), 1.7, tick);
    });
    ICONS.put("resolve", (g, p) -> {
      fill(g, p.blue(), new Rectangle2D.Double(6.43, 4.1, 3.14, 7.8));
      frame(g, p);
      stroke(g, p.grey(), 1.2, new Line2D.Double(5.83, 3.5, 5.83, 12.5), new Line2D.Double(10.17, 3.5, 10.17, 12.5));
    });
    ICONS.put("agents", (g, p) -> {
      fill(g, p.purple(), sparkle(7, 8.5, 6));
      fill(g, p.purple(), sparkle(12.8, 3.6, 2.6));
    });
  }

  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    boolean check = args.length == 2 && "--check".equals(args[0]);
    if (args.length != (check ? 2 : 1)) {
      System.err.println("usage: java releng/icons/IconGen.java [--check] <icons directory>");
      System.exit(2);
    }
    Path dir = Path.of(args[check ? 1 : 0]);
    requireContrast("light", LIGHT);
    requireContrast("dark", DARK);
    Set<String> expected = new TreeSet<>();
    int differences = 0;
    for (Map.Entry<String, Icon> e : ICONS.entrySet()) {
      for (boolean dark : new boolean[] {false, true}) {
        for (int scale : new int[] {1, 2}) {
          String name = (dark ? "dark/" : "") + e.getKey() + (scale == 2 ? "@2x" : "") + ".png";
          expected.add(name);
          BufferedImage image = render(e.getValue(), dark ? DARK : LIGHT, scale);
          Path file = dir.resolve(name);
          if (check) {
            differences += compare(image, file, name);
          } else {
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
          }
        }
      }
    }
    if (!check) {
      System.out.println(expected.size() + " icon files written to " + dir);
      return;
    }
    for (String found : present(dir)) {
      if (!expected.contains(found)) {
        System.err.println("not drawn by IconGen: " + found);
        differences++;
      }
    }
    if (differences > 0) {
      System.err.println(differences + " icon file(s) differ from IconGen");
      System.exit(1);
    }
    System.out.println(expected.size() + " icon files match IconGen");
  }

  static BufferedImage render(Icon icon, Palette p, int scale) {
    BufferedImage image = new BufferedImage(16 * scale, 16 * scale, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.scale(scale, scale);
      icon.draw(g, p);
    } finally {
      g.dispose();
    }
    return image;
  }

  /** 1 when {@code file} is missing or differs from {@code image} by more than {@link #TOLERANCE}, else 0. */
  static int compare(BufferedImage image, Path file, String name) throws IOException {
    if (!Files.isRegularFile(file)) {
      System.err.println("missing: " + name);
      return 1;
    }
    BufferedImage found = ImageIO.read(file.toFile());
    if (found == null || found.getWidth() != image.getWidth() || found.getHeight() != image.getHeight()) {
      System.err.println("wrong size or format: " + name);
      return 1;
    }
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int a = image.getRGB(x, y);
        int b = found.getRGB(x, y);
        for (int shift = 0; shift < 32; shift += 8) {
          if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > TOLERANCE) {
            System.err.println("differs at " + x + "," + y + ": " + name);
            return 1;
          }
        }
      }
    }
    return 0;
  }

  /** PNG files under {@code dir} (two levels: the light set and dark/), as relative paths with '/'. */
  static Set<String> present(Path dir) throws IOException {
    Set<String> out = new TreeSet<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (Stream<Path> files = Files.walk(dir, 2)) {
      files.filter(Files::isRegularFile).forEach(f -> out.add(dir.relativize(f).toString().replace('\\', '/')));
    }
    return out;
  }

  static void requireContrast(String set, Palette p) {
    for (Color c : new Color[] {p.grey(), p.red(), p.orange(), p.green(), p.blue(), p.purple()}) {
      double ratio = contrast(c, p.background());
      if (ratio < 3.0) {
        throw new IllegalStateException(String.format(Locale.ROOT, "%s set: #%06X on #%06X has contrast %.2f < 3.0",
            set, c.getRGB() & 0xFFFFFF, p.background().getRGB() & 0xFFFFFF, ratio));
      }
    }
  }

  static double contrast(Color a, Color b) {
    double x = luminance(a);
    double y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  }

  static double luminance(Color c) {
    return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
  }

  static double channel(int v) {
    double s = v / 255.0;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  }

  static Color rgb(int value) {
    return new Color(value);
  }

  static void fill(Graphics2D g, Color c, Shape s) {
    g.setColor(c);
    g.fill(s);
  }

  static void stroke(Graphics2D g, Color c, double width, Shape... shapes) {
    g.setColor(c);
    g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    for (Shape s : shapes) {
      g.draw(s);
    }
  }

  /** A block arrow pointing up or down. */
  static Shape arrow(boolean up) {
    double[][] pts = {{8, 2}, {14, 8}, {10.5, 8}, {10.5, 14}, {5.5, 14}, {5.5, 8}, {2, 8}};
    Path2D path = new Path2D.Double();
    for (int i = 0; i < pts.length; i++) {
      double y = up ? pts[i][1] : 16 - pts[i][1];
      if (i == 0) {
        path.moveTo(pts[i][0], y);
      } else {
        path.lineTo(pts[i][0], y);
      }
    }
    path.closePath();
    return path;
  }

  /** A triangle with its base at {@code baseX} (6 units high) and its tip at {@code tipX}. */
  static Shape triangle(double baseX, double tipX, double centerY) {
    Path2D path = new Path2D.Double();
    path.moveTo(baseX, centerY - 3);
    path.lineTo(tipX, centerY);
    path.lineTo(baseX, centerY + 3);
    path.closePath();
    return path;
  }

  /** The rounded box of the take icons. */
  static void box(Graphics2D g, Palette p) {
    stroke(g, p.grey(), 1.2, new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 2.5, 2.5));
  }

  /** The window of the layout icons. */
  static void frame(Graphics2D g, Palette p) {
    stroke(g, p.grey(), 1.2, new Rectangle2D.Double(1.5, 3.5, 13, 9));
  }

  /** A four-pointed sparkle of radius {@code r} around ({@code cx}, {@code cy}) with inward-curved sides. */
  static Shape sparkle(double cx, double cy, double r) {
    double k = r * 0.28;
    Path2D s = new Path2D.Double();
    s.moveTo(cx, cy - r);
    s.quadTo(cx + k, cy - k, cx + r, cy);
    s.quadTo(cx + k, cy + k, cx, cy + r);
    s.quadTo(cx - k, cy + k, cx - r, cy);
    s.quadTo(cx - k, cy - k, cx, cy - r);
    s.closePath();
    return s;
  }

  /** A five-pointed star. */
  static Shape star() {
    double[][] pts = {{12, 3}, {14.2, 8.3}, {20, 9}, {15.5, 12.8}, {17, 19}, {12, 16}, {7, 19}, {8.5, 12.8},
        {4, 9}, {9.8, 8.3}};
    Path2D path = new Path2D.Double();
    for (int i = 0; i < pts.length; i++) {
      double x = pts[i][0] * 2 / 3.0;
      double y = pts[i][1] * 2 / 3.0;
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    path.closePath();
    return path;
  }
}
