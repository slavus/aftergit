package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.function.IntConsumer;
import net.aftergit.merge.core.structure.StructureNode;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IElementComparer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.OwnerDrawLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * The structure tree of the Structure panel and of the Outline page (structure-compare design §2, §3): icon, name
 * (struck through when the node is not in the result), "who" small and grey (not when compact), the state chip at the
 * right edge. A click or Enter jumps to the node's block, the next ones cycle (Ruling 16); the current block selects
 * its node without taking the focus.
 */
final class StructureTree {
  private static final int ICON = 16;
  private static final int GAP = 6;
  private static final int CHIP_PAD = 5;
  private static final String ELLIPSIS = "\u2026";

  record ChipColors(Color background, Color foreground, Color border) {}

  private final MergeViewer viewer;
  private final boolean compact;
  private final Composite stack;
  private final StackLayout layout = new StackLayout();
  private final TreeViewer tree;
  private final Label empty;
  private final LocalResourceManager resources;
  /** The smaller font of "who", derived from the tree's; rebuilt when the merge-panes font changes. */
  private Font small;
  private FontDescriptor smallDescriptor;
  private final Runnable onChange = this::update;
  private final IntConsumer onCurrent = this::showCurrentBlock;
  private boolean jdtFailed;
  /** Paths of the nodes the user collapsed in the session {@code shownSession}. */
  private final Set<String> collapsed = new HashSet<>();
  private Object shownSession;
  /** The next filled tree scrolls to the current block's node: a new session, or the tree was just shown. */
  private boolean revealNext;

  StructureTree(Composite parent, MergeViewer viewer, boolean compact) {
    this.viewer = viewer;
    this.compact = compact;
    stack = new Composite(parent, SWT.NONE);
    stack.setLayout(layout);
    resources = new LocalResourceManager(JFaceResources.getResources(), stack);
    tree = new TreeViewer(stack, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    tree.setUseHashlookup(true);
    tree.setComparer(new IElementComparer() {
      @Override
      public boolean equals(Object a, Object b) {
        return a instanceof StructureNode x && b instanceof StructureNode y ? x.path().equals(y.path()) : a == b;
      }

      @Override
      public int hashCode(Object e) {
        return e instanceof StructureNode n ? n.path().hashCode() : e.hashCode();
      }
    });
    tree.setContentProvider(new Content());
    tree.setLabelProvider(new Row());
    ColumnViewerToolTipSupport.enableFor(tree);
    tree.getTree().setFont(MergeFont.get());
    deriveSmall();
    empty = new Label(stack, SWT.WRAP);
    Tree t = tree.getTree();
    t.addListener(SWT.MouseUp, e -> {
      if (e.button == 1 && e.count < 2) { // a double-click's second click would move on to the next block
        TreeItem item = t.getItem(new Point(e.x, e.y));
        // not on the expand arrow, which lies left of the item's bounds
        if (item != null && e.x >= item.getBounds().x && item.getData() instanceof StructureNode n) {
          activate(n);
        }
      }
    });
    // the user's own expanding and collapsing (SWT sends no event for the tree's own expandAll)
    AccessibleNames.set(t, child -> child == ACC.CHILDID_SELF ? Messages.structureTitle : null);
    t.addListener(SWT.Collapse, e -> {
      if (e.item != null && e.item.getData() instanceof StructureNode n) {
        collapsed.add(n.path());
      }
    });
    t.addListener(SWT.Expand, e -> {
      if (e.item != null && e.item.getData() instanceof StructureNode n) {
        collapsed.remove(n.path());
      }
    });
    t.addListener(SWT.KeyDown, e -> {
      if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
        TreeItem[] sel = t.getSelection();
        if (sel.length == 1 && sel[0].getData() instanceof StructureNode n) {
          activate(n);
        }
      }
    });
    viewer.structure().addListener(onChange);
    viewer.addCurrentBlockListener(onCurrent);
    stack.addDisposeListener(e -> {
      viewer.structure().removeListener(onChange);
      viewer.removeCurrentBlockListener(onCurrent);
    });
    MergeFont.watch(tree.getTree(), () -> {
      tree.getTree().setFont(MergeFont.get());
      deriveSmall(); // the smaller font of "who" follows the tree's
      tree.refresh();
    });
    update();
  }

  /** {@link #small} from the tree's current font; the previous one is released. */
  private void deriveSmall() {
    FontData fd = tree.getTree().getFont().getFontData()[0];
    fd.setHeight(Math.max(6, fd.getHeight() - 1));
    FontDescriptor d = FontDescriptor.createFrom(fd);
    Font previous = small;
    small = resources.create(d);
    if (previous != null) {
      resources.destroy(smallDescriptor);
    }
    smallDescriptor = d;
  }

  Control control() {
    return stack;
  }

  Tree tree() {
    return tree.getTree();
  }

  /** Its block becomes the current block and is revealed; the next activations cycle through its blocks. */
  void activate(StructureNode n) {
    int b = n.blockAfter(viewer.selectedBlock());
    if (b >= 0) {
      viewer.selectBlock(b);
    }
  }

  private List<StructureNode> visible(List<StructureNode> nodes) {
    return viewer.structure().showAll() ? nodes : nodes.stream().filter(StructureNode::shownWhenFiltered).toList();
  }

  private String emptyMessage() {
    StructureController c = viewer.structure();
    return switch (c.status()) {
      case NOT_AVAILABLE -> Messages.structureNotAvailable;
      case NEEDS_JAVA -> Messages.structureNeedsJava;
      case READY -> c.failed() ? Messages.structureFailed
          : c.computed() && visible(c.nodes()).isEmpty() ? Messages.structureNoChanges : null;
    };
  }

  /** The message shown instead of the tree, null while the tree shows. */
  String emptyText() {
    return layout.topControl == empty ? empty.getText() : null;
  }

  /**
   * Fills the tree with the newest nodes. Within a session the nodes the user collapsed stay collapsed and the top row
   * stays on top; a new session starts expanded, at the top.
   */
  private void update() {
    if (stack.isDisposed() || viewer.getControl().isDisposed()) {
      return;
    }
    String message = emptyMessage();
    empty.setText(message == null ? "" : message);
    Tree t = tree.getTree();
    Object input = viewer.getInput();
    String top = null;
    if (input != shownSession) {
      shownSession = input;
      collapsed.clear();
      revealNext = true;
    } else if (t.getTopItem() != null && t.getTopItem().getData() instanceof StructureNode n) {
      top = n.path();
    }
    List<StructureNode> nodes = viewer.structure().nodes();
    t.setRedraw(false);
    try {
      tree.setInput(nodes);
      tree.expandAll();
      for (String path : collapsed) {
        StructureNode n = byPath(nodes, path);
        if (n != null) {
          tree.setExpandedState(n, false);
        }
      }
      TreeItem item = top == null ? null : item(t.getItems(), top);
      if (item != null) {
        t.setTopItem(item);
      }
    } finally {
      t.setRedraw(true);
    }
    layout.topControl = message == null ? tree.getControl() : empty;
    stack.layout();
    boolean reveal = revealNext && viewer.structure().computed();
    if (reveal) {
      revealNext = false;
    }
    showCurrentBlock(viewer.selectedBlock(), reveal); // otherwise the user's scroll position stays
  }

  /** Scrolls to the current block's node now and once the tree is next filled (the tree was just shown). */
  void revealCurrentBlock() {
    revealNext |= !viewer.structure().computed(); // not filled yet: once it is
    showCurrentBlock(viewer.selectedBlock(), true);
  }

  private static StructureNode byPath(List<StructureNode> nodes, String path) {
    for (StructureNode n : nodes) {
      if (n.path().equals(path)) {
        return n;
      }
      StructureNode inner = byPath(n.children(), path);
      if (inner != null) {
        return inner;
      }
    }
    return null;
  }

  /** The item of {@code path} among the expanded items. */
  private static TreeItem item(TreeItem[] items, String path) {
    for (TreeItem i : items) {
      if (i.getData() instanceof StructureNode n && n.path().equals(path)) {
        return i;
      }
      if (i.getExpanded()) {
        TreeItem inner = item(i.getItems(), path);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  /** Selects the deepest node of {@code block} without taking the focus (design §2). */
  private void showCurrentBlock(int block) {
    showCurrentBlock(block, true);
  }

  private void showCurrentBlock(int block, boolean reveal) {
    if (stack.isDisposed()) {
      return;
    }
    StructureNode n = block < 0 ? null : nodeOf(viewer.structure().nodes(), block);
    tree.setSelection(n == null ? StructuredSelection.EMPTY : new StructuredSelection(n), reveal);
  }

  /** The deepest node whose blocks include {@code block}, first in tree order; null for none. */
  static StructureNode nodeOf(List<StructureNode> nodes, int block) {
    for (StructureNode n : nodes) {
      StructureNode inner = nodeOf(n.children(), block);
      if (inner != null) {
        return inner;
      }
      if (n.blocks().contains(block)) {
        return n;
      }
    }
    return null;
  }

  /** The rows as "label | who | chip", children indented by two spaces; "(removed)" after a struck-through label. */
  List<String> rows() {
    List<String> out = new ArrayList<>();
    if (layout.topControl == tree.getControl()) {
      for (TreeItem i : tree.getTree().getItems()) {
        rows(i, "", out);
      }
    }
    return out;
  }

  private void rows(TreeItem item, String indent, List<String> out) {
    StructureNode n = (StructureNode) item.getData();
    out.add(indent + StructureLabels.label(n) + (n.inResult() ? "" : " (removed)") + " | "
        + (compact ? "" : StructureLabels.who(n.who())) + " | " + chip(n).text());
    for (TreeItem c : item.getItems()) {
      rows(c, indent + "  ", out);
    }
  }

  private StructureLabels.Chip chip(StructureNode n) {
    return StructureLabels.chip(n, viewer.origin(n.worstBlock()));
  }

  /** Chip colours from the theme (Ruling 23): a filled "A" has its text in the tree's background colour. */
  static ChipColors colors(StructureLabels.ChipStyle style, Control on) {
    Color fg = on.getForeground();
    return switch (style) {
      case CONFLICT -> new ChipColors(Palette.strong(LineStyle.Kind.CONFLICT), fg, null);
      case AUTO_FILLED -> new ChipColors(Palette.strong(LineStyle.Kind.AUTO), on.getBackground(), null);
      case AUTO_OUTLINED -> new ChipColors(null, Palette.strong(LineStyle.Kind.AUTO), Palette.strong(LineStyle.Kind.AUTO));
      case AI_FILLED -> new ChipColors(Palette.light(LineStyle.Kind.AI), Palette.strong(LineStyle.Kind.AI), null);
      case AI_OUTLINED -> new ChipColors(null, Palette.strong(LineStyle.Kind.AI), Palette.strong(LineStyle.Kind.AI));
      case TAKEN -> new ChipColors(null, Palette.takenStrong(), null);
      case EDITED -> new ChipColors(null, Palette.editedStrong(), null);
      case GREY -> new ChipColors(null, Palette.strong(LineStyle.Kind.DROPPED), null);
      case NONE -> new ChipColors(null, fg, null);
    };
  }

  /**
   * JDT's icon, else the neutral one. JDT's descriptors fail only when they are asked for or drawn (JDT UI does not
   * start, a missing class): this tree then keeps to the neutral icons, logged once per tree.
   */
  private Image image(StructureNode n) {
    StructureImages jdt = viewer.structure().images();
    if (jdt != null && !jdtFailed) {
      try {
        ImageDescriptor d = jdt.descriptor(n.kind(), n.visibility());
        if (d != null) {
          return resources.create(d);
        }
      } catch (RuntimeException | LinkageError e) {
        jdtFailed = true;
        ILog.of(StructureTree.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
            "The Java icons of the structure tree are not available; it shows its own", JavaBridge.loggable(e)));
      }
    }
    return resources.create(NeutralIcons.INSTANCE.descriptor(n.kind(), n.visibility()));
  }

  /** Where a row draws "who" (as shown, maybe shortened) and its chip; {@code chipWidth} is 0 without a chip. */
  record RowParts(String who, int whoX, int whoEnd, int chipX, int chipWidth) {}

  /** The parts of {@code item}'s row as it is painted now. */
  RowParts parts(TreeItem item) {
    GC gc = new GC(tree.getTree());
    try {
      gc.setFont(tree.getTree().getFont());
      return parts(gc, (StructureNode) item.getData(), item.getBounds());
    } finally {
      gc.dispose();
    }
  }

  /** {@code gc} has the tree's font. */
  private RowParts parts(GC gc, StructureNode n, Rectangle cell) {
    Rectangle area = tree.getTree().getClientArea();
    int x = cell.x + 2 + ICON + GAP + gc.stringExtent(StructureLabels.label(n)).x + GAP;
    String chip = chip(n).text();
    int chipWidth = chip.isEmpty() ? 0 : gc.stringExtent(chip).x + 2 * CHIP_PAD;
    int right = area.x + area.width - chipWidth - GAP; // the chip's place at the right edge
    String who = compact ? "" : StructureLabels.who(n.who());
    int whoEnd = x;
    if (!who.isEmpty()) {
      Font f = gc.getFont();
      gc.setFont(small);
      who = shorten(gc, who, right - (chipWidth > 0 ? GAP : 0) - x);
      whoEnd = x + (who.isEmpty() ? 0 : gc.stringExtent(who).x);
      gc.setFont(f);
    }
    int chipX = Math.max(who.isEmpty() ? x : whoEnd + GAP, right); // only a long name pushes it out
    return new RowParts(who, x, whoEnd, chipX, chipWidth);
  }

  /** {@code text} if it fits {@code width}, else its longest start that fits with "…" after it; "" when not even that. */
  static String shorten(GC gc, String text, int width) {
    if (gc.stringExtent(text).x <= width) {
      return text;
    }
    for (int end = text.length() - 1; end > 0; end--) {
      String s = text.substring(0, end).stripTrailing() + ELLIPSIS;
      if (gc.stringExtent(s).x <= width) {
        return s;
      }
    }
    return "";
  }

  private final class Content implements ITreeContentProvider {
    @Override
    @SuppressWarnings("unchecked")
    public Object[] getElements(Object input) {
      return visible((List<StructureNode>) input).toArray();
    }

    @Override
    public Object[] getChildren(Object parent) {
      return visible(((StructureNode) parent).children()).toArray();
    }

    @Override
    public Object getParent(Object element) {
      return null;
    }

    @Override
    public boolean hasChildren(Object element) {
      return getChildren(element).length > 0;
    }
  }

  private final class Row extends OwnerDrawLabelProvider {
    /** The item's own text, for assistive technology and the tree's type-ahead; never painted (erase, paint). */
    @Override
    public void update(ViewerCell cell) {
      if (cell.getElement() instanceof StructureNode n) {
        cell.setText(StructureLabels.text(n, compact, chip(n)));
      }
      super.update(cell);
    }

    @Override
    public String getToolTipText(Object element) {
      return viewer.getInput() instanceof MergeSession s && element instanceof StructureNode n
          ? StructureLabels.tooltip(n, s.model()) : null;
    }

    @Override
    protected void measure(Event e, Object element) {
      StructureNode n = (StructureNode) element;
      Point label = e.gc.stringExtent(StructureLabels.label(n));
      // "who" is shortened to the room left (paint), so only the name and the chip set the width
      int width = 2 + ICON + GAP + label.x + GAP + e.gc.stringExtent(chip(n).text()).x + 2 * CHIP_PAD + GAP;
      Rectangle area = tree.getTree().getClientArea();
      // GTK measures at x 0, the cell starts after the expander's indent (its bounds skip measuring there)
      int left = "gtk".equals(SWT.getPlatform()) ? ((TreeItem) e.item).getBounds().x : e.x;
      e.width = Math.max(width, area.x + area.width - left); // the row reaches the right edge: the chip sits there
      e.height = Math.max(e.height, Math.max(ICON, label.y) + 6);
    }

    @Override
    protected void erase(Event e, Object element) {
      e.detail &= ~SWT.FOREGROUND; // the selection background stays, the row draws its own text
    }

    @Override
    protected void paint(Event e, Object element) {
      StructureNode n = (StructureNode) element;
      GC gc = e.gc;
      Tree t = tree.getTree();
      Rectangle cell = ((TreeItem) e.item).getBounds();
      Color normal = gc.getForeground();
      int x = cell.x + 2;
      Image icon = image(n);
      if (icon != null) {
        gc.drawImage(icon, x, cell.y + (cell.height - icon.getBounds().height) / 2);
      }
      x += ICON + GAP;
      boolean selected = (e.detail & SWT.SELECTED) != 0;
      gc.setForeground(n.changed() || selected ? normal : Palette.strong(LineStyle.Kind.DROPPED));
      String label = StructureLabels.label(n);
      Point extent = gc.stringExtent(label);
      int textY = cell.y + (cell.height - extent.y) / 2;
      gc.drawString(label, x, textY, true);
      if (!n.inResult()) {
        int mid = textY + extent.y / 2;
        gc.drawLine(x, mid, x + extent.x, mid); // removed in the result (Ruling 7)
      }
      RowParts parts = parts(gc, n, cell);
      if (!parts.who().isEmpty()) {
        Font f = gc.getFont();
        gc.setFont(small);
        gc.setForeground(Palette.strong(LineStyle.Kind.DROPPED));
        gc.drawString(parts.who(), parts.whoX(), cell.y + (cell.height - gc.stringExtent(parts.who()).y) / 2, true);
        gc.setFont(f);
      }
      StructureLabels.Chip chip = chip(n);
      if (!chip.text().isEmpty()) {
        Point c = gc.stringExtent(chip.text());
        int w = parts.chipWidth();
        int cx = parts.chipX();
        ChipColors cc = colors(chip.style(), t);
        if (cc.background() != null) {
          gc.setBackground(cc.background());
          gc.fillRoundRectangle(cx, cell.y + 2, w, cell.height - 4, 6, 6);
        }
        if (cc.border() != null) {
          gc.setForeground(cc.border());
          gc.drawRoundRectangle(cx, cell.y + 2, w - 1, cell.height - 5, 6, 6);
        }
        gc.setForeground(cc.foreground());
        gc.drawString(chip.text(), cx + CHIP_PAD, cell.y + (cell.height - c.y) / 2, true);
      }
      gc.setForeground(normal);
    }
  }
}
