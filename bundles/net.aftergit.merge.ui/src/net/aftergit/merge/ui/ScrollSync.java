package net.aftergit.merge.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Slider;

/** Drives the three panes from one shared vertical slider through a {@link ScrollMap}. */
final class ScrollSync {
  private final Slider slider;
  private final MergeViewer viewer;
  private final List<Runnable> scrollListeners = new CopyOnWriteArrayList<>();
  private ScrollMap map;
  private MergeModel shownModel;
  private double virtualTop;
  private boolean applying;
  /** Top pixel each pane had after this class last placed it; anything else means it moved on its own. */
  private final int[] lastTop = new int[PaneKind.values().length];
  /** Top pixel this class asked each pane for, before the widget clamped it. */
  private final int[] requestedTop = new int[PaneKind.values().length];
  private final int[] lastHorizontal = new int[PaneKind.values().length];
  private final boolean[] horizontalPending = new boolean[PaneKind.values().length];
  private int builtResultLines = -1;
  private double builtVirtualLines = -1;

  ScrollSync(Slider slider, MergeViewer viewer) {
    this.slider = slider;
    this.viewer = viewer;
    slider.setEnabled(false);
    slider.addListener(SWT.Selection, e -> scrollToVirtual(slider.getSelection() / (double) lineHeight()));
    for (PaneKind k : PaneKind.values()) {
      MergePane p = viewer.pane(k);
      StyledText t = p.text();
      p.viewer().addViewportListener(offset -> {
        if (!applying) {
          syncFrom(k);
        }
      });
      t.addListener(SWT.MouseVerticalWheel, e -> {
        e.doit = false;
        scrollToVirtual(virtualTop - e.count);
      });
      t.addListener(SWT.Resize, e -> rebuild());
      ScrollBar h = t.getHorizontalBar();
      if (h != null) {
        h.addListener(SWT.Selection, e -> syncHorizontal(k));
      }
      // caret moves and typing scroll horizontally without a scroll bar event
      t.addListener(SWT.Paint, e -> {
        int i = k.ordinal();
        if (t.getHorizontalPixel() != lastHorizontal[i] && !horizontalPending[i]) {
          horizontalPending[i] = true;
          t.getDisplay().asyncExec(() -> {
            horizontalPending[i] = false;
            if (!t.isDisposed() && t.getHorizontalPixel() != lastHorizontal[i]) {
              syncHorizontal(k);
            }
          });
        }
      });
    }
  }

  void addScrollListener(Runnable r) {
    scrollListeners.add(r);
  }

  void rebuild() {
    if (viewer.getInput() == null) {
      return;
    }
    MergeModel model = ((MergeSession) viewer.getInput()).model();
    if (model != shownModel) {
      // new input: the panes start at the top again
      shownModel = model;
      virtualTop = 0;
      builtResultLines = -1;
      for (PaneKind k : PaneKind.values()) {
        lastTop[k.ordinal()] = viewer.pane(k).text().getTopPixel();
        requestedTop[k.ordinal()] = lastTop[k.ordinal()];
      }
    }
    int resultLines = viewer.pane(PaneKind.RESULT).lineCount();
    // A result segment without lines cannot carry a position: its only line maps to the segment's end.
    boolean resultHasLinesAtMiddle =
        map != null && map.segmentLength(PaneKind.RESULT, virtualTop + viewportLines() / 2) > 0;
    map = ScrollMap.build(model, viewer.pane(PaneKind.LEFT).lineCount(), resultLines,
        viewer.pane(PaneKind.RIGHT).lineCount());
    boolean contentChanged = builtResultLines >= 0
        && (resultLines != builtResultLines || map.virtualLines() != builtVirtualLines);
    builtResultLines = resultLines;
    builtVirtualLines = map.virtualLines();
    if (movedOnItsOwn(PaneKind.RESULT) || contentChanged && resultHasLinesAtMiddle && anchorable(PaneKind.RESULT)) {
      // an edit scrolled the result or changed the map under it: keep the result still, move the others
      anchorOn(PaneKind.RESULT);
    } else {
      // a resize, or a position the result cannot express (clamped): keep the virtual position
      scrollToVirtual(virtualTop);
    }
  }

  /**
   * True when the pane's top differs from where this class placed it, for a reason other than the
   * widget clamping the requested position to its (possibly new, after a resize) maximum.
   */
  private boolean movedOnItsOwn(PaneKind kind) {
    MergePane p = viewer.pane(kind);
    if (!p.isVisible()) {
      return false;
    }
    int i = kind.ordinal();
    int top = p.text().getTopPixel();
    if (top == lastTop[i]) {
      return false;
    }
    boolean clampedToEnd = top >= maxTopPixel(p.text()) - 1 && requestedTop[i] >= top;
    return !clampedToEnd;
  }

  /** The pane's top can be mapped back to the virtual space without loss: it is not clamped at either end. */
  private boolean anchorable(PaneKind kind) {
    MergePane p = viewer.pane(kind);
    int top = p.text().getTopPixel();
    return p.isVisible() && top > 0 && top < maxTopPixel(p.text()) - 1;
  }

  private static int maxTopPixel(StyledText t) {
    int content = t.getLineCount() * t.getLineHeight() + t.getTopMargin() + t.getBottomMargin();
    return Math.max(0, content - t.getClientArea().height);
  }

  void scrollToVirtual(double top) {
    if (map == null) {
      return;
    }
    double max = Math.max(0, map.virtualLines() - viewportLines() / 2);
    virtualTop = Math.max(0, Math.min(top, max));
    updateSlider();
    apply();
  }

  /**
   * A pane scrolled on its own (caret, reveal, typing): move the virtual position and the other panes.
   * Nothing happens when the pane is still where it was placed, because mapping a clamped or
   * many-to-one pane position back would move the other panes.
   */
  void syncFrom(PaneKind kind) {
    if (map == null || !movedOnItsOwn(kind)) {
      return;
    }
    anchorOn(kind);
  }

  /** Takes the virtual position from {@code kind}'s current top and places the other panes. */
  private void anchorOn(PaneKind kind) {
    StyledText t = viewer.pane(kind).text();
    lastTop[kind.ordinal()] = t.getTopPixel();
    requestedTop[kind.ordinal()] = t.getTopPixel();
    double paneTop = t.getTopPixel() / (double) lineHeight();
    virtualTop = map.virtualTop(kind, paneTop, viewportLines());
    updateSlider();
    applyExcept(kind);
  }

  private void apply() {
    applyExcept(null);
  }

  private void applyExcept(PaneKind skip) {
    applying = true;
    try {
      for (PaneKind k : PaneKind.values()) {
        MergePane p = viewer.pane(k);
        if (k == skip || !p.isVisible()) {
          continue;
        }
        double top = map.paneTop(k, virtualTop, viewportLines());
        requestedTop[k.ordinal()] = (int) Math.round(top * lineHeight());
        p.text().setTopPixel(requestedTop[k.ordinal()]);
        lastTop[k.ordinal()] = p.text().getTopPixel(); // as clamped by the widget
      }
    } finally {
      applying = false;
    }
    for (Runnable r : scrollListeners) {
      r.run();
    }
  }

  private void updateSlider() {
    int lh = lineHeight();
    int client = viewer.pane(PaneKind.RESULT).text().getClientArea().height;
    int max = (int) Math.ceil(map.virtualLines() * lh) + client / 2;
    int thumb = Math.max(1, Math.min(client, max));
    slider.setEnabled(true);
    slider.setValues((int) Math.round(virtualTop * lh), 0, Math.max(max, 1), thumb, lh, Math.max(1, client - lh));
  }

  private void syncHorizontal(PaneKind from) {
    ScrollBar src = viewer.pane(from).text().getHorizontalBar();
    lastHorizontal[from.ordinal()] = viewer.pane(from).text().getHorizontalPixel();
    if (src == null) {
      return;
    }
    int range = Math.max(1, src.getMaximum() - src.getThumb());
    double ratio = src.getSelection() / (double) range;
    for (PaneKind k : PaneKind.values()) {
      if (k == from) {
        continue;
      }
      StyledText t = viewer.pane(k).text();
      ScrollBar h = t.getHorizontalBar();
      if (h != null) {
        t.setHorizontalPixel((int) Math.round(ratio * Math.max(0, h.getMaximum() - h.getThumb())));
      }
      lastHorizontal[k.ordinal()] = t.getHorizontalPixel();
    }
  }

  private int lineHeight() {
    return Math.max(1, viewer.pane(PaneKind.RESULT).text().getLineHeight());
  }

  private double viewportLines() {
    return viewer.pane(PaneKind.RESULT).text().getClientArea().height / (double) lineHeight();
  }
}
