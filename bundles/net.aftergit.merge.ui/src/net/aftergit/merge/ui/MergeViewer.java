package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.PlatformUI;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.ai.AiRequests;
import net.aftergit.merge.core.check.CompileCheck;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;

/** The three-pane resolver widget: toolbar, ours | result | theirs, status line. */
public final class MergeViewer extends Viewer implements IAdaptable {
  private final Composite root;
  private final MergePane left;
  private final MergePane result;
  private final MergePane right;
  private final ConnectorCanvas leftConnector;
  private final ConnectorCanvas rightConnector;
  private final Composite panes;
  private final MergeLayout layout;
  private final ScrollSync scrollSync;
  private final Composite statusBar;
  private final BarText position;
  private final BarText status;
  private final PaneSetup setup;
  private final BarText notice;
  private IDocument listenedResult;
  /** Line delimiter of the result document. */
  private String resultEol = "\n";
  private final IDocumentListener resultListener = new IDocumentListener() {
    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {}

    @Override
    public void documentChanged(DocumentEvent event) {
      documentChanges++;
      String typed = event.getText();
      // the marks move with the text until the refresh below places them again
      result.painter().shiftInsertions(event.getOffset(), event.getLength(), typed == null ? 0 : typed.length());
      fireDirty();
      if (!refreshScheduled) {
        refreshScheduled = true;
        root.getDisplay().asyncExec(() -> {
          refreshScheduled = false;
          // an action refreshes synchronously right after its edits: nothing left to do then
          if (!root.isDisposed() && refreshedAtChange != documentChanges) {
            refreshingAfterEdit = true;
            try {
              refresh();
            } finally {
              refreshingAfterEdit = false;
            }
          }
        });
      }
    }
  };
  private final Runnable modelListener = () -> {
    forgetOverridden();
    fireDirty();
  };
  private Runnable saveAndMarkResolvedHook = () -> {};
  private final List<Runnable> dirtyListeners = new CopyOnWriteArrayList<>();
  private MergeSession session;
  /** The project of the file shown; null in a viewer without a file (tests). */
  private IProject project;
  /** Proposals of agents that wait for the user, by block index (agents design §3). */
  private final Map<Integer, Proposal> pending = new TreeMap<>();
  /** Proposals kept waiting while the resolver was busy (a save); offered again by {@link #releaseHeld()}. */
  private final List<Proposal> held = new ArrayList<>();
  /** Proposals the user was already shown once (stored, offered again): they wait, never applied at once. */
  private final java.util.Set<Proposal> waitOnly = new java.util.HashSet<>();
  private boolean agentsActive;
  /** Answers of the AI resolvers (resolve-with-ai design §3), told apart from the agents' proposals by identity. */
  private final java.util.Set<Proposal> aiProposals = Collections.newSetFromMap(new IdentityHashMap<>());
  /** Blocks whose applied text came from an AI resolver: their revert tells no agents' store. */
  private final java.util.Set<BlockId> aiApplied = new java.util.HashSet<>();
  /** The AI_RESOLVED blocks by index as the model last showed them, to see the user override one. */
  private final Map<Integer, BlockId> aiResolved = new java.util.HashMap<>();
  /** Agents' proposals the user reverted or overrode (take, ignore, typing): the store was told once (design §3). */
  private final java.util.Set<BlockId> overridden = new java.util.HashSet<>();
  /** True while the language integration adjusts the result after a change: what it edits is no override. */
  private boolean derivedChange;
  /** True while an AI resolver is ready (design §1); hidden, none of their answers waits. */
  private boolean aiActive;
  private Consumer<BlockId> proposalForgetter = id -> {};
  /** True while a save or a dialog of the editor runs: waiting proposals are then neither applied nor discarded. */
  private BooleanSupplier agentsHeld = () -> false;
  private ResultDocumentAdapter adapter;
  private UndoStack undo;
  private final List<Runnable> undoListeners = new CopyOnWriteArrayList<>();
  private PaneKind focused = PaneKind.RESULT;
  private int selectedBlock = -1;
  private boolean programmaticMove;
  private boolean refreshScheduled;
  private final java.util.Map<PaneKind, InnerLineHighlighter> highlighters = new java.util.EnumMap<>(PaneKind.class);
  private boolean innerLineEnabled = true;
  private final MergeToolbar toolbar;
  private final AiActions ai;
  private InnerLine.Ranges[] inner = new InnerLine.Ranges[0];
  /** Where each block's result came from, as of the last refresh (block-states design §1). */
  private PaneStyle.Origin[] origins = new PaneStyle.Origin[0];
  /** Word ranges of each conflict, by block index; side and base texts never change for an input. */
  private InnerLine.Ranges[] innerCache = new InnerLine.Ranges[0];
  /** A resolved block's sides compared with its result, kept until one of the three texts changes. */
  private record Compared(String left, String result, String right, InnerLine.Ranges ranges) {}
  private Compared[] compared = new Compared[0];
  /**
   * Above this many lines in one of its texts a resolved block is not compared with its result, and above this many
   * characters in its three texts together: a cheap bound checked before any word is split, so that typing in a large
   * block stays quick. Below it {@link net.aftergit.merge.core.merge.WordMerge#MAX_TOKENS} still applies.
   */
  static final int MAX_COMPARED_LINES = 5_000;
  static final int MAX_COMPARED_CHARS = 300_000;
  /** While typing, a block is compared with its result again at most this often (ms); for tests settable. */
  private int compareDelay = 150;
  /** When a refresh last compared a block with its result (System.nanoTime), 0 for not since the last pause. */
  private long lastComparison;
  /** This refresh may compare blocks with their results: no typing within {@link #compareDelay}. */
  private boolean compareNow = true;
  /** True when this refresh left a resolved block's comparison for later: the result pane keeps its shifted words. */
  private boolean comparisonDeferred;
  private boolean comparisonTimer;
  /** Changed words of the side panes by block, for blocks that are not open conflicts; cleared with each input. */
  private final java.util.Map<Integer, List<int[]>> leftWords = new java.util.HashMap<>();
  private final java.util.Map<Integer, List<int[]>> rightWords = new java.util.HashMap<>();
  private int decorationRedraws;
  private int sideWordComputations;
  private int innerLineComputations;
  private int resultComparisons;
  private int refreshCount;
  /** The refresh that follows an edit of the result: colours and appearance are as before (PanePainter). */
  private boolean refreshingAfterEdit;
  /** Result document changes so far, and their number when the last refresh ran. */
  private long documentChanges;
  private long refreshedAtChange = -1;

  private static final String COLOR_PREFIX = "net.aftergit.merge.color.";
  private final org.eclipse.swt.widgets.Display display;
  private org.eclipse.jface.resource.ColorRegistry watchedRegistry;
  private final org.eclipse.jface.util.IPropertyChangeListener colorListener = e -> {
    if (e.getProperty() != null && e.getProperty().startsWith(COLOR_PREFIX)) {
      onThemeChanged();
    }
  };
  /** The error frames use the editors' error annotation color, which has its own preference. */
  private final org.eclipse.jface.util.IPropertyChangeListener errorColorListener = e -> {
    if (Palette.ERROR_COLOR_KEY.equals(e.getProperty())) {
      onThemeChanged(); // posts to the UI thread: a preference may change on any thread
    }
  };
  private final org.eclipse.jface.util.IPropertyChangeListener themeListener = e -> {
    if (org.eclipse.ui.themes.IThemeManager.CHANGE_CURRENT_THEME.equals(e.getProperty())) {
      watchRegistry();
    }
    onThemeChanged();
  };

  /** The structure tree of the session (structure-compare design §1), shared by the panel and the Outline. */
  private final StructureController structure;
  private final StructurePanel structurePanel;
  private final Sash structureSash;
  private final List<java.util.function.IntConsumer> currentBlockListeners = new CopyOnWriteArrayList<>();

  public MergeViewer(Composite parent) {
    this(parent, new PlainPaneSetup());
  }

  MergeViewer(Composite parent, PaneSetup setup) {
    ColorMigration.ensure();
    this.setup = setup;
    ai = new AiActions(this);
    root = new Composite(parent, SWT.NONE);
    display = root.getDisplay();
    structure = new StructureController(display);
    root.setLayout(new GridLayout(1, false));
    layout = new MergeLayout(MergePreferences.layout());
    layout.setConnectorWidth(Appearance.connector().pixels());
    innerLineEnabled = MergePreferences.innerLine();
    toolbar = new MergeToolbar(root, this);
    toolbar.control().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Composite panes = new Composite(root, SWT.NONE);
    panes.setLayout(layout);
    panes.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    left = new MergePane(panes, false, true, viewerFactory(PaneKind.LEFT));
    result = new MergePane(panes, true, false, viewerFactory(PaneKind.RESULT));
    right = new MergePane(panes, false, false, viewerFactory(PaneKind.RIGHT));
    ai.installResultMenu(result.text());
    leftConnector = new ConnectorCanvas(panes, this, PaneKind.LEFT);
    leftConnector.setLayoutData(MergeLayout.Slot.LEFT_CONNECTOR);
    rightConnector = new ConnectorCanvas(panes, this, PaneKind.RIGHT);
    rightConnector.setLayoutData(MergeLayout.Slot.RIGHT_CONNECTOR);
    left.setLayoutData(MergeLayout.Slot.LEFT);
    result.setLayoutData(MergeLayout.Slot.RESULT);
    right.setLayoutData(MergeLayout.Slot.RIGHT);
    Slider slider = new Slider(panes, SWT.VERTICAL);
    slider.setLayoutData(MergeLayout.Slot.SLIDER);
    Sash sash = new Sash(panes, SWT.HORIZONTAL);
    sash.setLayoutData(MergeLayout.Slot.SASH);
    this.panes = panes;
    structurePanel = new StructurePanel(panes, this);
    structurePanel.control().setLayoutData(MergeLayout.Slot.STRUCTURE);
    structureSash = new Sash(panes, SWT.VERTICAL);
    structureSash.setLayoutData(MergeLayout.Slot.STRUCTURE_SASH);
    layout.setStructureWidth(MergePreferences.structureWidth());
    scrollSync = new ScrollSync(slider, this);
    scrollSync.addScrollListener(this::redrawDecorations);
    sash.addListener(SWT.Selection, e -> {
      var area = panes.getClientArea();
      layout.setRatio(e.y / (double) Math.max(1, area.height - MergeLayout.SASH));
      panes.layout(true);
      scrollSync.rebuild();
    });
    AccessibleNames.set(structureSash, child -> child == ACC.CHILDID_SELF ? Messages.structureResize : null);
    structureSash.addListener(SWT.Selection, e -> {
      var area = panes.getClientArea();
      int width = Math.max(MergeLayout.STRUCTURE_MIN, MergeLayout.structureWidth(e.x - area.x, area.width));
      e.x = area.x + width; // the drag feedback stops at the limits; no wider width is remembered (Ruling 12)
      layout.setStructureWidth(width);
      if (e.detail != SWT.DRAG) {
        MergePreferences.setStructureWidth(layout.structureWidth()); // remembered once released (Ruling 12)
      }
      panes.layout(true);
      scrollSync.rebuild();
      redrawDecorations();
    });
    for (PaneKind k : PaneKind.values()) {
      pane(k).stripe().setSource(this, k);
    }
    for (PaneKind k : PaneKind.values()) {
      InnerLineHighlighter h = new InnerLineHighlighter(pane(k).viewer());
      h.setReconcilerInstalled(setup.presentationReconciled(k));
      highlighters.put(k, h);
    }
    trackFocus(left, PaneKind.LEFT);
    trackFocus(result, PaneKind.RESULT);
    trackFocus(right, PaneKind.RIGHT);

    statusBar = new Composite(root, SWT.NONE);
    GridLayout sl = new GridLayout(3, false);
    sl.marginHeight = 2;
    sl.horizontalSpacing = 16; // the position and the counts read as two parts (block-states mockup)
    statusBar.setLayout(sl);
    statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    // the theme CSS paints plain composites of the editor white after creation: the bar draws its state itself
    statusBar.addListener(SWT.Paint, e -> {
      if (barColor != null) {
        e.gc.setBackground(barColor);
        e.gc.fillRectangle(e.gc.getClipping());
      }
    });
    position = new BarText(statusBar, false); // where the user is (block-states design §3); the counts follow
    GridData positionData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    positionData.exclude = true; // no current block yet: the counts start at the left edge
    position.setLayoutData(positionData);
    position.setVisible(false);
    status = new BarText(statusBar, false);
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    notice = new BarText(statusBar, true);
    showNotice(setup.notice(), null);
    if (PlatformUI.isWorkbenchRunning()) {
      PlatformUI.getWorkbench().getThemeManager().addPropertyChangeListener(themeListener);
      watchRegistry();
    }
    org.eclipse.ui.editors.text.EditorsUI.getPreferenceStore().addPropertyChangeListener(errorColorListener);
    // the page writes it too (preferences design §4): every open resolver follows
    Runnable stopInnerLine = MergePreferences.onChange(MergePreferences.INNER_LINE, () -> {
      if (!display.isDisposed()) {
        display.asyncExec(this::followInnerLine);
      }
    });
    MergeFont.watch(root, this::fontChanged);
    Runnable stopAppearance = Appearance.onChange(() -> {
      if (!display.isDisposed()) {
        display.asyncExec(this::appearanceChanged);
      }
    });
    root.addDisposeListener(e -> {
      stopInnerLine.run();
      stopAppearance.run();
      ai.close();
      org.eclipse.ui.editors.text.EditorsUI.getPreferenceStore().removePropertyChangeListener(errorColorListener);
      if (PlatformUI.isWorkbenchRunning()) {
        PlatformUI.getWorkbench().getThemeManager().removePropertyChangeListener(themeListener);
      }
      if (watchedRegistry != null) {
        watchedRegistry.removeListener(colorListener);
        watchedRegistry = null;
      }
      structure.dispose();
      detach();
      setup.dispose(); // its problem trackers drop their listeners, ours included
    });
    // the result pane exists now; a reconcile may finish without any edit or scroll following it
    setup.addProblemListener(this::problemsChanged);
    toolbar.refreshState();
  }


  private MergePane.ViewerFactory viewerFactory(PaneKind kind) {
    return (parent, rulers, styles) -> setup.createViewer(kind, parent, rulers, styles,
        new PaneServices(new BaseTextHover(this, kind),
            kind == PaneKind.RESULT ? new UndoBridge(this) : null));
  }

  private final AtomicBoolean problemsRefreshPending = new AtomicBoolean();
  private int problemRedraws;

  /** Problems change on the reconciler thread; coalesce into one UI update of status and stripes. */
  private void problemsChanged() {
    if (display.isDisposed() || !problemsRefreshPending.compareAndSet(false, true)) {
      return;
    }
    try {
      display.asyncExec(() -> {
        problemsRefreshPending.set(false);
        if (!root.isDisposed() && session != null) {
          problemRedraws++;
          java.util.List<ResultProblem> problems = setup.problems(); // one snapshot for marks and status
          if (updateCompileMarks(problems)) {
            showCompileMarks();
          }
          status.setText(statusText(problems));
          statusBar.layout();
          redrawDecorations();
        }
      });
    } catch (org.eclipse.swt.SWTException e) {
      // the display went away between the check and the post (workbench shutdown): nothing to show any more
      problemsRefreshPending.set(false);
    }
  }

  private void watchRegistry() {
    if (watchedRegistry != null) {
      watchedRegistry.removeListener(colorListener);
    }
    watchedRegistry = Palette.registry();
    watchedRegistry.addListener(colorListener);
  }

  /** On any thread (a theme or preference change); the refresh runs on the UI thread. */
  private void onThemeChanged() {
    if (display.isDisposed()) {
      return;
    }
    try {
      display.asyncExec(() -> {
        if (!root.isDisposed()) {
          refresh();
          root.redraw();
        }
      });
    } catch (org.eclipse.swt.SWTException e) {
      // the display went away between the check and the post: nothing to repaint
    }
  }

  private void detach() {
    if (listenedResult != null) {
      listenedResult.removeDocumentListener(resultListener);
      listenedResult = null;
    }
    if (adapter != null) {
      adapter.disconnect();
      adapter = null;
      undo = null;
    }
    if (session != null) {
      session.model().removeChangeListener(modelListener);
    }
  }

  private static Action action(String text, Runnable run) {
    return new Action(text) {
      @Override
      public void run() {
        run.run();
      }
    };
  }

  /** The block under the caret of {@code kind}, -1 for none; the current block stays while it covers the line. */
  private int blockUnderCaret(PaneKind kind) {
    return session == null ? -1
        : BlockLocator.currentAt(session.model(), kind, pane(kind).caretLine(), selectedBlock);
  }

  private void trackFocus(MergePane pane, PaneKind kind) {
    pane.viewer().getTextWidget().addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        focused = kind;
        setSelectedBlock(blockUnderCaret(kind)); // block-states design §2: the block under the caret is current
        if (kind == PaneKind.RESULT) {
          resultFocus(true);
        }
        toolbar.refreshState();
      }

      @Override
      public void focusLost(FocusEvent e) {
        if (kind == PaneKind.RESULT) {
          resultFocus(false);
        }
      }
    });
    pane.viewer().getTextWidget().addCaretListener(e -> {
      if (!programmaticMove) {
        if (undo != null && kind == PaneKind.RESULT && e.caretOffset != undo.typingEnd()) {
          undo.closeGroup();
        }
        // a click or a key moved the caret into (or out of) a block; the focus event may still be queued, and
        // focused keeps the last pane while the focus is outside the viewer
        if (kind == focused || pane.viewer().getTextWidget().isFocusControl()) {
          setSelectedBlock(blockUnderCaret(kind));
        }
      }
      toolbar.refreshState(); // a programmatic move changes the current block too
    });
  }

  private void resultFocus(boolean on) {
    setup.resultFocusChanged(on);
    if (siteServices != null) {
      siteServices.setResultContext(setup.resultContextId(), on);
    }
  }

  /** CompareEditorInput adapts its content viewer, e.g. to ITextEditorExtension3 for JDT's smart insert. */
  @Override
  public <T> T getAdapter(Class<T> adapter) {
    return setup.getAdapter(adapter);
  }

  @Override
  public Control getControl() {
    return root;
  }

  @Override
  public Object getInput() {
    return session;
  }

  @Override
  public void setInput(Object input) {
    if (input == null || input instanceof ICompareInput) {
      // The compare framework re-feeds the editor's ICompareInput; the session is set explicitly.
      return;
    }
    if (!(input instanceof MergeSession)) {
      throw new IllegalArgumentException("Unexpected input " + input.getClass().getName());
    }
    ai.newSession(); // answers for the old session must not land in the new one
    detach();
    session = (MergeSession) input;
    pending.clear();
    held.clear();
    waitOnly.clear();
    aiProposals.clear();
    aiApplied.clear();
    aiResolved.clear();
    overridden.clear();
    agentsActive = false; // the editor input offers the stored proposals of the new session again
    setSelectedBlock(-1);
    MergeModel m = session.model();
    innerCache = new InnerLine.Ranges[m.blocks().size()];
    compared = new Compared[m.blocks().size()];
    leftWords.clear();
    rightWords.clear();
    programmaticMove = true; // the caret jumps to 0 with each new document: no choice of the user
    try {
      left.viewer().setDocument(setup.sideDocument(LineText.render(m.left().lines(), "\n", true)));
      right.viewer().setDocument(setup.sideDocument(LineText.render(m.right().lines(), "\n", true)));
      IDocument resultDoc = setup.resultDocument(m.document().chunk(0, m.document().lineCount()));
      resultEol = setup.resultDelimiter();
      adapter = setup.sharedBuffer() != null ? ResultDocumentAdapter.forSharedBuffer(resultDoc, resultEol)
          : new ResultDocumentAdapter(resultDoc);
      adapter.connect(m);
      undo = new UndoStack(adapter, m);
      undo.addListener(this::fireUndoChanged);
      fireUndoChanged(); // handlers bound to a previous stack follow the new one
      setup.showResult(result.viewer(), resultDoc);
      resultDoc.addDocumentListener(resultListener);
      listenedResult = resultDoc;
    } finally {
      programmaticMove = false;
    }
    forgetOverridden(); // only notes the AI-resolved blocks the new session starts with
    m.addChangeListener(modelListener);
    left.setHeader(session.labels().left());
    right.setHeader(session.labels().right());
    result.setHeader(String.format(Messages.resultTitle, Messages.resultTitlePending));
    showNotice(setup.notice(), session.notice());
    refresh();
    structure.setSession(session); // after the refresh: its change needs no second computation
    showStructure(MergePreferences.structureShown() && structureAvailable());
  }

  private void showNotice(String setupNotice, String sessionNotice) {
    String n = joinNotices(setupNotice, sessionNotice);
    notice.setText(n == null ? "" : n);
    notice.setToolTipText(n);
    if (statusBar != null) {
      statusBar.layout(true);
    }
  }

  /** Notices for the status line joined with a middle dot; null when there is none. */
  static String joinNotices(String a, String b) {
    boolean hasA = a != null && !a.isEmpty();
    boolean hasB = b != null && !b.isEmpty();
    if (hasA && hasB) {
      return a + " \u00b7 " + b;
    }
    return hasA ? a : hasB ? b : null;
  }

  SharedBuffer sharedBuffer() {
    return setup.sharedBuffer();
  }

  /**
   * Writes {@code fileText} through the shared buffer. With {@code keepResult} (unresolved blocks saved as
   * markers) the result shows its own text again afterwards and the buffer stays clean, although it differs from
   * the file. Without it the buffer keeps the file text, which differs from the result at most in line ends.
   */
  void commitResult(String fileText, boolean keepResult, IProgressMonitor monitor) throws CoreException {
    commitResult(fileText, keepResult, false, monitor);
  }

  /** Like {@link #commitResult(String, boolean, IProgressMonitor)}; {@code saveActions}: through the Java Save Actions. */
  void commitResult(String fileText, boolean keepResult, boolean saveActions, IProgressMonitor monitor)
      throws CoreException {
    FileText shown = showFileText(fileText, keepResult);
    boolean committed = false;
    try {
      setup.sharedBuffer().commit(monitor, saveActions);
      committed = true;
    } finally {
      shown.done(committed && keepResult);
    }
  }

  /** The shared document holding the text a commit writes; {@link #done} shows the result again. */
  interface FileText {
    /** After the commit; {@code saved}: the buffer stays clean although the result differs from the file. */
    void done(boolean saved);
  }

  /**
   * Puts {@code fileText} into the shared document for a commit, without the model seeing it as an edit. With
   * {@code keepResult} the returned {@link FileText#done} puts the result text back.
   */
  FileText showFileText(String fileText, boolean keepResult) {
    SharedBuffer b = setup.sharedBuffer();
    IDocument doc = adapter.document();
    String shown = doc.get();
    // other editors of the file record both replacements as one step that changes nothing
    IDocumentUndoManager others = keepResult ? DocumentUndoManagerRegistry.getDocumentUndoManager(doc) : null;
    if (others != null) {
      others.beginCompoundChange();
    }
    try {
      frozen(() -> adapter.runUntracked(() -> TextSpans.replaceMinimal(doc, fileText)));
    } catch (RuntimeException | Error e) {
      if (others != null) {
        others.endCompoundChange();
      }
      throw e;
    }
    return saved -> {
      try {
        if (keepResult) {
          Runnable putBack = () -> {
            if (saved) {
              b.showSaved(shown);
            } else {
              TextSpans.replaceMinimal(doc, shown);
            }
          };
          if (root.isDisposed() || adapter == null) {
            putBack.run(); // closing: no model or widget to spare any more, only the shared document
          } else {
            frozen(() -> adapter.runUntracked(putBack));
          }
        }
      } finally {
        if (others != null) {
          others.endCompoundChange();
        }
      }
    };
  }

  /** The result held still while a save started elsewhere writes the shared document. */
  interface Hold {
    /** The result text when the hold began. */
    String shown();

    /** Puts {@code text} into the document (with {@code saved}: the buffer stays clean) and tracks edits again. */
    void release(String text, boolean saved);
  }

  /** Stops the model from seeing changes of the shared document (save actions of other editors) until released. */
  Hold holdResult() {
    SharedBuffer b = setup.sharedBuffer();
    IDocument doc = adapter.document();
    String shown = doc.get();
    Runnable resume = adapter.hold();
    return new Hold() {
      @Override
      public String shown() {
        return shown;
      }

      @Override
      public void release(String text, boolean saved) {
        try {
          if (!root.isDisposed() && adapter != null) {
            frozen(() -> adapter.runUntracked(() -> {
              if (saved) {
                b.showSaved(text);
              } else {
                TextSpans.replaceMinimal(doc, text);
              }
            }));
          }
        } finally {
          resume.run();
        }
      }
    };
  }

  /** Runs a replacement of the result text without flicker, keeping the selection and the scroll position. */
  private void frozen(Runnable r) {
    StyledText t = result.text();
    Point sel = result.viewer().getSelectedRange();
    int top = t.getTopIndex();
    t.setRedraw(false);
    programmaticMove = true;
    try {
      r.run();
    } finally {
      int length = adapter.document().getLength();
      int x = Math.min(sel.x, length);
      result.viewer().setSelectedRange(x, Math.min(sel.y, length - x));
      t.setTopIndex(top);
      programmaticMove = false;
      t.setRedraw(true);
    }
  }

  public void compareWithBase(CompareWithBase.Pair p) {
    if (session != null) {
      CompareWithBase.open(session, p);
    }
  }

  public void setSaveAndMarkResolvedHook(Runnable hook) {
    this.saveAndMarkResolvedHook = hook;
  }

  /** "Open in EGit Merge Tool" of the editor hosting this viewer; null outside an editor. */
  private Runnable openInEGitHook;
  private java.util.function.BooleanSupplier egitTakesFile = () -> false;

  /** {@code takesFile}: whether EGit's Merge Tool can take the file at all (a project EGit manages). */
  void setOpenInEGitHook(Runnable hook, java.util.function.BooleanSupplier takesFile) {
    this.openInEGitHook = hook;
    this.egitTakesFile = takesFile;
  }

  /** True when ⋯ More offers "Open in EGit Merge Tool": in an editor, EGit UI installed, a file EGit manages. */
  boolean canOpenInEGit() {
    return openInEGitHook != null && EGitMergeTool.available() && egitTakesFile.getAsBoolean();
  }

  void openInEGit() {
    if (openInEGitHook != null) {
      openInEGitHook.run();
    }
  }

  public void setResultTitle(String fileName) {
    result.setHeader(String.format(Messages.resultTitle, fileName));
  }

  @Override
  public ISelection getSelection() {
    return selectedBlock < 0 ? StructuredSelection.EMPTY : new StructuredSelection(selectedBlock);
  }

  @Override
  public void setSelection(ISelection selection, boolean reveal) {
    if (selection instanceof StructuredSelection s && s.getFirstElement() instanceof Integer i) {
      selectBlock(i);
    }
  }

  @Override
  public void refresh() {
    if (session == null) {
      return;
    }
    refreshCount++;
    refreshedAtChange = documentChanges;
    MergeModel m = session.model();
    origins = BlockStyles.origins(m);
    inner = new InnerLine.Ranges[m.blocks().size()];
    comparisonDeferred = false;
    long now = System.nanoTime();
    compareNow = !refreshingAfterEdit || lastComparison == 0
        || now - lastComparison >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(compareDelay);
    int comparisons = resultComparisons;
    for (int i = 0; i < inner.length; i++) {
      inner[i] = innerLineEnabled ? innerRanges(m, i) : InnerLine.Ranges.NONE;
    }
    if (resultComparisons != comparisons) {
      lastComparison = now == 0 ? 1 : now;
    }
    java.util.function.IntPredicate hasInner = i -> !inner[i].isEmpty();
    java.util.List<ResultProblem> problems = setup.problems(); // one snapshot for marks and status
    updateCompileMarks(problems);
    boolean afterEdit = refreshingAfterEdit;
    left.setStyle(BlockStyles.compute(m, PaneKind.LEFT, left.lineCount(), hasInner, i -> false, origins), afterEdit);
    result.setStyle(BlockStyles.compute(m, PaneKind.RESULT, result.lineCount(), hasInner, notCompiling::contains,
        origins), afterEdit);
    right.setStyle(BlockStyles.compute(m, PaneKind.RIGHT, right.lineCount(), hasInner, i -> false, origins),
        afterEdit);
    for (PaneKind k : PaneKind.values()) {
      if (k == PaneKind.RESULT && comparisonDeferred) {
        continue; // its words and marks moved with the typing; the comparison follows after a pause
      }
      List<PanePainter.Insertion> marks = new java.util.ArrayList<>();
      highlighters.get(k).setSpans(wordSpans(k, marks));
      pane(k).painter().setInsertions(marks);
    }
    if (comparisonDeferred) {
      compareLater();
    }
    showSelection(); // block ranges move with edits
    status.setText(statusText(problems));
    showPosition();
    paintBar(m);
    statusBar.layout();
    scrollSync.rebuild();
    redrawDecorations();
    toolbar.refreshState();
    structure.changed();
  }

  private InnerLine.Ranges innerRanges(MergeModel m, int block) {
    var b = m.blocks().get(block);
    if (InnerLine.comparesWithResult(b)) {
      return resultRanges(m, block);
    }
    if (!b.isConflict() || b.state() != BlockState.UNRESOLVED) {
      return InnerLine.Ranges.NONE;
    }
    if (innerCache[block] == null) {
      innerLineComputations++;
      innerCache[block] = InnerLine.compute(m, block);
    }
    return innerCache[block];
  }

  /**
   * A resolved block's sides against its result, cached until one of the three texts changes. While the user types,
   * a block compared less than {@link #compareDelay} ago keeps its last ranges and is compared after a pause.
   */
  private InnerLine.Ranges resultRanges(MergeModel m, int block) {
    var b = m.blocks().get(block);
    if (b.resultEnd() - b.resultStart() > MAX_COMPARED_LINES
        || b.source().end(ThreeSide.LEFT) - b.source().start(ThreeSide.LEFT) > MAX_COMPARED_LINES
        || b.source().end(ThreeSide.RIGHT) - b.source().start(ThreeSide.RIGHT) > MAX_COMPARED_LINES) {
      return InnerLine.Ranges.NONE;
    }
    String l = m.sideText(block, ThreeSide.LEFT);
    String res = m.resultText(block);
    String r = m.sideText(block, ThreeSide.RIGHT);
    Compared c = compared[block];
    if (c != null && c.result().equals(res) && c.left().equals(l) && c.right().equals(r)) {
      return c.ranges();
    }
    if ((long) l.length() + res.length() + r.length() > MAX_COMPARED_CHARS) {
      compared[block] = new Compared(l, res, r, InnerLine.Ranges.NONE);
      return InnerLine.Ranges.NONE;
    }
    if (!compareNow) {
      comparisonDeferred = true;
      return c == null ? InnerLine.Ranges.NONE : c.ranges();
    }
    resultComparisons++;
    c = new Compared(l, res, r, InnerLine.againstResult(l, res, r));
    compared[block] = c;
    return c.ranges();
  }

  /** One refresh after the pause that compares what the typing left for later. */
  private void compareLater() {
    if (comparisonTimer) {
      return;
    }
    comparisonTimer = true;
    display.timerExec(compareDelay, () -> {
      comparisonTimer = false;
      if (!root.isDisposed() && session != null) {
        refreshingAfterEdit = true; // colours and appearance are as before: only changed lines are painted
        try {
          lastComparison = 0;
          refresh();
        } finally {
          refreshingAfterEdit = false;
        }
      }
    });
  }

  /** How long typing defers comparing a resolved block with its result again (ms); for tests. */
  void setCompareDelay(int millis) {
    compareDelay = millis;
  }

  /** Number of word-level computations so far; for tests. */
  int innerLineComputations() {
    return innerLineComputations;
  }

  /** Number of comparisons of resolved blocks with their result so far; for tests. */
  int resultComparisons() {
    return resultComparisons;
  }

  /** Number of refreshes so far; for tests. */
  int refreshCount() {
    return refreshCount;
  }

  static String layoutLabel(PaneLayout pl) {
    return switch (pl) {
      case ALL -> Messages.layoutAll;
      case LEFT_RESULT -> Messages.layoutLeftResult;
      case RESULT_RIGHT -> Messages.layoutResultRight;
      case RESULT_BELOW -> Messages.layoutResultBelow;
    };
  }

  public void setPaneLayout(PaneLayout pl) {
    layout.setMode(pl);
    MergePreferences.setLayout(pl);
    toolbar.syncLayout(pl);
    panes.layout(true);
    if (!pane(focused).isVisible()) {
      focused = PaneKind.RESULT;
      setSelectedBlock(-1);
      result.text().setFocus();
    }
    scrollSync.rebuild();
    redrawDecorations();
    toolbar.refreshState();
  }

  public PaneLayout paneLayout() {
    return layout.mode();
  }

  /**
   * The merge font changed (preferences design §2): the panes take it (whichever listener runs first), the scrolling
   * is rebuilt on the new line height and the connectors and stripes follow the moved lines.
   */
  private void fontChanged() {
    if (root.isDisposed()) {
      return;
    }
    for (PaneKind k : PaneKind.values()) {
      pane(k).applyFont();
    }
    scrollSync.rebuild();
    redrawDecorations();
  }

  /** An appearance switch changed (preferences design §3): relayout and repaint in place. */
  void appearanceChanged() {
    if (root.isDisposed()) {
      return;
    }
    layout.setConnectorWidth(Appearance.connector().pixels());
    if (result.originColumn() != null) {
      result.originColumn().updateWidth();
    }
    panes.layout(true);
    scrollSync.rebuild();
    for (PaneKind k : PaneKind.values()) {
      pane(k).text().redraw();
    }
    redrawDecorations();
  }

  private void redrawDecorations() {
    decorationRedraws++;
    leftConnector.redraw();
    rightConnector.redraw();
    for (PaneKind k : PaneKind.values()) {
      pane(k).stripe().redraw();
    }
  }

  ConnectorCanvas connector(PaneKind side) {
    return side == PaneKind.LEFT ? leftConnector : rightConnector;
  }

  /** Where the result of {@code block} came from, as of the last refresh (block-states design §1). */
  PaneStyle.Origin origin(int block) {
    return block >= 0 && block < origins.length ? origins[block] : PaneStyle.Origin.NONE;
  }

  /** {@link #origin} of every block as of the last refresh; read only. */
  PaneStyle.Origin[] origins() {
    return origins;
  }

  /** The current block (block-states design §2), -1 for none. */
  int selectedBlock() {
    return selectedBlock;
  }

  StructureController structure() {
    return structure;
  }

  /** Shows or hides the Structure column (toggle, «, M1+M3+S); remembered for every resolver (design §2). */
  public void setStructureShown(boolean shown) {
    if (shown && !structureAvailable()) {
      toolbar.syncStructure(false);
      return; // Ruling 17: no structure for this file, the remembered state stays
    }
    MergePreferences.setStructureShown(shown);
    showStructure(shown);
  }

  public void toggleStructure() {
    setStructureShown(!structureShown());
  }

  public boolean structureShown() {
    return layout.structureShown();
  }

  /** The file has a structure tree (Java without JDT included: the panel says why it is empty). */
  boolean structureAvailable() {
    return structure.status() != StructureController.Status.NOT_AVAILABLE;
  }

  private void showStructure(boolean shown) {
    boolean focusInside = false;
    for (Control c = display.getFocusControl(); c != null; c = c.getParent()) {
      focusInside |= c == structurePanel.control();
    }
    boolean was = layout.structureShown();
    layout.setStructureShown(shown);
    structure.setShowing(structurePanel, shown);
    toolbar.syncStructure(shown);
    panes.layout(true);
    if (shown && !was) {
      structurePanel.tree().revealCurrentBlock(); // the node of the current block is in view once shown
    }
    if (!shown && focusInside) {
      result.text().setFocus(); // the hidden tree must not keep the keys
    }
    scrollSync.rebuild();
    redrawDecorations();
    toolbar.refreshState();
  }

  StructurePanel structurePanel() {
    return structurePanel;
  }

  Sash structureSash() {
    return structureSash;
  }

  /** Told the new current block (or -1) whenever it changes. */
  void addCurrentBlockListener(java.util.function.IntConsumer l) {
    currentBlockListeners.add(l);
  }

  void removeCurrentBlockListener(java.util.function.IntConsumer l) {
    currentBlockListeners.remove(l);
  }

  /**
   * Text styles of one pane: a side not taken grey and struck through (block-states design §1, also with inner-line
   * differences off); with them on, an open conflict's word ranges in conflict strong, a resolved conflict's words
   * that differ from its result (or, in the result, from a side) in the strong shade of the pane's kind with the
   * insertion marks added to {@code marks} (visual checklist row 44), and in the side panes the changed words of every
   * other painted block (ui-polish design §2).
   */
  private List<InnerLineHighlighter.Span> wordSpans(PaneKind kind, List<PanePainter.Insertion> marks) {
    List<InnerLineHighlighter.Span> out = new java.util.ArrayList<>();
    IDocument doc = pane(kind).viewer().getDocument();
    if (doc == null) {
      return out;
    }
    MergeModel m = session.model();
    for (int i = 0; i < m.blocks().size(); i++) {
      MergeBlock block = m.blocks().get(i);
      LineStyle.Kind paneKind = BlockStyles.kind(block, kind, origin(i));
      if (paneKind == LineStyle.Kind.DROPPED) {
        InnerLineHighlighter.Span dropped = droppedSpan(doc, block, kind);
        if (dropped != null) {
          out.add(dropped);
        }
      }
      if (!innerLineEnabled) {
        continue;
      }
      List<int[]> rel;
      List<Integer> relMarks = List.of();
      Color color;
      boolean againstResult = InnerLine.comparesWithResult(block);
      if (againstResult) {
        if (i >= inner.length || BlockStyles.start(block, kind) >= BlockStyles.end(block, kind)) {
          continue; // no lines of its own here: the empty-block marker is all there is
        }
        rel = switch (kind) {
          case LEFT -> inner[i].left();
          case RESULT -> inner[i].base();
          case RIGHT -> inner[i].right();
        };
        relMarks = switch (kind) {
          case LEFT -> inner[i].leftMarks();
          case RESULT -> inner[i].baseMarks();
          case RIGHT -> inner[i].rightMarks();
        };
        color = Palette.word(paneKind);
      } else if (paneKind == LineStyle.Kind.DROPPED) {
        continue; // no words on a side that was not taken
      } else if (i < inner.length && !inner[i].isEmpty()) {
        rel = switch (kind) {
          case LEFT -> inner[i].left();
          case RESULT -> inner[i].base();
          case RIGHT -> inner[i].right();
        };
        color = Palette.strong(LineStyle.Kind.CONFLICT);
      } else if (kind != PaneKind.RESULT && showsSideWords(block, kind)) {
        rel = sideWords(i, kind);
        color = wordColor(paneKind);
      } else {
        continue;
      }
      if ((rel.isEmpty() && relMarks.isEmpty()) || color == null
          || (kind != PaneKind.RESULT && !sideMatchesPane(i, kind))) {
        continue; // nothing to show, or an override that the pane does not show line for line
      }
      // the words of a dropped side are darker than its struck text, the strike keeps its colour
      Color text = againstResult ? Palette.wordText(paneKind) : null;
      int line = BlockStyles.start(block, kind);
      try {
        int base = doc.getLineOffset(Math.min(line, doc.getNumberOfLines() - 1));
        // word offsets are relative to the block's '\n'-separated model text; the result may end lines with "\r\n"
        String modelText = kind != PaneKind.RESULT ? null : againstResult ? m.resultText(i) : block.baseText();
        for (int[] r : rel) {
          if (modelText == null) {
            out.add(new InnerLineHighlighter.Span(base + r[0], r[1], color, text, false));
          } else {
            int s = CrlfOffsets.toDocument(modelText, r[0], resultEol);
            int e = CrlfOffsets.toDocument(modelText, r[0] + r[1], resultEol);
            out.add(new InnerLineHighlighter.Span(base + s, e - s, color, text, false));
          }
        }
        Color mark = Palette.insertion(paneKind);
        for (int at : relMarks) {
          int o = modelText == null ? at : CrlfOffsets.toDocument(modelText, at, resultEol);
          marks.add(new PanePainter.Insertion(base + o, mark));
        }
      } catch (org.eclipse.jface.text.BadLocationException e) {
        // block outside the document (stale): skip
      }
    }
    return out;
  }

  /** The lines of {@code block} in a side pane, without the last line end, in dropped.text and struck through. */
  private static InnerLineHighlighter.Span droppedSpan(IDocument doc, MergeBlock block, PaneKind kind) {
    int start = BlockStyles.start(block, kind);
    int end = BlockStyles.end(block, kind);
    if (start >= end) {
      return null; // an empty side: its marker is all there is
    }
    try {
      int lines = doc.getNumberOfLines();
      int from = doc.getLineOffset(Math.min(start, lines - 1));
      org.eclipse.jface.text.IRegion last = doc.getLineInformation(Math.min(end - 1, lines - 1));
      int to = last.getOffset() + last.getLength();
      return to > from
          ? new InnerLineHighlighter.Span(from, to - from, null, Palette.strong(LineStyle.Kind.DROPPED), true) : null;
    } catch (org.eclipse.jface.text.BadLocationException e) {
      return null; // stale block
    }
  }

  /** Side panes show the changed words of the blocks they paint, except open conflicts, ignored and AI blocks. */
  static boolean showsSideWords(MergeBlock b, PaneKind kind) {
    return BlockStyles.visible(b, kind) && !(b.isConflict() && b.state() == BlockState.UNRESOLVED)
        && b.state() != BlockState.IGNORED && b.state() != BlockState.AI_RESOLVED;
  }

  /** The background of a side's changed words: the strong shade of its kind, on the auto colour auto.hatch. */
  static Color wordColor(LineStyle.Kind kind) {
    return kind == LineStyle.Kind.AUTO ? Palette.autoHatch() : Palette.strong(kind);
  }

  /** Changed words of a side against the base, once per block and input: side and base texts never change. */
  private List<int[]> sideWords(int block, PaneKind kind) {
    var cache = kind == PaneKind.LEFT ? leftWords : rightWords;
    return cache.computeIfAbsent(block, b -> {
      sideWordComputations++;
      return InnerLine.sideWords(session.model(), b, kind == PaneKind.LEFT ? ThreeSide.LEFT : ThreeSide.RIGHT);
    });
  }

  /** Number of times the connectors and stripes were asked to repaint; for tests. */
  int decorationRedraws() {
    return decorationRedraws;
  }

  /** Number of side word computations so far; for tests. */
  int sideWordComputations() {
    return sideWordComputations;
  }

  /** The only place that changes the selected block: keeps its outline in the panes up to date. */
  private void setSelectedBlock(int index) {
    if (selectedBlock != index) {
      selectedBlock = index;
      showSelection();
      redrawDecorations(); // the connectors' edges and the stripes' outline follow the current block
      showPosition();
      toolbar.refreshState();
      for (java.util.function.IntConsumer l : currentBlockListeners) {
        l.accept(index);
      }
    }
  }

  /** Outlines the selected block in every pane where it has a place (ui-polish design §2: an outline, no fill). */
  private void showSelection() {
    MergeBlock b = session != null && selectedBlock >= 0 && selectedBlock < session.model().blocks().size()
        ? session.model().blocks().get(selectedBlock) : null;
    for (PaneKind k : PaneKind.values()) {
      boolean shown = b != null && (k == PaneKind.RESULT || BlockStyles.visible(b, k));
      pane(k).painter().setSelection(shown
          ? new PaneStyle.Frame(BlockStyles.start(b, k), BlockStyles.end(b, k)) : null);
    }
  }

  /** The side text of {@code block} has as many lines as its block in the side pane. */
  private boolean sideMatchesPane(int block, PaneKind kind) {
    MergeModel m = session.model();
    var b = m.blocks().get(block);
    String override = kind == PaneKind.LEFT ? b.leftOverride() : b.rightOverride();
    if (override == null) {
      return true;
    }
    int lines = (int) override.chars().filter(c -> c == '\n').count()
        + (override.isEmpty() || override.endsWith("\n") ? 0 : 1);
    return lines == BlockStyles.end(b, kind) - BlockStyles.start(b, kind);
  }

  boolean innerLineEnabled() {
    return innerLineEnabled;
  }

  /** Takes the inner-line switch the preferences hold now (the page or another resolver changed it). */
  private void followInnerLine() {
    if (!root.isDisposed() && innerLineEnabled != MergePreferences.innerLine()) {
      setInnerLineEnabled(MergePreferences.innerLine());
    }
  }

  public void setInnerLineEnabled(boolean enabled) {
    innerLineEnabled = enabled;
    refresh();
  }

  public String statusText() {
    return session == null ? "" : statusText(setup.problems());
  }

  private String statusText(java.util.List<ResultProblem> problems) {
    if (session == null) {
      return "";
    }
    MergeModel m = session.model();
    int ai = 0;
    for (MergeBlock b : m.blocks()) {
      if (b.needsReview() && b.state() == BlockState.AI_RESOLVED) {
        ai++;
      }
    }
    String hidden = notUnderlined.isEmpty() ? null : notUnderlined.values().iterator().next().get(0);
    return StatusText.format(m.unresolvedConflicts(), m.needsReviewCount() - ai, ai, errorCount(problems),
        notCompiling.size(), hidden);
  }

  /** Blocks marked "auto-resolved, but does not compile" (spec §5.4); follows reconciles, not part of undo. */
  private java.util.SortedSet<Integer> notCompiling = new java.util.TreeSet<>();

  /**
   * The messages of the errors that frame a block but that the result does not show (languages design, Part 2: only
   * AfterGit's own check of the result finds them), by block; the hover and the status line name them.
   */
  private java.util.Map<Integer, java.util.List<String>> notUnderlined = java.util.Map.of();

  /** Recomputes the marks; true when they changed. */
  private boolean updateCompileMarks(java.util.List<ResultProblem> problems) {
    java.util.List<LineProblem> shown = resultErrors(problems);
    java.util.SortedMap<Integer, java.util.List<LineProblem>> failing = CompileCheck.newErrors(session.model(),
        setup.comparedErrors(shown), setup.sideErrors(ThreeSide.LEFT), setup.sideErrors(ThreeSide.RIGHT));
    java.util.Map<Integer, java.util.List<String>> hidden = new java.util.TreeMap<>();
    failing.forEach((block, errors) -> {
      java.util.List<String> messages = errors.stream().filter(e -> !shown.contains(e)).map(LineProblem::message)
          .distinct().toList();
      if (!messages.isEmpty()) {
        hidden.put(block, messages);
      }
    });
    java.util.SortedSet<Integer> now = new java.util.TreeSet<>(failing.keySet());
    boolean changed = !now.equals(notCompiling) || !hidden.equals(notUnderlined);
    notCompiling = now;
    notUnderlined = java.util.Map.copyOf(hidden);
    return changed;
  }

  /** The errors that frame {@code block} without being underlined in the result, or an empty list. */
  java.util.List<String> notUnderlinedErrors(int block) {
    return notUnderlined.getOrDefault(block, java.util.List.of());
  }

  /** The result's errors by model line (no lone '\r' in a shared buffer, so document lines are model lines). */
  private java.util.List<LineProblem> resultErrors(java.util.List<ResultProblem> problems) {
    IDocument d = result.viewer().getDocument();
    java.util.List<LineProblem> out = new java.util.ArrayList<>();
    if (d == null) {
      return out;
    }
    for (ResultProblem p : problems) {
      if (!p.error()) {
        continue;
      }
      try {
        out.add(new LineProblem(d.getLineOfOffset(Math.min(p.offset(), d.getLength())),
            p.message() == null ? "" : p.message()));
      } catch (org.eclipse.jface.text.BadLocationException e) {
        // a stale problem: skip
      }
    }
    return out;
  }

  boolean notCompiling(int block) {
    return notCompiling.contains(block);
  }

  int notCompilingCount() {
    return notCompiling.size();
  }

  /**
   * Waits up to {@code millis} for the language server's answer to the current text (languages design, Part 2
   * Ruling 3), running the UI thread's events meanwhile so that the editor stays responsive and what arrives is
   * shown; then the marks follow what is known. True when nothing is pending any more. Returns at once for Java and
   * plain text.
   */
  boolean awaitDiagnostics(long millis) {
    if (session == null || root.isDisposed()) {
      return true;
    }
    long end = System.nanoTime() + millis * 1_000_000;
    boolean settled = !setup.diagnosticsPending();
    while (!settled && System.nanoTime() - end < 0 && !root.isDisposed()) {
      if (!display.readAndDispatch()) {
        display.timerExec(20, () -> {}); // wakes the sleep below when nothing else does
        display.sleep();
      }
      settled = !setup.diagnosticsPending();
    }
    if (!root.isDisposed() && session != null) {
      java.util.List<ResultProblem> problems = setup.problems(); // one snapshot for marks and status
      if (updateCompileMarks(problems)) {
        showCompileMarks();
      }
      status.setText(statusText(problems));
      statusBar.layout();
    }
    return settled;
  }

  private Color barColor;

  /** Green only without unresolved blocks and without blocks that do not compile (spec §6.7). */
  private void paintBar(MergeModel m) {
    Color bg = m.unresolvedConflicts() == 0 && notCompiling.isEmpty() ? Palette.light(LineStyle.Kind.ADDED) : null;
    barColor = bg;
    repaintBar();
  }

  private void repaintBar() {
    if (statusBar.isDisposed()) {
      return;
    }
    position.setBarColor(barColor);
    status.setBarColor(barColor);
    notice.setBarColor(barColor);
    statusBar.redraw();
  }

  /** The result's frames and the bar after the marks changed without an edit (no refresh). */
  private void showCompileMarks() {
    MergeModel m = session.model();
    result.setStyle(BlockStyles.compute(m, PaneKind.RESULT, result.lineCount(),
        i -> i < inner.length && !inner[i].isEmpty(), notCompiling::contains, origins));
    paintBar(m);
  }

  public void nextError() {
    error(true);
  }

  public void previousError() {
    error(false);
  }

  private void error(boolean forward) {
    if (session == null) {
      return;
    }
    java.util.List<ResultProblem> problems = setup.problems();
    int i = ErrorLocator.next(problems, result.viewer().getSelectedRange().x, forward);
    if (i < 0) {
      return;
    }
    ResultProblem p = problems.get(i);
    setSelectedBlock(-1);
    focused = PaneKind.RESULT;
    programmaticMove = true;
    try {
      result.viewer().setSelectedRange(p.offset(), p.length());
      result.viewer().revealRange(p.offset(), p.length());
    } finally {
      programmaticMove = false;
    }
    result.text().setFocus();
    setSelectedBlock(blockUnderCaret(PaneKind.RESULT)); // the block under the error is current, like a click
    scrollSync.syncFrom(PaneKind.RESULT);
  }

  int errorCount() {
    return errorCount(setup.problems());
  }

  private static int errorCount(java.util.List<ResultProblem> problems) {
    return (int) problems.stream().filter(ResultProblem::error).count();
  }

  /** Document lines of the result's errors (model lines: no lone '\r' in a shared buffer). */
  int[] errorLines() {
    IDocument d = result.viewer().getDocument();
    if (d == null) {
      return new int[0];
    }
    return setup.problems().stream().filter(ResultProblem::error).mapToInt(p -> {
      try {
        return d.getLineOfOffset(Math.min(p.offset(), d.getLength()));
      } catch (org.eclipse.jface.text.BadLocationException e) {
        return -1;
      }
    }).filter(l -> l >= 0).toArray();
  }

  /** The primary Save & Mark Resolved button of the toolbar. */
  Button saveAndMarkResolvedButton() {
    return toolbar.primaryButton();
  }

  /** Runs Save & Mark Resolved (the editor input sets what it does). */
  void runSaveAndMarkResolved() {
    saveAndMarkResolvedHook.run();
  }

  Composite statusBar() {
    return statusBar;
  }

  /** A conflict lies after ({@code forward}) or before the current position. */
  boolean hasConflict(boolean forward) {
    return session != null
        && BlockLocator.nextConflict(session.model(), fromLine(PaneKind.RESULT, forward), forward) >= 0;
  }

  /** A change of the focused pane lies after ({@code forward}) or before the current position. */
  boolean hasChange(boolean forward) {
    return session != null
        && BlockLocator.nextChange(session.model(), focused, fromLine(focused, forward), forward) >= 0;
  }

  /** The state color the bar paints; the theme's background when null. */
  Color statusBarBackground() {
    return barColor != null ? barColor : statusBar.getBackground();
  }

  private static org.eclipse.swt.graphics.RGB pixelRgb(org.eclipse.swt.graphics.ImageData d, int x, int y) {
    return d.palette.getRGB(d.getPixel(x, y));
  }

  boolean allResolvedBarShown() {
    Color green = Palette.light(LineStyle.Kind.ADDED);
    if (barColor == null || !barColor.equals(green) || !status.barColor().equals(green)) {
      return false;
    }
    // what the user sees: a pixel of the bar's own margin, and one in the status text area
    org.eclipse.swt.graphics.Rectangle r = statusBar.getClientArea();
    if (r.width < 2 || r.height < 1) {
      return true;
    }
    org.eclipse.swt.graphics.Image img = new org.eclipse.swt.graphics.Image(statusBar.getDisplay(), r.width, r.height);
    org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(img);
    try {
      statusBar.print(gc);
    } finally {
      gc.dispose();
    }
    try {
      org.eclipse.swt.graphics.ImageData data = img.getImageData();
      org.eclipse.swt.graphics.Rectangle sb = status.getBounds();
      int sx = Math.min(r.width - 1, Math.max(0, sb.x + sb.width - 1));
      int sy = Math.min(r.height - 1, Math.max(0, sb.y + sb.height - 1));
      return pixelRgb(data, 1, 0).equals(green.getRGB()) && pixelRgb(data, sx, sy).equals(green.getRGB());
    } finally {
      img.dispose();
    }
  }

  public MergePane pane(PaneKind kind) {
    return switch (kind) {
      case LEFT -> left;
      case RESULT -> result;
      case RIGHT -> right;
    };
  }

  public void addDirtyListener(Runnable listener) {
    dirtyListeners.add(listener);
  }

  private void fireDirty() {
    for (Runnable r : dirtyListeners) {
      r.run();
    }
  }

  public int currentBlock() {
    if (session == null) {
      return -1;
    }
    if (selectedBlock >= 0) {
      return selectedBlock;
    }
    MergePane p = pane(focused);
    int at = BlockLocator.blockAt(session.model(), focused, p.caretLine());
    if (at >= 0) {
      return at;
    }
    return BlockLocator.nextConflict(session.model(), result.caretLine() - 1, true);
  }

  public void revealBlock(int index, PaneKind kind) {
    if (session == null) {
      return;
    }
    setSelectedBlock(index);
    programmaticMove = true;
    try {
      var b = session.model().blocks().get(index);
      result.revealLine(b.resultStart());
      if (kind != PaneKind.RESULT) {
        pane(kind).revealLine(BlockStyles.start(b, kind));
      }
    } finally {
      programmaticMove = false;
    }
    // the pane revealed last leads; the others follow through the scroll map
    scrollSync.syncFrom(kind);
  }

  PaneKind focusedPane() {
    return focused;
  }

  ScrollSync scrollSync() {
    return scrollSync;
  }

  public void selectBlock(int index) {
    revealBlock(index, PaneKind.RESULT);
  }

  private int fromLine(PaneKind kind, boolean forward) {
    if (selectedBlock >= 0) {
      var sel = session.model().blocks().get(selectedBlock);
      int s = BlockStyles.start(sel, kind);
      int e = BlockStyles.end(sel, kind);
      return forward ? Math.max(s, e - 1) : s;
    }
    int caret = pane(kind).caretLine();
    return forward ? caret - 1 : caret;
  }

  /** Next unresolved conflict (toolbar "Unresolved ▶"); command id {@code nextConflict} keeps its keys. */
  public void nextConflict() { conflict(true); }
  public void previousConflict() { conflict(false); }

  private void conflict(boolean forward) {
    if (session == null) {
      return;
    }
    int b = BlockLocator.nextConflict(session.model(), fromLine(PaneKind.RESULT, forward), forward);
    if (b >= 0) {
      selectBlock(b);
    }
  }

  /** Next block that was a conflict, in any state (toolbar "Conflict ▶", block-states design §3). */
  public void nextAnyConflict() { anyConflict(true); }
  public void previousAnyConflict() { anyConflict(false); }

  private void anyConflict(boolean forward) {
    if (session == null) {
      return;
    }
    int b = BlockLocator.nextAnyConflict(session.model(), selectedBlock, fromLine(PaneKind.RESULT, forward), forward);
    if (b >= 0) {
      selectBlock(b);
    }
  }

  /** A block that was a conflict lies after ({@code forward}) or before the current position. */
  boolean hasAnyConflict(boolean forward) {
    return session != null && BlockLocator.nextAnyConflict(session.model(), selectedBlock,
        fromLine(PaneKind.RESULT, forward), forward) >= 0;
  }

  /** "Conflict 3 of 7 · Take Left ◀" while the current block was a conflict, empty otherwise. */
  private void showPosition() {
    String p = session == null ? null : StatusText.position(session.model(), selectedBlock);
    position.setText(p == null ? "" : p);
    // without a position the label leaves the layout, so the counts keep their place at the left edge
    ((GridData) position.getLayoutData()).exclude = p == null;
    position.setVisible(p != null);
    statusBar.layout();
  }

  /** Text of the position in the status line; for tests. */
  String positionText() {
    return position.text();
  }

  private SiteServices siteServices;

  void setSiteServices(SiteServices s) { this.siteServices = s; }

  SiteServices siteServices() { return siteServices; }

  public void nextChange() { change(true); }
  public void previousChange() { change(false); }

  private void change(boolean forward) {
    nextChangeIn(focused, forward);
  }

  public void nextChangeIn(PaneKind kind, boolean forward) {
    if (session == null) {
      return;
    }
    int b = BlockLocator.nextChange(session.model(), kind, fromLine(kind, forward), forward);
    if (b >= 0) {
      revealBlock(b, kind);
    }
  }

  public void nextAutoResolved() { auto(true); }
  public void previousAutoResolved() { auto(false); }

  private void auto(boolean forward) {
    if (session == null) {
      return;
    }
    int b = BlockLocator.nextAutoResolved(session.model(), fromLine(PaneKind.RESULT, forward), forward);
    if (b >= 0) {
      selectBlock(b);
    }
  }

  public void takeBlock(int block, ThreeSide side, boolean replace) {
    act(block, true, b -> {
      MergeModel m = session.model();
      BlockState other = side == ThreeSide.LEFT ? BlockState.TAKEN_RIGHT : BlockState.TAKEN_LEFT;
      if (replace && m.blocks().get(b).state() == other) {
        m.revert(b);
      }
      m.take(b, side);
    });
  }

  public void ignoreBlock(int block) {
    act(block, false, b -> session.model().ignore(b));
  }

  /** True while the user has a content assist or quick fix popup open in the result. */
  boolean resultPopupOpen() {
    return Popups.open(result.viewer());
  }

  /** False without input; otherwise closes the result's popups, which must not outlive a programmatic change. */
  private boolean readyToEdit() {
    if (undo == null) {
      return false;
    }
    Popups.close(result.viewer());
    return true;
  }

  /**
   * Runs {@code op} on the result as one undo step (spec §4.4), then refreshes. With {@code bodyChange} the language
   * integration adjusts the result inside the same step (Java: imports, spec §5.3); a failure there is logged and the
   * change stays. False without input.
   */
  boolean runResultAction(Runnable op, boolean bodyChange) {
    if (!readyToEdit()) {
      return false;
    }
    MergeModel m = session.model();
    String before = bodyChange ? m.document().chunk(0, m.document().lineCount()) : null;
    undo.runAction(() -> {
      op.run();
      if (before != null) {
        derivedChange = true;
        try {
          setup.afterBodyChange(m, before);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          // the change stays and remains one undo step
          ILog.of(MergeViewer.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
              "Adjusting the merge result after a change failed; the change stays", JavaBridge.loggable(e)));
        } finally {
          derivedChange = false;
        }
      }
    });
    refresh();
    return true;
  }

  /** One action on an explicit block: one undo step, then refresh and reveal. */
  private void act(int block, boolean textChange, java.util.function.IntConsumer op) {
    if (session == null) {
      return;
    }
    // the user's choice in the imports is final: only body changes get their imports adjusted
    boolean body = textChange && session.model().blocks().get(block).region() != Region.IMPORTS;
    if (runResultAction(() -> op.accept(block), body)) {
      // the block may have come from the caret fallback (next conflict below): show what changed
      selectBlock(block);
    }
  }

  private void withBlock(boolean textChange, java.util.function.IntConsumer op) {
    int b = currentBlock();
    if (b >= 0) {
      act(b, textChange, op);
    }
  }

  public void takeLeft() { withBlock(true, b -> session.model().take(b, ThreeSide.LEFT)); }
  public void takeRight() { withBlock(true, b -> session.model().take(b, ThreeSide.RIGHT)); }
  public void takeLeftThenRight() { withBlock(true, b -> session.model().takeBoth(b, ThreeSide.LEFT)); }
  public void takeRightThenLeft() { withBlock(true, b -> session.model().takeBoth(b, ThreeSide.RIGHT)); }
  public void ignore() { withBlock(false, b -> session.model().ignore(b)); }
  public void revert() {
    withBlock(true, this::revertAndForget);
  }

  /** Reverts {@code block} (↶ of an agent's block in the connector), one undo step. */
  public void revertBlock(int block) {
    act(block, true, this::revertAndForget);
  }

  /** Back to the conflict; a proposal (of an agent or of the auto source) reverted by the user is not offered again. */
  private void revertAndForget(int block) {
    MergeModel m = session.model();
    MergeBlock b = m.blocks().get(block);
    boolean proposed = b.state() == BlockState.AI_RESOLVED
        || b.state() == BlockState.AUTO_RESOLVED && Proposal.AUTO.equals(b.resolvedBy());
    BlockId id = proposed ? BlockId.of(m, block) : null;
    m.revert(block);
    if (id != null && !aiApplied.remove(id)) {
      overridden.add(id); // a take after an undo of this revert tells the store nothing new
      forget(id);
    }
  }

  /**
   * An agent's applied proposal the user overrode, by Take Left / Right / Both, Ignore or typing into it, is
   * forgotten like a discarded one (agents design §3), once per block: an undo brings the text back here, but the
   * store does not offer it again. Answers of the AI resolvers are not stored and tell nothing; a block made EDITED
   * by the language integration's adjustment (an import fix) was not overridden by the user.
   */
  private void forgetOverridden() {
    MergeModel m = session.model();
    List<MergeBlock> blocks = m.blocks();
    for (int i = 0; i < blocks.size(); i++) {
      BlockState s = blocks.get(i).state();
      if (s == BlockState.AI_RESOLVED) {
        aiResolved.computeIfAbsent(i, k -> BlockId.of(m, k));
        continue;
      }
      BlockId id = aiResolved.remove(i);
      boolean override = s == BlockState.TAKEN_LEFT || s == BlockState.TAKEN_RIGHT || s == BlockState.TAKEN_BOTH
          || s == BlockState.IGNORED || s == BlockState.EDITED && !derivedChange;
      if (id != null && override && !aiApplied.contains(id) && overridden.add(id)) {
        forget(id);
      }
    }
  }

  /** Tells the store to forget a proposal; a failure there is logged and never stops the user's action. */
  private void forget(BlockId id) {
    try {
      proposalForgetter.accept(id);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      ILog.of(MergeViewer.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The AI proposal of block " + id + " could not be forgotten", JavaBridge.loggable(e)));
    }
  }

  void setProject(IProject p) { this.project = p; }

  IProject project() { return project; }

  public void rerunAutoResolve() {
    runResultAction(() -> ResolverContributions.pipeline(project).forFile(session.fileName())
        .run(session.model()), true);
  }

  MergeToolbar toolbar() { return toolbar; }

  PaneSetup paneSetup() { return setup; }

  ResultKind resultKind() { return setup.resultKind(); }

  String noticeText() { return notice.text(); }

  /** Text the status line shows now; for tests. */
  String statusLabelText() { return status.text(); }

  /** Repaints of status and stripes for changed problems so far; for tests. */
  int problemRedraws() { return problemRedraws; }

  public void runGreedyOnce() {
    runResultAction(() -> ResolverContributions.pipeline(project, true).forFile(session.fileName())
        .run(session.model()), true);
  }

  public void revertAllAutoResolved() {
    runResultAction(() -> {
      MergeModel m = session.model();
      for (var b : m.blocks()) {
        if (b.state() == BlockState.AUTO_RESOLVED) {
          m.revert(b.index());
        }
      }
    }, true);
  }

  public void markReviewed() {
    withBlock(false, b -> session.model().markReviewed(b));
  }

  public void markAllReviewed() {
    runResultAction(() -> {
      MergeModel m = session.model();
      for (var b : m.blocks()) {
        if (b.needsReview()) {
          m.markReviewed(b.index());
        }
      }
    }, false);
  }

  /** Marks {@code block} reviewed (✓ of an agent's block in the connector), one undo step. */
  void markBlockReviewed(int block) {
    act(block, false, b -> session.model().markReviewed(b));
  }

  /** Called with the block of a proposal the user discarded, reverted or overrode, so the store forgets it. */
  void setProposalForgetter(Consumer<BlockId> forgetter) {
    proposalForgetter = forgetter;
  }

  /** Shows (true) or hides the agents (agents design §1): hidden, no proposal waits. */
  void setAgentsActive(boolean on) {
    agentsActive = on;
    if (!on) {
      pending.values().removeIf(p -> !aiProposals.contains(p));
      held.removeIf(p -> !aiProposals.contains(p));
      waitOnly.clear();
    }
    refresh();
  }

  boolean agentsActive() {
    return agentsActive;
  }

  /** True when {@code p} may act or show now: its source, the agents or the AI resolvers, is on. */
  private boolean active(Proposal p) {
    return aiProposals.contains(p) ? aiActive : agentsActive;
  }

  /** Shows (true) or hides what the AI resolvers proposed (resolve-with-ai design §1): hidden, none of it waits. */
  void setAiActive(boolean on) {
    if (aiActive == on) {
      return;
    }
    aiActive = on;
    if (!on) {
      pending.values().removeIf(aiProposals::contains);
      held.removeIf(aiProposals::contains);
      aiProposals.clear();
    }
    refresh();
  }

  boolean aiActive() {
    return aiActive;
  }

  /**
   * Offers the answer of an AI resolver (design §3) by the rules of an agent's proposal: applied at once to an
   * untouched open conflict as one undo step, otherwise waiting; with {@code hold} it waits until
   * {@link #releaseHeld()}. NOT_FOUND while the AI resolvers are hidden or the file has no such conflict.
   */
  Offer offerAi(Proposal proposal, boolean hold) {
    if (!aiActive || session == null) {
      return Offer.NOT_FOUND;
    }
    aiProposals.add(proposal);
    return offerAll(List.of(proposal), hold).get(0);
  }

  /** Resolve with AI in this editor: its menu entries and its run. */
  AiActions ai() {
    return ai;
  }

  /**
   * The request for {@code block} as it is now (design §2): its texts, context and compile errors; null without a
   * session (the editor is closing).
   */
  AiRequest aiRequest(int block, int contextLines) {
    if (session == null) {
      return null;
    }
    MergeModel m = session.model();
    return AiRequests.forBlock(m, block, session.fileName(), session.labels().left(), session.labels().right(),
        contextLines, new AiRequests.Errors(resultErrors(setup.problems()), setup.sideErrors(ThreeSide.LEFT),
            setup.sideErrors(ThreeSide.RIGHT)));
  }

  /** A notice of Resolve with AI in the status line (design §3), like the agents' notices. */
  void showAiNotice(String text) {
    showAgentNotice(text);
  }

  /** Remembers where an applied text came from, so a revert tells the agents' store only about theirs. */
  private void appliedFrom(Proposal p, MergeModel m, int block) {
    BlockId id = BlockId.of(m, block);
    if (aiProposals.contains(p)) {
      aiApplied.add(id);
    } else {
      aiApplied.remove(id);
    }
  }

  /** Tells whether the editor is busy (a save, its questions, another modal dialog); see {@link #agentsHeld()}. */
  void setAgentsHeld(BooleanSupplier held) {
    agentsHeld = held;
  }

  /** True while waiting proposals must not change the model nor be dropped. */
  boolean agentsHeld() {
    return agentsHeld.getAsBoolean();
  }

  private static BiFunction<Shell, Proposal, PendingProposalDialog.Choice> proposalChooser = PendingProposalDialog::ask;
  private static Consumer<Shell> busyNotice = MergeViewer::tellBusy;

  /** Replaces the proposal dialog in tests; null puts the dialog back. */
  static void setProposalChooserForTests(BiFunction<Shell, Proposal, PendingProposalDialog.Choice> chooser) {
    proposalChooser = chooser == null ? PendingProposalDialog::ask : chooser;
  }

  /** Replaces the message that Apply or Discard found the resolver busy, in tests; null puts it back. */
  static void setBusyNoticeForTests(Consumer<Shell> notice) {
    busyNotice = notice == null ? MergeViewer::tellBusy : notice;
  }

  private static void tellBusy(Shell parent) {
    MessageDialog.openInformation(parent, Messages.proposalBusyTitle, Messages.proposalBusy);
  }

  /** True when the waiting proposal of {@code block} can be shown now (More menu, ✨ in the connector). */
  boolean canShowPendingProposal(int block) {
    Proposal p = block < 0 ? null : pending.get(block);
    return p != null && active(p) && !agentsHeld();
  }

  /** Shows the waiting proposal of the current block (keyboard and screen reader way to the ✨). */
  public void showPendingProposal() {
    showPendingProposal(currentBlock());
  }

  /**
   * Shows the waiting proposal of {@code block} and applies or discards it as the user answers (agents design §3).
   * The answer is only about the text the user saw: one the agent replaced or took back meanwhile, or a closed
   * resolver, changes nothing.
   */
  void showPendingProposal(int block) {
    if (!canShowPendingProposal(block)) {
      return;
    }
    Proposal p = pending.get(block);
    Control control = getControl();
    PendingProposalDialog.Choice choice = proposalChooser.apply(control.getShell(), p);
    // the dialog's shell is gone once it answered; acting from inside it would count it as a modal dialog and be held
    if (control.isDisposed() || !active(p) || pending.get(block) != p
        || choice == PendingProposalDialog.Choice.CLOSE) {
      return;
    }
    boolean done = choice == PendingProposalDialog.Choice.APPLY ? applyPending(block) : discardPending(block);
    if (!done && agentsHeld()) {
      busyNotice.accept(control.getShell()); // the proposal still waits; it can be taken once the save is over
    }
  }

  /** How many proposals of the agents wait (the agents' status icon); answers of the AI resolvers are not counted. */
  int agentProposalsWaiting() {
    return agentsActive ? (int) pending.values().stream().filter(p -> !aiProposals.contains(p)).count() : 0;
  }

  /** The proposals that wait for the user, by block index; none of a source (agents, AI resolvers) that is off. */
  Map<Integer, Proposal> pendingProposals() {
    Map<Integer, Proposal> out = new TreeMap<>();
    pending.forEach((i, p) -> {
      if (active(p)) {
        out.put(i, p);
      }
    });
    return Collections.unmodifiableMap(out);
  }

  /** A notice of the agents in the status line, next to the notices of the setup and the session. */
  void showAgentNotice(String text) {
    showNotice(setup.notice(), joinNotices(session == null ? null : session.notice(), text));
  }

  Offer offerProposal(Proposal proposal) {
    return offerAll(List.of(proposal)).get(0);
  }

  List<Offer> offerAll(List<Proposal> proposals) {
    return offerAll(proposals, false);
  }

  /**
   * Offers proposals the user was already shown once (agents design §3: stored proposals an editor of the file got
   * before): each one waits, also for an untouched conflict, unless the block already holds its text. So an undo, a
   * revert or an edit of the user is never undone at once when the file opens again or agents come back.
   */
  List<Offer> offerWaiting(List<Proposal> proposals, boolean hold) {
    if (agentsActive) {
      waitOnly.addAll(proposals);
    }
    return offerAll(proposals, hold);
  }

  /**
   * Offers proposals (agents design §3): each one for an unresolved block that still holds its base text is applied
   * at once, all of them together as one undo step; one whose text the block already holds changes nothing; any
   * other waits for the user. Proposals for the same block act as if offered one after the other. NOT_FOUND when the
   * file has no such conflict. The selection does not move. With {@code hold} (the resolver is busy, a save is going
   * on) nothing changes: every proposal found waits until {@link #releaseHeld()}. While the source of a proposal (the
   * agents or the AI resolvers) is off, it changes nothing and does not wait: NOT_FOUND.
   */
  List<Offer> offerAll(List<Proposal> proposals, boolean hold) {
    List<Offer> out = new ArrayList<>();
    if (session == null) {
      proposals.forEach(p -> out.add(Offer.NOT_FOUND));
      return out;
    }
    MergeModel m = session.model();
    List<Runnable> apply = new ArrayList<>();
    Map<Integer, String> claimed = new java.util.HashMap<>(); // blocks this call already gave a text
    boolean body = false;
    for (Proposal p : proposals) {
      int i = active(p) ? p.block().find(m) : -1; // a proposal of a source that is off changes nothing
      if (i < 0) {
        out.add(Offer.NOT_FOUND);
      } else if (hold) {
        pending.put(i, p);
        held.add(p);
        out.add(Offer.PENDING);
      } else if (claimed.containsKey(i) && claimed.get(i).equals(p.text())) {
        out.add(Offer.APPLIED); // the same text again
      } else if (!claimed.containsKey(i) && m.isAutoResolvable(i) && !waitOnly.contains(p)) {
        apply.add(() -> {
          m.applyResolution(i, p.text(), p.state(), Strategy.TEXT, p.resolvedBy(), p.rationale());
          appliedFrom(p, m, i);
        });
        body |= m.blocks().get(i).region() != Region.IMPORTS;
        pending.remove(i);
        claimed.put(i, p.text());
        out.add(Offer.APPLIED);
      } else if (!claimed.containsKey(i) && m.resultText(i).equals(p.text())) {
        pending.remove(i);
        claimed.put(i, p.text());
        out.add(Offer.APPLIED);
      } else {
        pending.put(i, p);
        out.add(Offer.PENDING);
      }
    }
    if (apply.isEmpty() || !runResultAction(() -> apply.forEach(Runnable::run), body)) {
      refresh();
    }
    return out;
  }

  /** True while proposals wait that came while the resolver was busy. */
  boolean hasHeld() {
    return !held.isEmpty();
  }

  /** Offers again the proposals that came while the resolver was busy and still wait (none was discarded). */
  void releaseHeld() {
    if (held.isEmpty() || session == null) {
      held.clear();
      return;
    }
    MergeModel m = session.model();
    List<Proposal> again = new ArrayList<>();
    for (Proposal p : held) {
      int i = p.block().find(m);
      if (i >= 0 && pending.get(i) == p && active(p)) {
        pending.remove(i);
        again.add(p);
      }
    }
    held.clear();
    if (!again.isEmpty()) {
      offerAll(again, false);
    }
  }

  /** Applies the waiting proposal of {@code block} as one undo step; false when none waits. */
  boolean applyPending(int block) {
    if (agentsHeld()) {
      return false; // a save writes only what the user decided on before it started
    }
    Proposal p = pending.get(block);
    if (p == null || session == null || !active(p)) {
      return false;
    }
    pending.remove(block);
    act(block, true, b -> {
      session.model().applyResolution(b, p.text(), p.state(), Strategy.TEXT, p.resolvedBy(), p.rationale());
      appliedFrom(p, session.model(), b);
    });
    return true;
  }

  /** Drops the waiting proposal of {@code block}; it is not offered again. False when none waits. */
  boolean discardPending(int block) {
    if (agentsHeld()) {
      return false;
    }
    Proposal p = pending.remove(block);
    if (p == null) {
      return false;
    }
    try {
      if (!aiProposals.contains(p)) {
        forget(p.block()); // nothing of an AI resolver is stored
      }
    } finally {
      refresh();
    }
    return true;
  }

  /**
   * The agent took {@code proposal} back: a waiting one goes; an applied one that is unchanged and not reviewed
   * becomes the conflict again (one undo step). True when something changed.
   */
  boolean withdrawProposal(Proposal proposal) {
    if (session == null) {
      return false;
    }
    MergeModel m = session.model();
    int i = proposal.block().find(m);
    if (i < 0) {
      return false;
    }
    Proposal waiting = pending.get(i);
    if (waiting != null && !aiProposals.contains(waiting) && waiting.block().equals(proposal.block())) {
      pending.remove(i);
      refresh();
      return true;
    }
    MergeBlock b = m.blocks().get(i);
    if (b.state() == proposal.state() && b.needsReview() && m.resultText(i).equals(proposal.text())) {
      runResultAction(() -> m.revert(i), b.region() != Region.IMPORTS);
      return true;
    }
    return false;
  }

  void beginCompoundEdit() {
    if (undo != null) {
      undo.beginCompound();
    }
  }

  void endCompoundEdit() {
    if (undo != null) {
      undo.endCompound();
    }
  }

  /** Ends a compound that was left open (an interrupted completion, a disconnected viewer). */
  void resetCompoundEdit() {
    if (undo != null) {
      undo.forceClose();
    }
  }

  public void undo() {
    if (readyToEdit()) {
      afterRestore(undo.undo());
    }
  }

  public void redo() {
    if (readyToEdit()) {
      afterRestore(undo.redo());
    }
  }

  /** Puts the caret on what an undo or redo changed, so the next action applies there. */
  private void afterRestore(UndoStack.Restored r) {
    refresh();
    if (r.block() >= 0) {
      selectBlock(r.block());
    } else if (r.textOffset() >= 0) {
      setSelectedBlock(-1); // typing was undone: act at the caret, not on an earlier navigation
      programmaticMove = true;
      try {
        result.viewer().setSelectedRange(r.textOffset(), 0);
        result.viewer().revealRange(r.textOffset(), 0);
      } finally {
        programmaticMove = false;
      }
      scrollSync.syncFrom(PaneKind.RESULT);
      setSelectedBlock(blockUnderCaret(PaneKind.RESULT)); // the block under the restored caret is current
    }
  }

  public boolean canUndo() {
    return undo != null && undo.canUndo();
  }

  public boolean canRedo() {
    return undo != null && undo.canRedo();
  }

  void addUndoListener(Runnable r) {
    undoListeners.add(r);
  }

  private void fireUndoChanged() {
    for (Runnable r : undoListeners) {
      r.run();
    }
  }
}
