package net.aftergit.merge.ui;

import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;

/**
 * While the resolver holds the file's shared buffer, a save of the file from another editor (Save, Save All,
 * saving on close) goes through the resolver: it asks its save question and the commit writes what the user
 * chose (markers or current text), never the base text of unresolved blocks silently. A cancelled or impossible
 * save writes the file's own text back, so the file stays byte for byte as it was.
 *
 * <p>Every one of those saves ends in the buffer's commit, which tells its listeners {@code stateChanging} on the
 * committing thread before it writes the document. The workbench offers no veto for another part's save, so the
 * guard sets the document to the text to write at that moment and shows the result again when the commit is done.
 */
final class ForeignSaveGuard implements IFileBufferListener {
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  private final IFile file;
  private final MergeEditorInput owner;
  private final SharedBuffer shared;
  private volatile MergeViewer.FileText pending;
  /** Commits announced by stateChanging that have not landed yet (a commit may run on another thread). */
  private final AtomicInteger inFlight = new AtomicInteger();
  /** Of those, the commits that run on another thread than the UI's. */
  private final AtomicInteger inFlightOffUi = new AtomicInteger();
  /**
   * Ticks of {@link #endSave} in a row that found commits in flight with no commit or save on the UI thread's
   * stack: a UI commit that neither wrote nor failed (an Error escaped it) is never going to signal.
   */
  private int staleTicks;
  /**
   * When the newest commit on another thread was prepared, after the save question was answered (System.nanoTime):
   * the resolver waits for such a commit from then on, not from the start of the save or from its announcement, so
   * neither the time the user takes to answer nor earlier commits count.
   */
  private volatile long offUiSince;
  private static final int STALE_TICKS = 4;
  /** Bound for a commit on another thread that never signals. */
  private static final long OFF_UI_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
  private static volatile long offUiTimeoutNanos = OFF_UI_TIMEOUT_NANOS;
  /** Commits on another thread that the guard gave up on; one of them may still write the file. */
  private final AtomicInteger abandonedOffUi = new AtomicInteger();

  /** Tests: how long a commit on another thread may stay in flight; negative restores the default. */
  static void setOffUiTimeoutForTests(long millis) {
    offUiTimeoutNanos = millis < 0 ? OFF_UI_TIMEOUT_NANOS : java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
  }
  /** The save in progress: one answer for all its commits (save actions commit again). */
  private MergeEditorInput.ForeignSave save;
  private boolean asking;
  private volatile boolean disposed;
  private boolean reportedUnmatched;
  private volatile boolean finishing;

  private ForeignSaveGuard(IFile file, MergeEditorInput owner, SharedBuffer shared) {
    this.file = file;
    this.owner = owner;
    this.shared = shared;
  }

  /** Guards the shared buffer of {@code owner}'s result until {@link #dispose} or the buffer's release. */
  static ForeignSaveGuard install(IFile file, MergeEditorInput owner) {
    SharedBuffer shared = owner.viewer().sharedBuffer();
    if (shared == null) {
      throw new IllegalStateException("The result of " + file.getFullPath() + " is not shared");
    }
    ForeignSaveGuard g = new ForeignSaveGuard(file, owner, shared);
    FileBuffers.getTextFileBufferManager().addFileBufferListener(g);
    shared.onRelease(g::dispose);
    return g;
  }

  void dispose() {
    if (!disposed) {
      disposed = true;
      FileBuffers.getTextFileBufferManager().removeFileBufferListener(this);
      try {
        abandonCommits(); // a commit still in flight will not reach this guard any more
      } finally {
        endSave();
      }
    }
  }

  /** Ends the commits in flight as failed: the result shows its own text again and nothing waits for them. */
  private void abandonCommits() {
    if (pending != null || inFlight.get() > 0) {
      finish(false);
    }
    inFlight.set(0);
    // such a commit may still land and write the document; one that finishes meanwhile is not counted twice
    abandonedOffUi.addAndGet(inFlightOffUi.getAndSet(0));
    staleTicks = 0;
  }

  @Override
  public void stateChanging(IFileBuffer buffer) {
    if (disposed || !shared.holds(buffer) || shared.isCommitting() || !isCommit()) {
      return; // a revert, a state validation, a change on disk, or the resolver's own save
    }
    announced();
  }

  /** Tells the guard of a commit as stateChanging does, as if the stack showed one; for tests. */
  void announceCommitForTests() {
    announced();
  }

  /** Commits announced that have not written or failed yet; for tests. */
  int inFlight() {
    return inFlight.get();
  }

  private void announced() {
    boolean offUi = Display.getCurrent() == null;
    inFlight.incrementAndGet();
    if (offUi) {
      inFlightOffUi.incrementAndGet();
    }
    onUi(() -> beforeCommit(offUi));
  }

  /**
   * Prepares the announced commit. When an Error escapes before a save began, nothing would ever end this commit
   * for the guard (only a begun save is ended): it is taken back before the Error goes on.
   */
  private void beforeCommit(boolean offUi) {
    boolean prepared = false;
    try {
      prepareCommit();
      prepared = true;
      if (offUi) {
        offUiSince = System.nanoTime(); // the question is answered: from now on the resolver waits for the commit
      }
    } finally {
      if (!prepared && save == null) {
        inFlight.updateAndGet(n -> Math.max(0, n - 1));
        if (offUi) {
          inFlightOffUi.updateAndGet(n -> Math.max(0, n - 1));
        }
      }
    }
  }

  private void prepareCommit() {
    if (disposed) {
      return;
    }
    if (save == null) {
      if (asking) {
        // a second save while the question is open: it may write only what the file holds
        refuse(true);
        return;
      }
      asking = true;
      try {
        save = owner.beginForeignSave();
      } catch (RuntimeException e) {
        ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
            "Save of " + file.getFullPath() + " from another editor could not go through the resolver", e));
        refuse(true);
        return;
      } finally {
        asking = false;
      }
      // the save, its save actions and its other commits run before the next event
      staleTicks = 0;
      Display.getCurrent().asyncExec(this::endSave);
    }
    try {
      pending = save.beforeCommit();
    } catch (RuntimeException e) {
      ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
          "Save of " + file.getFullPath() + " from another editor could not go through the resolver", e));
      refuse(false);
    }
  }

  private void endSave() {
    if (save != null && !disposed && (inFlight.get() > 0 || stillSaving())) {
      if (!commitLost()) {
        // a commit has not written yet (it may run on another thread), or a save action runs a nested event loop
        // (organize imports shows progress): the save is not over
        Display.getCurrent().timerExec(50, this::endSave);
        return;
      }
      ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.WARNING, PLUGIN_ID, "A save of " + file.getFullPath()
          + " from another editor neither wrote nor failed; the resolver no longer waits for it"));
      abandonCommits();
    }
    MergeEditorInput.ForeignSave s = save;
    save = null;
    if (s != null) {
      onUi(s::end);
    }
  }

  /**
   * True when the commits in flight will never signal: on the UI thread none is on the stack any more for a few
   * ticks, or one on another thread has not landed in a long time.
   */
  private boolean commitLost() {
    if (inFlight.get() == 0) {
      staleTicks = 0;
      return false;
    }
    if (inFlightOffUi.get() > 0) {
      return System.nanoTime() - offUiSince > offUiTimeoutNanos;
    }
    staleTicks = stillSaving() ? 0 : staleTicks + 1;
    return staleTicks >= STALE_TICKS;
  }

  /** Lets the commit write only what the file holds; {@code tell}: say where to save instead. */
  private void refuse(boolean tell) {
    try {
      pending = owner.refuseForeignCommit();
    } catch (RuntimeException e) {
      ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
          "Could not keep " + file.getFullPath() + " as it is; the save writes conflict markers", e));
      try {
        pending = owner.writeMarkersInstead();
      } catch (RuntimeException e2) {
        ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
            "Could not write conflict markers into " + file.getFullPath(), e2));
      }
    }
    Control c = owner.viewer() == null ? null : owner.viewer().getControl();
    if (tell && c != null && !c.isDisposed()) {
      c.getDisplay().asyncExec(() -> {
        if (!c.isDisposed()) {
          MessageDialog.openInformation(c.getShell(), Messages.foreignSaveTitle, Messages.foreignSaveRefused);
        }
      });
    }
  }

  @Override
  public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
    if (isDirty || !shared.holds(buffer)) {
      return;
    }
    if (finishing) {
      return; // putting the result back reports the buffer clean again
    }
    if (pending != null || inFlight.get() > 0) {
      finish(true); // the commit has written the document
    } else if (!disposed && !shared.isCommitting() && abandonedOffUi.get() > 0) {
      lateCommitLanded();
    } else if (!finishing && !disposed && !shared.isCommitting() && !reportedUnmatched && isCommit()) {
      reportedUnmatched = true; // a commit that stateChanging did not recognize
      ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
          "A save of " + file.getFullPath() + " was not routed through the resolver"));
    }
  }

  /** A commit the guard gave up on has written the file after all: the document may differ from it now. */
  private void lateCommitLanded() {
    abandonedOffUi.updateAndGet(n -> Math.max(0, n - 1));
    shared.markDiverged(); // closing the resolver shows the file again
    ILog.of(ForeignSaveGuard.class).log(new Status(IStatus.WARNING, PLUGIN_ID, "A save of " + file.getFullPath()
        + " from another editor wrote the file after the resolver had stopped waiting for it"));
  }

  @Override
  public void stateChangeFailed(IFileBuffer buffer) {
    if (!shared.holds(buffer) || finishing) {
      return;
    }
    if (pending == null && inFlight.get() == 0 && abandonedOffUi.get() > 0) {
      abandonedOffUi.updateAndGet(n -> Math.max(0, n - 1)); // a commit given up on failed after all
      return;
    }
    finish(false);
  }

  private void finish(boolean committed) {
    MergeViewer.FileText p = pending;
    pending = null;
    try {
      if (p != null) {
        finishing = true; // putting the result back reports the buffer clean again
        try {
          onUi(() -> p.done(committed));
        } finally {
          finishing = false;
        }
      }
    } finally {
      inFlight.updateAndGet(n -> Math.max(0, n - 1));
      if (Display.getCurrent() == null) {
        inFlightOffUi.updateAndGet(n -> Math.max(0, n - 1));
      }
    }
  }

  /**
   * True when the nearest file buffer method on the stack is a commit. Buffers announce reverts, state
   * validation and changes on disk with the same {@code stateChanging}.
   */
  private static boolean isCommit() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
        .filter(f -> IFileBuffer.class.isAssignableFrom(f.getDeclaringClass()))
        .findFirst()
        .map(f -> f.getMethodName().startsWith("commit") && !f.getMethodName().equals("commitFileBufferContent"))
        .orElse(false));
  }

  /** True while a document provider's save or a buffer commit is on the stack (a nested event loop runs). */
  private static boolean stillSaving() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames.anyMatch(f ->
        IDocumentProvider.class.isAssignableFrom(f.getDeclaringClass()) && f.getMethodName().startsWith("save")
            || IFileBuffer.class.isAssignableFrom(f.getDeclaringClass()) && f.getMethodName().startsWith("commit")));
  }

  /** Document and dialogs belong to the UI thread; a commit from elsewhere waits for it. */
  private static void onUi(Runnable r) {
    if (Display.getCurrent() != null) {
      r.run();
    } else {
      PlatformUI.getWorkbench().getDisplay().syncExec(r);
    }
  }

  @Override
  public void bufferCreated(IFileBuffer buffer) {}

  @Override
  public void bufferDisposed(IFileBuffer buffer) {}

  @Override
  public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {}

  @Override
  public void bufferContentReplaced(IFileBuffer buffer) {}

  @Override
  public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {}

  @Override
  public void underlyingFileMoved(IFileBuffer buffer, IPath path) {}

  @Override
  public void underlyingFileDeleted(IFileBuffer buffer) {}
}
