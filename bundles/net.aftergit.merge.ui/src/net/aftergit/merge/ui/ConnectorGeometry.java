package net.aftergit.merge.ui;

/** Geometry of the connector bands between two panes, in the style of SmartGit's Conflict Solver. */
final class ConnectorGeometry {
  static final int BUTTON = 10;

  static final int MIN_BUTTON = 6;

  /** Half the size of the clickable square around a band's ✨: it reaches the ring of a waiting proposal. */
  static final int SPARKLE = 8;

  /** The middle of a band, where its ✨ is drawn: {x, y}. */
  static int[] center(Band b, int width) {
    return new int[] {(width - 1) / 2, (b.lt() + b.lb() + b.rt() + b.rb()) / 4};
  }

  static boolean nearCenter(Band b, int x, int y, int width) {
    int[] c = center(b, width);
    return Math.abs(x - c[0]) <= SPARKLE && Math.abs(y - c[1]) <= SPARKLE;
  }

  enum Hit { NONE, TAKE, IGNORE }

  private ConnectorGeometry() {}

  /** sideOnLeft: the side pane is at the left edge (left connector); the result is at the other edge. */
  static Hit hit(Band b, int x, int y, int width, boolean sideOnLeft) {
    int w = width - 1;
    boolean leftColumn = x < BUTTON;
    boolean rightColumn = x > w - BUTTON;
    boolean inLeft = y >= b.lt() && y < Math.max(b.lb(), b.lt() + MIN_BUTTON);
    boolean inRight = y >= b.rt() && y < Math.max(b.rb(), b.rt() + MIN_BUTTON);
    if (leftColumn && inLeft) {
      return sideOnLeft ? Hit.TAKE : Hit.IGNORE;
    }
    if (rightColumn && inRight) {
      return sideOnLeft ? Hit.IGNORE : Hit.TAKE;
    }
    return Hit.NONE;
  }

  /** Top and exclusive bottom of a block at the left (lt, lb) and right (rt, rb) edge. */
  record Band(int block, int lt, int lb, int rt, int rb) {}

  record Curve(float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1) {}

  record Shape(Curve top, Curve bottom) {}

  static Shape shape(Band b, int width) {
    float w = width - 1;
    float xl = BUTTON;
    float xr = w - BUTTON;
    float xm = (xl + xr) / 2f;
    Curve top = new Curve(xl, b.lt(), xm, b.lt(), xm, b.rt(), xr, b.rt());
    Curve bottom = new Curve(xr, b.rb(), xm, b.rb(), xm, b.lb(), xl, b.lb());
    return new Shape(top, bottom);
  }

  static boolean visible(Band b, int height) {
    int top = Math.min(b.lt(), b.rt());
    int bottom = Math.max(b.lb(), b.rb());
    return bottom >= 0 && top <= height;
  }
}
