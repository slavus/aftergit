package net.aftergit.merge.ui;

import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;

/** One pane: read-only title field above a source viewer with line numbers. */
public final class MergePane extends Composite {
  private final Text header;
  private final SourceViewer viewer;
  private final PanePainter painter;
  private final ChangeStripe stripe;

  /**
   * Creates the text viewer of a pane; {@code rulers} returns a new ruler with line numbers (and in the result the
   * origin column) on each call.
   */
  interface ViewerFactory {
    ViewerFactory PLAIN = (parent, rulers, styles) -> new SourceViewer(parent, rulers.get(), styles);

    SourceViewer create(Composite parent, java.util.function.Supplier<CompositeRuler> rulers, int styles);
  }

  MergePane(Composite parent, boolean editable, boolean stripeOnLeft) {
    this(parent, editable, stripeOnLeft, ViewerFactory.PLAIN);
  }

  /** The result's column of origins and badges; null in the side panes. */
  private OriginColumn originColumn;
  /** The line numbers of the ruler the viewer was built on. */
  private LineNumbers numbers;
  /** The text's background the line numbers were last painted on. */
  private RGB numbersBackground;

  /**
   * Line numbers on the text's own background (the theme's or the Java editor's) in the text editors' line number
   * colour, as Eclipse's editors show them; without them the column takes the platform's list colours.
   */
  private final class LineNumbers extends LineNumberRulerColumn {
    @Override
    protected Color getBackground(Display display) {
      StyledText t = viewer == null ? null : viewer.getTextWidget();
      return t == null || t.isDisposed() ? super.getBackground(display) : t.getBackground();
    }

    @Override
    public Control createControl(CompositeRuler parentRuler, Composite parentControl) {
      Control c = super.createControl(parentRuler, parentControl);
      followColour();
      return c;
    }

    /** The editors' line number colour (the dark theme sets its own). */
    void followColour() {
      setForeground(EditorsUI.getSharedTextColors().getColor(PreferenceConverter.getColor(
          EditorsUI.getPreferenceStore(), AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR)));
    }
  }

  /** Repaints the line numbers once the text's background changed (a theme switch) or their colour did. */
  private void followLineNumberColours() {
    StyledText t = viewer.getTextWidget();
    t.addPaintListener(e -> {
      RGB bg = t.getBackground().getRGB();
      if (!bg.equals(numbersBackground)) {
        numbersBackground = bg;
        if (numbers != null && numbers.getControl() != null && !numbers.getControl().isDisposed()) {
          numbers.redraw();
        }
      }
    });
    IPropertyChangeListener colour = e -> {
      if (AbstractDecoratedTextEditorPreferenceConstants.EDITOR_LINE_NUMBER_RULER_COLOR.equals(e.getProperty())
          && numbers != null && numbers.getControl() != null && !numbers.getControl().isDisposed()) {
        numbers.followColour();
        numbers.redraw();
      }
    };
    EditorsUI.getPreferenceStore().addPropertyChangeListener(colour);
    t.addDisposeListener(e -> EditorsUI.getPreferenceStore().removePropertyChangeListener(colour));
  }

  /** The ruler the viewer was built on: the last one asked for (a Java editor that fails asks again). */
  private CompositeRuler ruler;

  /** Line numbers; in the result also the column of origins and badges, right of them (block-states design §1). */
  private CompositeRuler ruler(boolean result) {
    CompositeRuler r = new CompositeRuler();
    this.ruler = r;
    numbers = new LineNumbers();
    r.addDecorator(0, numbers);
    if (result) {
      // the viewer is built on the last ruler asked for: a Java editor that fails asks again for the plain one
      originColumn = new OriginColumn();
      r.addDecorator(1, originColumn);
    }
    return r;
  }

  CompositeRuler ruler() {
    return ruler;
  }

  /** The merge-panes font in the text, the line numbers and the origin column (preferences design §2). */
  void applyFont() {
    Font f = MergeFont.get();
    StyledText t = text();
    if (t.isDisposed()) {
      return;
    }
    if (!f.equals(t.getFont())) {
      t.setFont(f);
    }
    if (ruler != null && ruler.getControl() != null && !ruler.getControl().isDisposed()) {
      ruler.getControl().setFont(f); // the ruler's own control: its columns get the font only through setFont
      ruler.setFont(f); // every column of the ruler, also the origin column (its badge font derives from it)
    }
  }

  OriginColumn originColumn() {
    return originColumn;
  }

  MergePane(Composite parent, boolean editable, boolean stripeOnLeft, ViewerFactory factory) {
    super(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 5;
    setLayout(layout);
    header = new Text(this, SWT.BORDER | SWT.READ_ONLY | SWT.SINGLE);
    header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Composite body = new Composite(this, SWT.NONE);
    GridLayout bl = new GridLayout(2, false);
    bl.marginWidth = 0;
    bl.marginHeight = 0;
    bl.horizontalSpacing = 1;
    body.setLayout(bl);
    body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    ChangeStripe leftStripe = stripeOnLeft ? new ChangeStripe(body) : null;
    // no V_SCROLL: the shared slider of ScrollSync scrolls all panes
    viewer = factory.create(body, () -> ruler(editable), SWT.BORDER | SWT.MULTI | SWT.H_SCROLL);
    viewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    stripe = stripeOnLeft ? leftStripe : new ChangeStripe(body);
    stripe.setLayoutData(new GridData(SWT.CENTER, SWT.FILL, false, true));
    applyFont();
    MergeFont.watch(this, this::applyFont);
    followLineNumberColours();
    viewer.setEditable(editable);
    painter = new PanePainter(viewer.getTextWidget());
  }

  ChangeStripe stripe() {
    return stripe;
  }

  public SourceViewer viewer() {
    return viewer;
  }

  public StyledText text() {
    return viewer.getTextWidget();
  }

  int headerHeight() {
    return header.computeSize(SWT.DEFAULT, SWT.DEFAULT).y + ((GridLayout) getLayout()).verticalSpacing;
  }

  /** Y of the top of {@code line} (bottom of the last line when line == lineCount) in {@code target}'s coordinates. */
  int lineY(int line, Control target) {
    StyledText t = text();
    int count = t.getLineCount();
    int y = line < count ? t.getLinePixel(Math.max(0, line)) : t.getLinePixel(count - 1) + t.getLineHeight();
    return target.toControl(t.toDisplay(0, y)).y;
  }

  public void setHeader(String text) {
    header.setText(text);
  }

  public void setStyle(PaneStyle style) {
    setStyle(style, false);
  }

  /** {@code afterEdit}: only lines whose style changed are painted again (see {@link PanePainter#setStyle}). */
  void setStyle(PaneStyle style, boolean afterEdit) {
    painter.setStyle(style, afterEdit);
    if (originColumn != null) {
      originColumn.setGutters(style.gutters());
    }
  }

  PanePainter painter() {
    return painter;
  }

  PaneStyle style() {
    return painter.style();
  }

  public int caretLine() {
    var widget = viewer.getTextWidget();
    return widget.getLineAtOffset(widget.getCaretOffset());
  }

  public void revealLine(int line) {
    IDocument d = viewer.getDocument();
    try {
      int l = Math.max(0, Math.min(line, d.getNumberOfLines() - 1));
      int offset = d.getLineOffset(l);
      viewer.setSelectedRange(offset, 0);
      viewer.revealRange(offset, 0);
    } catch (BadLocationException e) {
      // line outside the document: nothing to reveal
    }
  }

  /** Number of '\n'-terminated lines shown (core line count). */
  int lineCount() {
    IDocument d = viewer.getDocument();
    int length = d.getLength();
    if (length == 0) {
      return 0;
    }
    int n = d.getNumberOfLines();
    try {
      return d.getChar(length - 1) == '\n' ? n - 1 : n;
    } catch (BadLocationException e) {
      return n;
    }
  }
}
