package net.aftergit.merge.lsp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension4;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.model.ITMModel;
import org.eclipse.tm4e.core.model.ModelTokensChangedEvent;
import org.eclipse.tm4e.core.model.TMModel;
import org.eclipse.tm4e.core.model.TMToken;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.model.ITMModelManager;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.tm4e.ui.themes.ITokenProvider;

/**
 * tm4e's reconciler for one pane: the grammar of the file forced (the pane's document has no file buffer tm4e could
 * find it by), the theme for dark or light as the resolver opened (Ruling 4), wrapped so that it never takes over the
 * pane's look (Rulings 5, 6). tm4e tokenizes on its own thread and colours through changeTextPresentation, under the
 * presentation listeners of AfterGit (the words changed inside lines). A line longer than
 * {@link TextMatePanes#MAX_LINE_CHARS}, or with more tokens times characters than {@link TextMatePanes#MAX_LINE_COST},
 * stays plain: SWT lays such a line out again for every style range of it, which stalls the UI thread.
 */
final class PaneReconciler extends TMPresentationReconciler {
  private final String scope;
  private final boolean dark;
  private volatile boolean coloured;
  private volatile PaneTokens tokens;
  private ITextViewer installed;
  private final ITextPresentationListener longLines = this::plainLongLines;
  /** A model of another user kept out of tm4e's sight while tm4e lets go of the document (an input change). */
  private ITMModel hidden;
  /** The document {@link #hidden} belongs to. */
  private IDocument hiddenOf;
  /** Runs before tm4e's own input listener, which the pane's document changes also go to. */
  private final ITextInputListener modelWatch = new ITextInputListener() {
    @Override
    public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
      restoreHidden(); // a change that failed half-way never keeps another user's model out for good
      ITMModel h = hide(oldInput);
      if (h != null) {
        hidden = h;
        hiddenOf = oldInput;
      }
    }

    @Override
    public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
      restoreHidden(); // before tm4e connects the new input: the same document finds its model again
    }
  };

  PaneReconciler(IGrammar grammar, boolean dark, boolean coloured) {
    Runnable hook = LspTestHooks.reconcilerHook();
    if (hook != null) {
      hook.run();
    }
    this.scope = grammar.getScopeName();
    this.dark = dark;
    this.coloured = coloured;
    setGrammar(grammar);
    // before install: with a theme already set, tm4e does not choose an ITheme of its own (Ruling 6)
    setTheme(TMUIPlugin.getThemeManager().getThemeForScope(scope, dark));
  }

  @Override
  public void setTheme(ITokenProvider theme) {
    if (theme instanceof PaneTokens own) {
      tokens = own;
      super.setTheme(own);
      return;
    }
    // tm4e's listener of the TextMate theme preferences passes the theme it chose for Eclipse's theme of now
    PaneTokens own = new PaneTokens(TMUIPlugin.getThemeManager().getThemeForScope(scope, dark), coloured);
    tokens = own;
    super.setTheme(own);
  }

  @Override
  public void install(ITextViewer viewer) {
    installed = viewer;
    if (viewer instanceof ITextViewerExtension4 v) {
      v.addTextPresentationListener(longLines); // before AfterGit's own: the changed words stay on top
    }
    viewer.addTextInputListener(modelWatch); // before tm4e's: it sees the document before tm4e connects it
    super.install(viewer);
  }

  /**
   * tm4e keeps one model per document and lets go of it, disposing it, whenever any of its users lets go of the
   * document (TMModelManager.disconnect, called by TMPresentationReconciler$TextViewerListener; checked with javap).
   * The shared result's document has other users: an editor of the file, whichever opened first, or tm4e's
   * partitioner of the file buffer. When this pane lets go, its own tokens listener leaves the model; if the model
   * has listeners left (TMModel.listeners, read by reflection: tm4e has no API for it), it leaves tm4e's map of
   * models (a ConcurrentHashMap) while tm4e lets go, so that it stays theirs. Returns that model, or null when
   * nobody else uses it (tm4e then disposes it). When another user lets go first, tm4e disposes the model under this
   * pane and connects a new one on the pane's next change, as for any viewer of tm4e.
   */
  private ITMModel hide(IDocument d) {
    ITMModel m = connectedModel(d);
    if (m == null) {
      return null;
    }
    try {
      removeOurListener(m);
      if (listenerCount(m) > 0 && models().remove(d, m)) {
        return m;
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      logModelsUnreachable(e); // tm4e disposes it: another user's reconciler connects a new one on its next change
    }
    return null;
  }

  /** Puts a model hidden by an input change back, if one is still out. */
  private void restoreHidden() {
    ITMModel h = hidden;
    IDocument d = hiddenOf;
    hidden = null;
    hiddenOf = null;
    if (h != null) {
      restore(d, h);
    }
  }

  /**
   * Puts {@code model} back as tm4e's model of {@code d}. If that fails, or another model took its place meanwhile,
   * the model is disposed: one tm4e no longer knows would keep its document listener and tokenizer for good.
   */
  private static void restore(IDocument d, ITMModel model) {
    Object there;
    try {
      there = models().putIfAbsent(d, model);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      logModelsUnreachable(e);
      model.dispose();
      return;
    }
    if (there != null && there != model) {
      model.dispose();
    }
  }

  /** tm4e's model of {@code d} if it has one, else null; never connects one. */
  private static ITMModel connectedModel(IDocument d) {
    ITMModelManager models = TMUIPlugin.getTMModelManager();
    return d != null && models.isConnected(d) ? models.connect(d) : null;
  }

  /** The tokens listener of this reconciler leaves {@code model}. */
  private void removeOurListener(ITMModel model) throws ReflectiveOperationException {
    Field f = TMPresentationReconciler.class.getDeclaredField("modelsTokensChangedListener");
    f.setAccessible(true);
    if (f.get(this) instanceof ModelTokensChangedEvent.Listener l && registered(model, l)) {
      model.removeModelTokensChangedListener(l); // tm4e logs a WARNING for a listener it does not have
    }
  }

  /**
   * {@code l} is among the tokens listeners of {@code model} (TMModel.listeners, AbstractListeners.listeners: a set;
   * read by reflection, tm4e has no API for it). The tokens listener is added when tm4e connects the model, and a
   * pane that never got one, or lost it with an earlier model, has nothing to remove.
   */
  private static boolean registered(ITMModel model, ModelTokensChangedEvent.Listener l)
      throws ReflectiveOperationException {
    Object listeners = listeners(model);
    Class<?> c = listeners.getClass();
    while (c != null && !c.getSimpleName().equals("AbstractListeners")) {
      c = c.getSuperclass();
    }
    if (c == null) {
      return true; // not readable: let tm4e decide, as before
    }
    Field set = c.getDeclaredField("listeners");
    set.setAccessible(true);
    return ((java.util.Collection<?>) set.get(listeners)).contains(l);
  }

  private static Object listeners(ITMModel model) throws ReflectiveOperationException {
    Field f = TMModel.class.getDeclaredField("listeners");
    f.setAccessible(true);
    return f.get(model);
  }

  /** The tokens listeners of {@code model}: its users that colour or partition from it. */
  private static int listenerCount(ITMModel model) throws ReflectiveOperationException {
    Object listeners = listeners(model);
    Method count = listeners.getClass().getMethod("count");
    return ((Number) count.invoke(listeners)).intValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<IDocument, Object> models() throws ReflectiveOperationException {
    Object manager = TMUIPlugin.getTMModelManager();
    Field f = manager.getClass().getDeclaredField("models");
    f.setAccessible(true);
    return (Map<IDocument, Object>) f.get(manager);
  }

  private static final AtomicBoolean modelsLogged = new AtomicBoolean();

  private static void logModelsUnreachable(Throwable e) {
    if (modelsLogged.compareAndSet(false, true)) {
      ILog.of(PaneReconciler.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The TextMate model of a "
          + "document shared with an editor could not be kept for the editor; its colours return on its next change",
          Grammars.loggable(e)));
    }
  }

  /** Leaves every line of {@code p} longer than the limit, or too dense, without TextMate colours. */
  private void plainLongLines(TextPresentation p) {
    ITextViewer v = installed;
    IDocument d = v == null ? null : v.getDocument();
    IRegion extent = p.getExtent();
    if (d == null || extent == null) {
      return;
    }
    int start = extent.getOffset();
    int end = Math.min(start + extent.getLength(), d.getLength());
    try {
      int limit = TextMatePanes.maxLineChars();
      long costLimit = TextMatePanes.MAX_LINE_COST;
      // the model tm4e colours from; its tokens are the whole line's, however little of it p covers
      ITMModelManager models = TMUIPlugin.getTMModelManager();
      ITMModel model = models.isConnected(d) ? models.connect(d) : null;
      for (int line = d.getLineOfOffset(start), last = d.getLineOfOffset(end); line <= last; line++) {
        String delimiter = d.getLineDelimiter(line);
        int length = d.getLineLength(line); // with the delimiter, which the limit does not count
        int chars = length - (delimiter == null ? 0 : delimiter.length());
        if (chars > limit || tooDense(model, line, chars, costLimit)) {
          int from = Math.max(start, d.getLineOffset(line));
          int to = Math.min(end, d.getLineOffset(line) + length);
          if (to > from) {
            p.replaceStyleRange(new StyleRange(from, to - from, null, null));
          }
        }
      }
    } catch (BadLocationException e) {
      // the document changed under the presentation: the next one is computed for the new text
    }
  }

  /** The line's tokens times its characters are above {@code costLimit}. */
  private static boolean tooDense(ITMModel model, int line, int chars, long costLimit) {
    if (model == null || (long) chars * chars <= costLimit) {
      return false; // even a token per character stays under the limit
    }
    List<TMToken> tokens = model.getLineTokens(line);
    return tokens != null && (long) tokens.size() * chars > costLimit;
  }

  @Override
  public void uninstall() {
    ITextViewer v = installed;
    if (v == null) {
      return; // already uninstalled (the viewer's dispose and TextMatePanes.dispose both call it)
    }
    installed = null;
    if (v instanceof ITextViewerExtension4 e) {
      e.removeTextPresentationListener(longLines);
    }
    v.removeTextInputListener(modelWatch);
    restoreHidden();
    IDocument d = v.getDocument();
    ITMModel kept = hide(d); // a model other users of the shared document have stays theirs
    try {
      super.uninstall();
    } finally {
      if (kept != null) {
        restore(d, kept);
      } else if (d != null && TMUIPlugin.getTMModelManager().isConnected(d)) {
        TMUIPlugin.getTMModelManager().disconnect(d); // our own model: its tokenizer thread ends with it
      }
    }
  }

  /** The switch: plain text or the theme's colours, at once in the viewer. UI thread. */
  void setColoured(boolean on) {
    coloured = on;
    PaneTokens t = tokens;
    if (t != null) {
      t.setEnabled(on);
    }
    ITextViewer v = installed;
    StyledText w = v == null ? null : v.getTextWidget();
    if (w != null && !w.isDisposed() && v.getDocument() != null) {
      v.invalidateTextPresentation(); // tm4e colours the whole document again with the switched tokens
    }
  }
}
