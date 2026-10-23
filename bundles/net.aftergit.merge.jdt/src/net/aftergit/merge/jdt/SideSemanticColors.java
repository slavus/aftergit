package net.aftergit.merge.jdt;

import java.util.Arrays;
import net.aftergit.merge.jdt.SemanticScanner.Kind;
import net.aftergit.merge.jdt.SemanticScanner.Tokens;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

/**
 * The Java editor's semantic colours on a read-only side pane (fields, parameters, static calls, deprecated
 * members, ...), following Java > Editor > Syntax Coloring and the theme live. The text of each document is scanned
 * once, off the UI thread; the UI thread only merges the found ranges into the viewer's presentation, on top of the
 * lexical colours. Public JDT API only. Unlike the editor, which replaces a range's style, the semantic style is merged:
 * a bold or italic lexical style stays on a semantic token (only with non-default lexical settings).
 */
public final class SideSemanticColors implements ITextPresentationListener {
  /** Family of the scanning jobs, for {@code Job.getJobManager().join}. */
  public static final Object FAMILY = new Object();

  /** Texts longer than this get no semantic colours: parsing them could take long (like the import fix's limit). */
  public static final int MAX_CHARS = 1_000_000;

  private static volatile int maxChars = MAX_CHARS;

  /** Replaces the size limit; a value below 0 restores {@link #MAX_CHARS}. */
  public static void setMaxCharsForTests(int chars) {
    maxChars = chars < 0 ? MAX_CHARS : chars;
  }

  private final SourceViewer viewer;
  private final IFile file;
  private final IPreferenceStore store;
  private final Display display;
  private final IPropertyChangeListener preferences = this::preferenceChanged;
  private final IDocumentListener edits = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
      // the ranges go once it has changed
    }

    @Override
    public void documentChanged(DocumentEvent event) {
      forget(); // a side never changes; should it, stale ranges would colour the wrong words
      invalidate();
    }
  };
  private boolean enabled;
  private boolean disposed;
  private IDocument document;
  private Tokens tokens = Tokens.NONE;
  private boolean scanned;
  private Scan scan;
  /** The style of each kind, null when it is disabled; read on the UI thread only. */
  private StyleRange[] styles;

  private SideSemanticColors(SourceViewer viewer, IFile file, IPreferenceStore store, boolean enabled) {
    this.viewer = viewer;
    this.file = file;
    this.store = store;
    this.display = viewer.getTextWidget().getDisplay();
    this.enabled = enabled;
    this.styles = styles(store);
  }

  /**
   * Semantic colours for the side pane {@code viewer} showing texts of {@code file}, read from {@code store} (the
   * Java editor's preference chain); {@code enabled} is the AfterGit switch. Call on the UI thread, before the viewer
   * gets its document; {@link #dispose} stops it.
   */
  public static SideSemanticColors install(SourceViewer viewer, IFile file, IPreferenceStore store, boolean enabled) {
    SideSemanticColors c = new SideSemanticColors(viewer, file, store, enabled);
    viewer.addTextPresentationListener(c); // installed with the viewer: first, under every other style
    viewer.addTextInputListener(new ITextInputListener() {
      @Override
      public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
        c.unhook();
      }

      @Override
      public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
        c.hook(newInput);
      }
    });
    store.addPropertyChangeListener(c.preferences);
    c.hook(viewer.getDocument());
    return c;
  }

  /** The AfterGit switch: off drops the colours at once, on brings them back (scanning first if never scanned). */
  public void setEnabled(boolean on) {
    if (disposed || on == enabled) {
      return;
    }
    enabled = on;
    if (on && !scanned) {
      start();
    }
    invalidate();
  }

  /** Stops the scan and the listening; the viewer may already be disposed. */
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    store.removePropertyChangeListener(preferences);
    unhook();
    viewer.removeTextPresentationListener(this);
  }

  private void hook(IDocument d) {
    if (disposed) {
      return;
    }
    document = d;
    if (d != null) {
      d.addDocumentListener(edits);
      if (enabled) {
        start();
      }
    }
  }

  private void unhook() {
    if (document != null) {
      document.removeDocumentListener(edits);
    }
    document = null;
    forget();
  }

  private void forget() {
    if (scan != null) {
      scan.cancel();
      scan = null;
    }
    tokens = Tokens.NONE;
    scanned = false;
  }

  private void start() {
    IDocument d = document;
    if (d == null || scan != null || scanned) {
      return;
    }
    if (d.getLength() > maxChars) { // before copying the text on the UI thread
      scanned = true; // nothing to scan for good: the lexical colours stay alone
      return;
    }
    String text = d.get();
    Scan[] self = new Scan[1];
    Scan s = new Scan(file, text, display, result -> {
      if (disposed || document != d || scan != self[0]) {
        return; // a stale answer: the pane closed or shows another text
      }
      scan = null;
      scanned = true;
      tokens = result;
      invalidate();
    });
    self[0] = s;
    scan = s;
    s.schedule();
  }

  private void invalidate() {
    StyledText t = viewer.getTextWidget();
    if (t != null && !t.isDisposed() && viewer.getDocument() != null) {
      viewer.invalidateTextPresentation();
    }
  }

  private void preferenceChanged(PropertyChangeEvent e) {
    if (!e.getProperty().startsWith(PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_PREFIX)) {
      return;
    }
    Runnable apply = () -> {
      if (!disposed) {
        styles = styles(store);
        invalidate();
      }
    };
    if (Display.getCurrent() != null) {
      apply.run();
    } else if (!display.isDisposed()) {
      display.asyncExec(apply);
    }
  }

  /** The style of each kind as the Java editor makes it (colour, bold, italic, strikethrough, underline). */
  private static StyleRange[] styles(IPreferenceStore store) {
    Kind[] kinds = Kind.values();
    StyleRange[] out = new StyleRange[kinds.length];
    for (Kind k : kinds) {
      String p = PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_PREFIX + k.key;
      if (!store.getBoolean(p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX)) {
        continue;
      }
      StyleRange r = new StyleRange();
      String colorKey = p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_COLOR_SUFFIX;
      if (store.contains(colorKey)) {
        RGB rgb = PreferenceConverter.getColor(store, colorKey);
        Color c = JavaUI.getColorManager().getColor(rgb);
        r.foreground = c;
      }
      int style = SWT.NORMAL;
      if (store.getBoolean(p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_BOLD_SUFFIX)) {
        style |= SWT.BOLD;
      }
      if (store.getBoolean(p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ITALIC_SUFFIX)) {
        style |= SWT.ITALIC;
      }
      r.fontStyle = style;
      r.strikeout = store.getBoolean(p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_STRIKETHROUGH_SUFFIX);
      r.underline = store.getBoolean(p + PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_UNDERLINE_SUFFIX);
      out[k.ordinal()] = r;
    }
    return out;
  }

  /** The style of the first enabled kind in {@code kinds}, or null. */
  private StyleRange styleOf(int kinds) {
    for (int i = 0; i < styles.length; i++) {
      if ((kinds & (1 << i)) != 0 && styles[i] != null) {
        return styles[i];
      }
    }
    return null;
  }

  @Override
  public void applyTextPresentation(TextPresentation presentation) {
    Tokens t = tokens;
    if (!enabled || disposed || t.size() == 0) {
      return;
    }
    long start = System.nanoTime();
    IRegion extent = presentation.getExtent();
    int from = extent == null ? 0 : extent.getOffset();
    int to = extent == null ? Integer.MAX_VALUE : from + extent.getLength();
    int i = Arrays.binarySearch(t.offsets(), from);
    i = i < 0 ? Math.max(0, -i - 2) : i;
    int first = i;
    int last = first;
    while (last < t.size() && t.offsets()[last] < to) {
      last++;
    }
    StyleRange[] ranges = new StyleRange[last - first];
    int n = 0;
    for (; i < last; i++) {
      int s = Math.max(t.offsets()[i], from);
      int e = Math.min(t.offsets()[i] + t.lengths()[i], to);
      StyleRange style = e > s ? styleOf(t.kinds()[i]) : null;
      if (style != null) {
        StyleRange r = (StyleRange) style.clone();
        r.start = s;
        r.length = e - s;
        ranges[n++] = r;
      }
    }
    if (n > 0) {
      // one pass over the presentation (sorted, not overlapping): merging range by range moves its list each time
      presentation.mergeStyleRanges(n == ranges.length ? ranges : Arrays.copyOf(ranges, n));
    }
    JdtTestHooks.semanticApplied(System.nanoTime() - start, n);
  }

  /** The scan of one text. */
  private static final class Scan extends Job {
    private final IFile file;
    private final String text;
    private final Display display;
    private final java.util.function.Consumer<Tokens> done;

    Scan(IFile file, String text, Display display, java.util.function.Consumer<Tokens> done) {
      super("Semantic colors of a side");
      setSystem(true);
      setPriority(DECORATE);
      this.file = file;
      this.text = text;
      this.display = display;
      this.done = done;
    }

    @Override
    public boolean belongsTo(Object family) {
      return family == FAMILY;
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
      Tokens result;
      try {
        java.util.function.Consumer<IProgressMonitor> hook = JdtTestHooks.semanticScanHook();
        if (hook != null) {
          hook.accept(monitor);
        }
        result = SemanticScanner.scan(file, text, monitor);
      } catch (OperationCanceledException e) {
        return Status.CANCEL_STATUS;
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        // no semantic colours for this text, never an error dialog: the lexical ones stay
        ILog.of(SideSemanticColors.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
            "No semantic colors for a side of " + (file == null ? "a file" : file.getFullPath()),
            e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e));
        return Status.OK_STATUS;
      }
      if (monitor.isCanceled() || display.isDisposed()) {
        return Status.CANCEL_STATUS;
      }
      try {
        display.asyncExec(() -> done.accept(result));
      } catch (org.eclipse.swt.SWTException e) {
        return Status.CANCEL_STATUS; // the display went away meanwhile (shutdown)
      }
      return Status.OK_STATUS;
    }
  }
}
