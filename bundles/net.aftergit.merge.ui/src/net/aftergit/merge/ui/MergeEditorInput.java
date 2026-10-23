package net.aftergit.merge.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.text.MessageFormat;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IServiceLocator;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.ResultWriter;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictKinds;
import net.aftergit.merge.git.Decoding;
import net.aftergit.merge.git.GitIndex;
import net.aftergit.merge.git.LabelResolver;
import net.aftergit.merge.git.LineEnds;
import net.aftergit.merge.git.MarkerConfig;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.MergeSources;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import net.aftergit.merge.git.StageReader;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.git.agents.ProposalSource;

/** Hosts the three-pane resolver in the standard Compare editor (spec §6.1). */
public final class MergeEditorInput extends CompareEditorInput {
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  private final IFile file;
  private RepoFile repoFile;
  private MergeSources sources;
  private MergeSession session;
  private MergeViewer viewer;
  private SaveDecision decision;
  private final List<StructureOutlinePage> outlinePages = new ArrayList<>();

  public MergeEditorInput(IFile file) {
    super(new CompareConfiguration());
    this.file = file;
    setTitle(String.format(Messages.editorTitle, file.getName()));
  }

  public void setSaveDecision(SaveDecision decision) {
    this.decision = decision;
  }

  public MergeViewer viewer() {
    return viewer;
  }

  public MergeSession session() {
    return session;
  }

  /**
   * A new Outline page of this resolver (structure-compare design §3); the Outline view disposes it. It is kept only
   * once its control exists, so a caller that asks and never shows it leaves nothing behind.
   */
  StructureOutlinePage outlinePage() {
    return new StructureOutlinePage(this);
  }

  void pageCreated(StructureOutlinePage p) {
    outlinePages.add(p);
  }

  int outlinePageCount() {
    return outlinePages.size();
  }

  void pageClosed(StructureOutlinePage p) {
    outlinePages.remove(p);
  }

  /** The Compare editor's own outline would replace ours while its preference is on (Ruling 13). */
  private void ownOutline() {
    getCompareConfiguration().setProperty(CompareConfiguration.USE_OUTLINE_VIEW, Boolean.FALSE);
  }

  @Override
  protected Object prepareInput(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
    try {
      ownOutline();
      // a command-line merge may have rewritten the file; sync first so saving does not fail
      file.refreshLocal(IResource.DEPTH_ZERO, monitor);
      repoFile = RepositoryLocator.locate(file)
          .orElseThrow(() -> new IOException(NLS.bind(Messages.editorNoRepository, file.getFullPath())));
      ConflictKind kind = ConflictKinds.classify(repoFile.repository(), repoFile.path());
      if (kind.offersSides() || kind.needsMergeTool()) {
        throw new IOException(NLS.bind(Messages.editorRefusesKind, file.getFullPath(), ConflictChoices.describe(kind)));
      }
      Charset charset = Charset.forName(file.getCharset());
      Labels labels = LabelResolver.resolve(repoFile.repository());
      sources = StageReader.read(repoFile, charset, labels);
      IProject project = file.getProject();
      ComparisonPolicy policy = MergePreferences.policy(project); // preferences design §6: the file's project
      boolean autoResolve = MergePreferences.autoResolveOnOpen(project);
      ResolverPipeline pipeline = autoResolve
          ? ResolverContributions.pipeline(project).forFile(file.getName()) : ResolverPipeline.none();
      boolean ownCharset = sources.decodedAs() == null || sources.decodedAs().equals(charset);
      // Latin-1 text of a file the Java tooling decodes otherwise is no Java source
      ImportLocator imports = ownCharset ? JavaBridge.importLocator(file, monitor) : null;
      InitResult init = HybridInitializer.initialize(
          new MergeInput(sources.base(), sources.ours(), sources.theirs(), sources.workingTree()), policy, pipeline,
          imports);
      if (monitor != null && monitor.isCanceled()) {
        throw new InterruptedException(); // the parses of the import sections stopped; nothing is opened
      }
      if (autoResolve && MergePreferences.resolverEnabled(project, ImportsResolver.ID)) {
        JavaBridge.fixImportsAtOpen(file, init.model(), init.imports(), monitor); // pass 1 ends with the imports
      }
      String w = sources.workingTree() == null ? "" : sources.workingTree();
      LineText working = LineText.of(w);
      // git's marker lines may end differently (JGit: LF in CRLF files): the content lines decide, the majority when mixed
      String contentEol = LineEnds.ofContentOrMajority(w);
      boolean trailing = LineText.mergeTrailingNewline(LineText.of(nz(sources.base())), LineText.of(nz(sources.ours())),
          LineText.of(nz(sources.theirs())), sources.workingTree() == null ? null : working);
      session = new MergeSession(init.model(), labels, contentEol, trailing,
          file.getName(), init.imports(), ownCharset ? null : sources.decodedAs(),
          notice(init, ownCharset ? null : charset), MarkerConfig.of(repoFile.repository(), sources.workingTree()),
          init.sidesFromMarkers());
      return new DiffNode(null, 0);
    } catch (IOException | CoreException e) {
      throw new InvocationTargetException(e);
    }
  }

  /**
   * Status line notice of how the result was built (spec section 9), or null; {@code invalidIn}: the charset the
   * bytes are not valid in.
   */
  static String notice(InitResult init, Charset invalidIn) {
    String markers = init.usedFallback() ? Messages.noticeFromMarkers
        : init.sidesFromMarkers().isEmpty() ? null : Messages.noticeSidesFromMarkers;
    String bytes = invalidIn == null ? null : NLS.bind(Messages.noticeInvalidBytes, invalidIn.name());
    return MergeViewer.joinNotices(markers, bytes);
  }

  IFile file() {
    return file;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  /** Chooses the panes for a file (Java, another language with a TextMate grammar, or plain text); replaceable for tests. */
  interface PaneSetupFactory {
    PaneSetup create(IFile file, IEditorSite site, MergeSession session);
  }

  private PaneSetupFactory paneSetups = (f, site, s) -> {
    PaneSetup java = JavaBridge.create(f, site, s);
    if (java != null) {
      return java;
    }
    PaneSetup languages = LspBridge.create(f, s); // never for Java files, with or without the JDT
    return languages != null ? languages : new PlainPaneSetup();
  };

  void setPaneSetupFactory(PaneSetupFactory factory) {
    this.paneSetups = factory;
  }

  /** The Compare editor's site; null outside a workbench part (the full Java editor needs it). */
  private IEditorSite editorSite() {
    var part = getContainer() == null ? null : getContainer().getWorkbenchPart();
    return part != null && part.getSite() instanceof IEditorSite s ? s : null;
  }

  /** Builds the viewer; on failure the setup (and the shared buffer it holds) is released and nothing is left. */
  private MergeViewer createViewer(Composite parent) {
    Set<Control> before = new HashSet<>(Arrays.asList(parent.getChildren()));
    PaneSetup setup = paneSetups.create(file, editorSite(), session);
    try {
      MergeViewer v = new MergeViewer(parent, setup);
      v.setProject(file.getProject());
      v.setInput(session);
      v.setResultTitle(file.getName());
      return v;
    } catch (RuntimeException | Error e) {
      try {
        for (Control c : parent.getChildren()) {
          if (!before.contains(c)) {
            c.dispose(); // a viewer built this far disposes its setup with its control
          }
        }
      } catch (RuntimeException | Error cleanup) {
        e.addSuppressed(cleanup);
      } finally {
        try {
          setup.dispose(); // reverts and disconnects the shared buffer; a second call does nothing
        } catch (RuntimeException | Error cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      throw e;
    }
  }

  @Override
  public Viewer findContentViewer(Viewer oldViewer, ICompareInput input, Composite parent) {
    if (viewer == null || viewer.getControl().isDisposed()) {
      viewer = createViewer(parent);
      ownOutline();
      for (StructureOutlinePage p : List.copyOf(outlinePages)) {
        p.attach(); // an Outline opened before the viewer existed
      }
      if (viewer.sharedBuffer() != null) {
        // saves of the shared buffer from other editors go through this resolver while it holds the buffer
        ForeignSaveGuard guard = ForeignSaveGuard.install(file, this);
        foreignSaveGuard = guard;
        viewer.getControl().addDisposeListener(e -> guard.dispose());
      }
      viewer.addDirtyListener(() -> setDirty(true));
      viewer.setSaveAndMarkResolvedHook(() -> saveAndMarkResolved(new NullProgressMonitor()));
      viewer.setOpenInEGitHook(() -> openInEGitMergeTool(new NullProgressMonitor()),
          () -> RepositoryLocator.locateMapped(file).isPresent());
      viewer.setAgentsHeld(this::agentsHeld);
      viewer.ai().setOffer(this::offerAi);
      viewer.ai().setFilePath(file.getFullPath().toString());
      viewer.setProposalForgetter(id -> {
        ProposalSource source = Agents.source();
        if (source == null) {
          forgottenWhileOff.add(id); // the store is out of reach: told when agents come back
        } else {
          forget(source, id);
        }
      });
      Agents.register(this);
      viewer.getControl().addDisposeListener(e -> Agents.unregister(this));
      agentsChanged(Agents.source());
      IServiceLocator locator = getContainer() == null ? null : getContainer().getServiceLocator();
      if (locator != null) {
        SiteServices site = SiteServices.activate(locator, viewer);
        viewer.setSiteServices(site);
        viewer.getControl().addDisposeListener(e -> site.dispose());
      }
      if (decision == null) {
        decision = SaveDecision.dialogs(parent.getShell());
      }
      if (openInEGitDecision == null) {
        openInEGitDecision = OpenInEGitDecision.dialog(parent.getShell());
      }
    }
    return viewer;
  }

  /**
   * The proposal source of the agents came ({@code source} non-null) or went (agents design §1, §3); on the UI
   * thread. When it comes, the stored proposals of the file are offered and outdated ones reported.
   */
  void agentsChanged(ProposalSource source) {
    if (viewer == null || viewer.getControl().isDisposed()) {
      return;
    }
    boolean wasActive = viewer.agentsActive();
    viewer.setAgentsActive(source != null);
    if (source == null || wasActive) {
      return;
    }
    Set<BlockId> decided = new HashSet<>(forgottenWhileOff);
    forgottenWhileOff.clear();
    for (BlockId id : decided) {
      forget(source, id); // reverted or discarded while agents were off: the store forgets it now
    }
    try {
      ProposalSource.Stored stored = source.stored(file);
      List<Proposal> fresh = new ArrayList<>();
      List<Proposal> shown = new ArrayList<>();
      for (Proposal p : stored.proposals()) {
        if (!decided.contains(p.block())) {
          (stored.shown().contains(p.block()) ? shown : fresh).add(p);
        }
      }
      if (!fresh.isEmpty()) {
        offer(fresh);
      }
      if (!shown.isEmpty()) {
        offerWaiting(shown); // offered to an editor before: the user may have undone or changed them since
      }
      if (stored.stale()) {
        viewer.showAgentNotice(Messages.aiStaleNotice);
      }
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      ILog.of(MergeEditorInput.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
          "The stored proposals of " + file.getFullPath() + " could not be read", JavaBridge.loggable(e)));
    }
  }

  /** Blocks whose proposal the user reverted or discarded while agents were off (the store was out of reach). */
  private final Set<BlockId> forgottenWhileOff = new HashSet<>();

  private void forget(ProposalSource source, BlockId id) {
    try {
      source.forget(file, id);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      // the user's revert or discard stands; the store may offer the proposal again
      ILog.of(MergeEditorInput.class).log(new Status(IStatus.WARNING, PLUGIN_ID, "The AI proposal of block "
          + id + " in " + file.getFullPath() + " could not be forgotten", JavaBridge.loggable(e)));
    }
  }

  /**
   * Saves and finishes in progress (their questions run nested event loops, where calls of agents arrive); while
   * above zero, proposals only wait (agents design §3: a save writes only what the user decided on).
   */
  private int busy;
  private boolean heldRetryScheduled;
  private static final int HELD_RETRY_MS = 250;

  /**
   * True while proposals of agents must not change the model: a save, Save &amp; Mark Resolved or a save from another
   * editor is going on, or a modal dialog is open (such as the "Save changes?" question on close).
   */
  boolean agentsHeld() {
    return busy > 0 || foreignSave != null || otherModalShell();
  }

  private boolean otherModalShell() {
    if (viewer == null || viewer.getControl().isDisposed()) {
      return false;
    }
    Shell own = viewer.getControl().getShell();
    for (Shell s : viewer.getControl().getDisplay().getShells()) {
      if (s != own && !s.isDisposed() && s.isVisible()
          && (s.getStyle() & (SWT.APPLICATION_MODAL | SWT.SYSTEM_MODAL | SWT.PRIMARY_MODAL)) != 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Offers proposals of agents to the open resolver (UI thread). While {@link #agentsHeld()} they only wait, and are
   * offered again once the save or dialog is over.
   */
  List<Offer> offer(List<Proposal> proposals) {
    boolean hold = holdOffers();
    List<Offer> out = viewer.offerAll(proposals, hold);
    if (hold) {
      scheduleHeldRetry();
    }
    return out;
  }

  /**
   * Offers the answer of an AI resolver (resolve-with-ai design §3, UI thread) like a proposal of an agent: held
   * while a save, a dialog or a content assist popup is open, and offered again afterwards.
   */
  Offer offerAi(Proposal proposal) {
    boolean hold = holdOffers();
    Offer o = viewer.offerAi(proposal, hold);
    if (hold) {
      scheduleHeldRetry();
    }
    return o;
  }

  /** Like {@link #offer}, for stored proposals an editor was offered before: they only wait for the user. */
  private void offerWaiting(List<Proposal> proposals) {
    boolean hold = holdOffers();
    viewer.offerWaiting(proposals, hold);
    if (hold) {
      scheduleHeldRetry();
    }
  }

  /**
   * Offers wait while {@link #agentsHeld()}, and also while the user has a content assist or quick fix popup open
   * in the result: applying would close it under the user's typing.
   */
  private boolean holdOffers() {
    return agentsHeld() || viewer != null && !viewer.getControl().isDisposed() && viewer.resultPopupOpen();
  }

  private void scheduleHeldRetry() {
    if (heldRetryScheduled || viewer == null || viewer.getControl().isDisposed()) {
      return;
    }
    heldRetryScheduled = true;
    viewer.getControl().getDisplay().timerExec(HELD_RETRY_MS, () -> {
      heldRetryScheduled = false;
      if (viewer == null || viewer.getControl().isDisposed() || !viewer.hasHeld()) {
        return;
      }
      if (holdOffers()) {
        scheduleHeldRetry();
      } else {
        viewer.releaseHeld();
      }
    });
  }

  @Override
  public void saveChanges(IProgressMonitor monitor) throws CoreException {
    busy++;
    try {
      saveChangesNow(monitor);
    } finally {
      busy--;
    }
  }

  private void saveChangesNow(IProgressMonitor monitor) {
    boolean saved;
    try {
      saved = saveResult(monitor);
    } catch (CoreException e) {
      // CompareEditor.doSave only shows an error and returns, and a save-on-close then goes ahead and drops the
      // resolution: report here and cancel like a declined save, so the editor stays open and dirty
      errorReporter.accept(Messages.saveFailedTitle, e.getStatus());
      saved = false;
    }
    if (!saved) {
      // CompareEditor.doSave swallows this cancellation and skips its dirty-state update;
      // the cancelled monitor tells a save-on-close flow to keep the editor open
      if (monitor != null) {
        monitor.setCanceled(true);
      }
      throw new OperationCanceledException();
    }
  }

  /** Shows an error under a title; replaceable for tests. */
  private BiConsumer<String, IStatus> errorReporter = (title, status) -> {
    var shell = viewer != null && !viewer.getControl().isDisposed() ? viewer.getControl().getShell() : null;
    ErrorDialog.openError(shell, title, null, status);
  };

  void setErrorReporter(Consumer<IStatus> reporter) {
    this.errorReporter = (title, status) -> reporter.accept(status);
  }

  void setTitledErrorReporter(BiConsumer<String, IStatus> reporter) {
    this.errorReporter = reporter;
  }

  /**
   * Writes the result, asking how while conflicts remain unresolved.
   *
   * @return false when the user cancelled; nothing was written and the editor stays dirty
   */
  public boolean saveResult(IProgressMonitor monitor) throws CoreException {
    busy++;
    try {
      SaveDecision.SaveChoice choice = choose();
      if (choice == SaveDecision.SaveChoice.CANCEL) {
        return false;
      }
      write(choice == SaveDecision.SaveChoice.MARKERS, monitor);
      return true;
    } finally {
      busy--;
    }
  }

  /**
   * Asks how to save while conflicts remain unresolved; without them the current text is saved. During a save
   * started by another editor its answer holds, so Save All (that editor, then the resolver) asks once.
   */
  private SaveDecision.SaveChoice choose() {
    if (foreignSave != null) {
      return foreignSave.choice;
    }
    int unresolved = session.model().unresolvedConflicts();
    return unresolved > 0 ? decision.onUnresolved(unresolved) : SaveDecision.SaveChoice.CURRENT_TEXT;
  }

  private ForeignSave foreignSave;

  /**
   * A save of the shared buffer started by another editor: the user's answer for all its commits (save actions
   * commit again). Each commit writes what the answer says; a cancelled save writes the file's own text back, so
   * its bytes stay as they are.
   *
   * <p>With the current text the save actions apply as in any Java editor: their edits reach the model. With
   * markers or cancel the result is held: edits of the shared document do not reach the model until the save is
   * over, then the result text from before the save is put back. Typing in the resolver while held (possible only
   * in a nested event loop of a save action, such as a progress dialog) is dropped the same way.
   */
  final class ForeignSave {
    private final SaveDecision.SaveChoice choice;
    private final MergeViewer.Hold hold;
    private boolean failed;

    private ForeignSave(SaveDecision.SaveChoice choice) {
      this.choice = choice;
      this.hold = choice == SaveDecision.SaveChoice.CURRENT_TEXT ? null : viewer.holdResult();
      session.model().setFormattingElsewhere(true); // its save actions may reformat: whitespace keeps block states
    }

    /** Puts the text to write into the buffer; the returned action runs after the commit. */
    MergeViewer.FileText beforeCommit() {
      if (choice == SaveDecision.SaveChoice.CANCEL) {
        return refuseForeignCommit();
      }
      boolean markers = choice == SaveDecision.SaveChoice.MARKERS;
      MergeViewer.FileText shown = viewer.showFileText(render(markers), markers);
      return committed -> {
        shown.done(committed && markers);
        if (committed) {
          setDirty(false);
        } else {
          failed = true;
        }
      };
    }

    /** The save and all its commits are over: the result shows its own text again and edits reach the model. */
    void end() {
      if (foreignSave == this) {
        foreignSave = null;
      }
      boolean saved = choice != SaveDecision.SaveChoice.CANCEL && !failed;
      try {
        session.model().setFormattingElsewhere(false);
        if (hold != null) {
          hold.release(hold.shown(), saved);
        }
      } finally {
        if (saved) {
          setDirty(false); // putting the result back, or the save actions, are no unsaved edit
        } else if (failed) {
          setDirty(true);
        }
      }
    }
  }

  private ForeignSaveGuard foreignSaveGuard;

  /** The guard of the shared buffer of the last viewer, or null; for tests. */
  ForeignSaveGuard foreignSaveGuard() {
    return foreignSaveGuard;
  }

  /** True while a save started by another editor is going on (its save actions may still commit). */
  boolean foreignSaveInProgress() {
    return foreignSave != null;
  }

  /** Starts a save from another editor; asks the save question unless this UI event already has an answer. */
  ForeignSave beginForeignSave() {
    busy++; // until the save is over, foreignSave holds the proposals
    try {
      foreignSave = new ForeignSave(choose());
      return foreignSave;
    } finally {
      busy--;
    }
  }

  /**
   * Lets a commit started elsewhere write only what the file already holds; the shared document stays dirty. If
   * the file cannot be read, the commit writes markers rather than the base text of unresolved blocks.
   */
  MergeViewer.FileText refuseForeignCommit() {
    String text;
    try {
      text = diskText();
    } catch (CoreException | RuntimeException e) {
      ILog.of(MergeEditorInput.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
          "Could not keep " + file.getFullPath() + " byte for byte; a save from another editor writes conflict markers",
          e));
      text = render(true);
    }
    MergeViewer.FileText shown = viewer.showFileText(text, true);
    return committed -> shown.done(false);
  }

  /** Last resort for a commit started elsewhere: it writes unresolved blocks as markers, never as base text. */
  MergeViewer.FileText writeMarkersInstead() {
    MergeViewer.FileText shown = viewer.showFileText(render(true), true);
    return committed -> shown.done(false);
  }

  /**
   * The file's current text in the shared buffer's charset, which a commit writes back byte for byte. Fails when
   * the bytes do not survive decoding and encoding again (malformed or unmappable for the charset): a commit of
   * the decoded text would change the file.
   */
  private String diskText() throws CoreException {
    SharedBuffer shared = viewer.sharedBuffer();
    byte[] raw;
    try (var in = file.getContents(true)) {
      raw = in.readAllBytes();
    } catch (IOException e) {
      throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, "Could not read " + file.getFullPath(), e));
    }
    Charset charset = Charset.forName(shared.encoding());
    String text;
    byte[] again;
    try {
      text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
      ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
      again = new byte[encoded.remaining()];
      encoded.get(again);
    } catch (CharacterCodingException e) {
      throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
          "The bytes of " + file.getFullPath() + " are not valid " + charset.name(), e));
    }
    if (!Arrays.equals(raw, again)) {
      throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
          "The bytes of " + file.getFullPath() + " do not come back the same through " + charset.name()));
    }
    return text;
  }

  private boolean trailingNewline() {
    if (viewer == null || viewer.getControl().isDisposed()) {
      return session.trailingNewline();
    }
    String text = viewer.pane(PaneKind.RESULT).viewer().getDocument().get();
    return session.trailingNewline() && (text.isEmpty() || text.endsWith("\n"));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MergeEditorInput other && other.file.equals(file);
  }

  @Override
  public int hashCode() {
    return file.hashCode();
  }

  /** The file text a save writes: unresolved blocks as markers, or the current text. */
  private String render(boolean markers) {
    var model = session.model();
    if (markers) {
      MarkerConfig mc = session.markers();
      Charset target = targetCharset();
      return ResultWriter.render(model, mc.style(), markerLabel(session.labels().left(), target), mc.baseLabel(),
          markerLabel(session.labels().right(), target),
          ConflictMarkerParser.DEFAULT_MARKER_SIZE, session.eol(), trailingNewline());
    }
    return LineText.render(LineText.chunkLines(model.document().chunk(0, model.document().lineCount())),
        session.eol(), trailingNewline());
  }

  /** The charset the result is written in. */
  private Charset targetCharset() {
    if (session.charset() != null) {
      return session.charset();
    }
    SharedBuffer shared = viewer != null && !viewer.getControl().isDisposed() ? viewer.sharedBuffer() : null;
    try {
      return Charset.forName(shared != null ? shared.encoding() : file.getCharset());
    } catch (CoreException e) {
      return java.nio.charset.StandardCharsets.UTF_8; // only the cosmetic marker labels depend on it here
    }
  }

  /**
   * A marker label (a branch name) as text for {@code target}. Labels are cosmetic, so they never stop a save: in
   * the ISO-8859-1 fallback they are always the UTF-8 bytes git writes, read as ISO-8859-1 (also for a label
   * ISO-8859-1 could hold); otherwise characters the charset cannot hold become '?'.
   */
  static String markerLabel(String label, Charset target) {
    if (target.equals(Decoding.FALLBACK)) {
      return new String(label.getBytes(java.nio.charset.StandardCharsets.UTF_8), Decoding.FALLBACK);
    }
    if (target.newEncoder().canEncode(label)) {
      return label;
    }
    var encoder = target.newEncoder();
    StringBuilder b = new StringBuilder();
    label.codePoints().forEach(cp -> b.append(encoder.canEncode(new String(Character.toChars(cp)))
        ? new String(Character.toChars(cp)) : "?"));
    return b.toString();
  }

  /** What to tell about the first character of {@code text} that {@code cs} cannot hold. */
  static String unmappableMessage(String text, Charset cs, boolean ownCharset) {
    var encoder = cs.newEncoder();
    int line = 1;
    int col = 1;
    for (int i = 0; i < text.length();) {
      int cp = text.codePointAt(i);
      String ch = new String(Character.toChars(cp));
      if (!encoder.canEncode(ch)) {
        String key = ownCharset ? Messages.saveUnmappableOwn : Messages.saveUnmappable;
        return NLS.bind(key, new Object[] {cs.name(), ch, String.format("U+%04X", cp), line, col});
      }
      if (cp == '\n') {
        line++;
        col = 1;
      } else {
        col++;
      }
      i += Character.charCount(cp);
    }
    return NLS.bind(Messages.saveUnmappable, new Object[] {cs.name(), "?", "", line, col});
  }

  private void write(boolean markers, IProgressMonitor monitor) throws CoreException {
    String text = render(markers);
    SharedBuffer shared = viewer != null && !viewer.getControl().isDisposed() ? viewer.sharedBuffer() : null;
    if (shared != null) {
      // through the buffer that open editors share; with markers the result keeps showing its own text
      // only a text without open conflicts goes through the Java Save Actions (preferences design §4)
      boolean actions = !markers && MergePreferences.saveActions() && session.model().unresolvedConflicts() == 0;
      // as for another editor's save: the actions' whitespace changes keep every block's state
      session.model().setFormattingElsewhere(actions);
      try {
        viewer.commitResult(text, markers, actions, monitor);
      } finally {
        session.model().setFormattingElsewhere(false);
      }
    } else {
      Charset cs = session.charset() != null ? session.charset() : Charset.forName(file.getCharset());
      byte[] bytes;
      try {
        bytes = Decoding.encode(text, cs);
      } catch (CharacterCodingException e) {
        throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID,
            unmappableMessage(text, cs, session.charset() == null), e));
      }
      file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
    }
    setDirty(false);
    AttentionMarkers.saved(file, session.model());
  }

  private OpenInEGitDecision openInEGitDecision;

  void setOpenInEGitDecision(OpenInEGitDecision d) {
    this.openInEGitDecision = d;
  }

  /**
   * ⋯ More › Open in EGit Merge Tool (docs/INSTALL.md, "Back to EGit"): opens EGit's own Merge Tool on the file, never
   * AfterGit again, and then closes the resolver. Unsaved changes are first saved (the working-tree file only, as
   * Save does: nothing is staged), or dropped by that close, or the user cancels. Nothing is dropped and the resolver
   * stays open when EGit cannot take the file: a project EGit does not manage is refused before asking, and the
   * resolver closes only after EGit's command ran.
   *
   * @return false when the resolver stays open (cancelled, the save failed or was cancelled, or EGit refused)
   */
  boolean openInEGitMergeTool(IProgressMonitor monitor) {
    if (RepositoryLocator.locateMapped(file).isEmpty()) {
      reportEGitRefused();
      return false;
    }
    busy++;
    try {
      if (isDirty()) {
        switch (openInEGitDecision.onUnsaved(file.getName())) {
          case CANCEL -> {
            return false;
          }
          case SAVE_AND_OPEN -> {
            try {
              if (!saveResult(monitor)) {
                return false;
              }
            } catch (CoreException e) {
              errorReporter.accept(Messages.saveFailedTitle, e.getStatus());
              return false;
            }
          }
          case DISCARD_AND_OPEN -> {
            // the close after EGit opened drops them (and reverts a shared buffer)
          }
        }
      }
    } finally {
      busy--;
    }
    if (!EGitMergeTool.open(file)) {
      reportEGitRefused(); // EGit's handler was disabled for the file: nothing was dropped
      return false;
    }
    IWorkbenchPart part = getContainer() == null ? null : getContainer().getWorkbenchPart();
    if (part instanceof IEditorPart editor && PlatformUI.isWorkbenchRunning()) {
      // the menu selection that started this is still being handled inside the editor
      PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
        IWorkbenchPage page = editor.getSite() == null ? null : editor.getSite().getPage();
        if (page != null && viewer != null && !viewer.getControl().isDisposed()) {
          page.closeEditor(editor, false);
        }
      });
    }
    return true;
  }

  private void reportEGitRefused() {
    errorReporter.accept(Messages.openInEGitTitle,
        new Status(IStatus.INFO, PLUGIN_ID, NLS.bind(Messages.openInEGitRefused, file.getName())));
  }

  /** What Save & Mark Resolved did (ui-polish design §3). */
  public enum FinishOutcome {
    /** Nothing was written or staged. */
    CANCELLED,
    /** The save failed: nothing written or staged; the editor stays open and dirty. */
    SAVE_FAILED,
    /** Saved as Ctrl+S saves, not staged; the editor stays open. */
    SAVED,
    /** Saved with the open conflicts as markers, not staged; the resolver closes. */
    SAVED_WITH_MARKERS,
    /** Saved and staged; the resolver closes and the status line says so. */
    RESOLVED,
    /** Saved, but staging failed: the error is shown and the editor stays open. */
    STAGE_FAILED
  }

  /**
   * Save & Mark Resolved (ui-polish design §3). Without open conflicts: save, {@code git add}, close, and a message
   * in the status line; unreviewed auto-resolved blocks are named there but never stop it. With open conflicts the
   * user chooses Cancel, Save without staging or Save with markers. Errors are shown, never thrown. Without open
   * conflicts it first waits up to {@link #DIAGNOSTICS_WAIT_MILLIS} for a language server's answer to the current
   * text; blocks that do not compile are named in the status line, and so is a server that did not answer in time.
   */
  public FinishOutcome saveAndMarkResolved(IProgressMonitor monitor) {
    if (finishing) {
      return FinishOutcome.CANCELLED; // a second click while the first waits for the language server
    }
    finishing = true;
    busy++;
    try {
      return finish(monitor);
    } finally {
      busy--;
      finishing = false;
    }
  }

  /** How long Save &amp; Mark Resolved waits for a language server's answer (languages design, Part 2 Ruling 3). */
  static final long DIAGNOSTICS_WAIT_MILLIS = 2000;
  private boolean finishing;

  /** The note of a Save &amp; Mark Resolved that decided before the language server was done. */
  static String notCheckedNotice() {
    return MessageFormat.format(Messages.finishNotChecked, DIAGNOSTICS_WAIT_MILLIS / 1000);
  }

  private FinishOutcome finish(IProgressMonitor monitor) {
    int unresolved = session.model().unresolvedConflicts();
    boolean checked = true;
    if (unresolved == 0 && viewer != null && !viewer.getControl().isDisposed()) {
      // the marks of the current text: a language server answers later than the edit (languages design, Part 2)
      checked = viewer.awaitDiagnostics(DIAGNOSTICS_WAIT_MILLIS);
      if (viewer.getControl().isDisposed()) {
        return FinishOutcome.CANCELLED; // the resolver was closed while it waited
      }
      unresolved = session.model().unresolvedConflicts(); // the events of the wait may have reverted a block
    }
    int notCompiling = viewer == null || viewer.getControl().isDisposed() ? 0 : viewer.notCompilingCount();
    if (unresolved > 0) {
      SaveDecision.FinishChoice choice = switch (MergePreferences.finishPolicy()) {
        case ASK -> decision.onFinishWithConflicts(unresolved);
        case SAVE_WITH_MARKERS -> SaveDecision.FinishChoice.SAVE_WITH_MARKERS;
        case SAVE_WITHOUT_STAGING -> SaveDecision.FinishChoice.SAVE_WITHOUT_STAGING;
      };
      return switch (choice) {
        case CANCEL -> FinishOutcome.CANCELLED;
        case SAVE_WITHOUT_STAGING -> saveWithoutStaging(monitor);
        case SAVE_WITH_MARKERS -> saveWithMarkers(monitor);
      };
    }
    try {
      write(false, monitor);
    } catch (CoreException e) {
      // write() failed before setDirty(false): the editor stays dirty
      errorReporter.accept(Messages.saveFailedTitle, e.getStatus());
      return FinishOutcome.SAVE_FAILED;
    }
    try {
      GitIndex.markResolved(repoFile.repository(), repoFile.path());
    } catch (GitAPIException | RuntimeException e) {
      errorReporter.accept(Messages.stageFailedTitle,
          new Status(IStatus.ERROR, PLUGIN_ID, NLS.bind(Messages.stageFailedFor, file.getName()), e));
      refreshQuietly(monitor);
      return FinishOutcome.STAGE_FAILED;
    }
    refreshQuietly(monitor);
    dropStoredProposals();
    int notReviewedAuto = 0;
    int notReviewedAi = 0;
    for (MergeBlock b : session.model().blocks()) {
      if (b.needsReview() && b.state() == BlockState.AI_RESOLVED) {
        notReviewedAi++;
      } else if (b.needsReview()) {
        notReviewedAuto++;
      }
    }
    String message = MergeViewer.joinNotices(MergeViewer.joinNotices(NLS.bind(Messages.finishResolved, file.getName()),
        notReviewedAuto == 0 ? null : MessageFormat.format(Messages.finishNotReviewed, notReviewedAuto)),
        notReviewedAi == 0 ? null : MessageFormat.format(Messages.finishAiNotReviewed, notReviewedAi));
    // like the unreviewed blocks, a warning that never stops the action (ui-polish design §3)
    message = MergeViewer.joinNotices(MergeViewer.joinNotices(message,
        notCompiling == 0 ? null : MessageFormat.format(Messages.finishNotCompiling, notCompiling)),
        checked ? null : notCheckedNotice());
    if (MergePreferences.closeAfterFinish()) {
      // the index holds the saved text; an edit made before the close keeps the resolver open (never dropped)
      closeEditor(message, MessageFormat.format(Messages.finishStagedKeptOpen, file.getName()));
    } else {
      statusReporter.accept(message);
    }
    return FinishOutcome.RESOLVED;
  }

  /**
   * The file is staged: its stored proposals of agents go (agents design §1). While agents are off the store is out
   * of reach; the server deletes such records when it starts again.
   */
  private void dropStoredProposals() {
    ProposalSource source = Agents.source();
    if (source == null) {
      return;
    }
    try {
      source.resolved(file);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      ILog.of(MergeEditorInput.class).log(new Status(IStatus.WARNING, PLUGIN_ID, "The stored AI proposals of "
          + file.getFullPath() + " could not be deleted", JavaBridge.loggable(e)));
    }
  }

  private FinishOutcome saveWithoutStaging(IProgressMonitor monitor) {
    try {
      return saveResult(monitor) ? FinishOutcome.SAVED : FinishOutcome.CANCELLED;
    } catch (CoreException e) {
      errorReporter.accept(Messages.saveFailedTitle, e.getStatus());
      return FinishOutcome.SAVE_FAILED;
    }
  }

  private FinishOutcome saveWithMarkers(IProgressMonitor monitor) {
    try {
      write(true, monitor); // markers in the repository's merge.conflictStyle (session.markers())
    } catch (CoreException e) {
      errorReporter.accept(Messages.saveFailedTitle, e.getStatus());
      return FinishOutcome.SAVE_FAILED;
    }
    if (MergePreferences.closeAfterFinish()) {
      closeEditor(null, MessageFormat.format(Messages.finishKeptOpen, file.getName()));
    } else {
      statusReporter.accept(NLS.bind(Messages.finishSavedWithMarkers, file.getName()));
    }
    return FinishOutcome.SAVED_WITH_MARKERS;
  }

  private void refreshQuietly(IProgressMonitor monitor) {
    try {
      file.refreshLocal(IResource.DEPTH_ZERO, monitor);
    } catch (CoreException e) {
      ILog.of(MergeEditorInput.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
          "Could not refresh " + file.getFullPath() + " after saving", e));
    }
  }

  /**
   * Closes the resolver after the current event (it was saved, so Eclipse asks nothing) and then reports
   * {@code closed} (if any). If the resolver became dirty again before that (typing, undo, or an edit of the shared
   * document in another editor), it stays open, so nothing is lost, and {@code keptOpen} is reported instead.
   * Nothing is reported when there is no editor to close or it is already gone.
   */
  private void closeEditor(String closed, String keptOpen) {
    IWorkbenchPart part = getContainer() == null ? null : getContainer().getWorkbenchPart();
    if (!(part instanceof IEditorPart editor) || !PlatformUI.isWorkbenchRunning()) {
      return;
    }
    // the click that started this is still being handled inside the editor
    PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
      IWorkbenchPage page = editor.getSite() == null ? null : editor.getSite().getPage();
      if (page == null || viewer == null || viewer.getControl().isDisposed()) {
        return; // already closed
      }
      if (editor.isDirty()) {
        statusReporter.accept(keptOpen);
      } else if (page.closeEditor(editor, false) && closed != null) {
        statusReporter.accept(closed);
      }
    });
  }

  private Consumer<String> statusReporter = MergeEditorInput::showInStatusLine;

  void setStatusReporter(Consumer<String> reporter) {
    this.statusReporter = reporter;
  }

  /** Shows {@code message} in the status line of the part that is active once the resolver has closed. */
  static void showInStatusLine(String message) {
    IWorkbenchWindow window = PlatformUI.isWorkbenchRunning()
        ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;
    IWorkbenchPage page = window == null ? null : window.getActivePage();
    IWorkbenchPart part = page == null ? null : page.getActivePart();
    IWorkbenchPartSite site = part == null ? null : part.getSite();
    IActionBars bars = site instanceof IViewSite v ? v.getActionBars()
        : site instanceof IEditorSite e ? e.getActionBars() : null;
    if (bars != null) {
      bars.getStatusLineManager().setMessage(message);
    }
  }
}
