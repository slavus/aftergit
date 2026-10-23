package net.aftergit.merge.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/**
 * One line of text on the status bar that draws its own background and text. The theme CSS restyles labels and
 * composites after the editor is created, so the state color of the bar is never set on a widget: it is painted here.
 * Like a label it takes no keyboard focus, and screen readers see a label whose name is the text.
 */
final class BarText extends Canvas {
  private String text = "";
  private final boolean secondary;
  private Color background;
  private final AccessibleControlAdapter accessibleControl = new AccessibleControlAdapter() {
    @Override
    public void getRole(AccessibleControlEvent e) {
      e.detail = ACC.ROLE_LABEL;
    }

    @Override
    public void getValue(AccessibleControlEvent e) {
      e.result = text;
    }
  };

  BarText(Composite parent, boolean secondary) {
    super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
    this.secondary = secondary;
    addListener(SWT.Paint, e -> paint(e.gc));
    getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        e.result = text;
      }
    });
    getAccessible().addAccessibleControlListener(accessibleControl);
  }

  AccessibleControlAdapter accessibleControl() {
    return accessibleControl;
  }

  String text() {
    return text;
  }

  void setText(String value) {
    String v = value == null ? "" : value;
    if (!v.equals(text)) {
      text = v;
      getParent().layout(true);
      redraw();
      getAccessible().sendEvent(ACC.EVENT_NAME_CHANGED, null);
    }
  }

  /** The bar's state color, or null to show the theme's background. */
  void setBarColor(Color color) {
    background = color;
    redraw();
  }

  Color barColor() {
    return background;
  }

  @Override
  public Point computeSize(int wHint, int hHint, boolean changed) {
    GC gc = new GC(this);
    try {
      Point p = gc.textExtent(text);
      return new Point(wHint == SWT.DEFAULT ? p.x : wHint, hHint == SWT.DEFAULT ? p.y : hHint);
    } finally {
      gc.dispose();
    }
  }

  private void paint(GC gc) {
    Color bg = background != null ? background : getParent().getBackground();
    gc.setBackground(bg);
    gc.fillRectangle(getClientArea());
    boolean dark = luminance(bg.getRGB()) < 0.5;
    gc.setForeground(getDisplay().getSystemColor(secondary
        ? (dark ? SWT.COLOR_GRAY : SWT.COLOR_DARK_GRAY)
        : (dark ? SWT.COLOR_WHITE : SWT.COLOR_BLACK)));
    gc.drawText(text, 0, 0, true);
  }

  private static double luminance(RGB c) {
    return (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0;
  }
}
