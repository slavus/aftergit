package net.aftergit.merge.ui;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

/**
 * One connection to the workspace file buffer of the conflicted file: the document every text or Java editor
 * of the file shows (spec §7.1). Nothing reaches the disk before {@link #commit}.
 */
final class SharedBuffer {
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  private final IPath path;
  private final ITextFileBuffer buffer;
  private boolean released;
  private boolean committing;
  /** The document shows other text than the file although the buffer is clean (markers saved, a late commit). */
  private volatile boolean diverged;
  private Runnable onRelease;
  /** Holds the save participants of a language integration while committing; returns what resumes them. */
  private java.util.function.Supplier<Runnable> commitHold;

  private SharedBuffer(IPath path, ITextFileBuffer buffer) {
    this.path = path;
    this.buffer = buffer;
  }

  static SharedBuffer connect(IFile file) throws CoreException {
    ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
    IPath path = file.getFullPath();
    manager.connect(path, LocationKind.IFILE, null);
    ITextFileBuffer b = manager.getTextFileBuffer(path, LocationKind.IFILE);
    if (b == null) {
      manager.disconnect(path, LocationKind.IFILE, null);
      throw new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, "No text buffer for " + path));
    }
    // the document belongs to the UI thread: a change of the buffer from a job (a refactoring, a refresh of the
    // file) runs in the manager's synchronization context then, as with the editors' own connections
    b.requestSynchronizationContext();
    return new SharedBuffer(path, b);
  }

  IDocument document() {
    return buffer.getDocument();
  }

  boolean isDirty() {
    return buffer.isDirty();
  }

  boolean isSynchronized() {
    return buffer.isSynchronized();
  }

  /** Writes the document to the file in the buffer's charset; editor save actions do not run. */
  void commit(IProgressMonitor monitor) throws CoreException {
    commit(monitor, false);
  }

  /** Like {@link #commit(IProgressMonitor)}, through the Java Save Actions first when {@code saveActions}. */
  void commit(IProgressMonitor monitor, boolean saveActions) throws CoreException {
    committing = true;
    diverged = false;
    java.util.function.Supplier<Runnable> hold = commitHold;
    Runnable resume = hold == null ? null : hold.get();
    try {
      if (!saveActions || !JavaBridge.saveWithActions(ResourcesPlugin.getWorkspace().getRoot().getFile(path), monitor)) {
        buffer.commit(monitor, true);
      }
    } finally {
      try {
        if (resume != null) {
          resume.run();
        }
      } finally {
        committing = false;
      }
    }
  }

  /**
   * Runs {@code hold} before each commit and what it returns after it: a language integration keeps its save
   * participants (a language server's edits before a save) out of the commit, so the file gets the text exactly.
   */
  void holdDuringCommit(java.util.function.Supplier<Runnable> hold) {
    this.commitHold = hold;
  }

  /** True while {@link #commit} runs: a commit seen meanwhile is ours, not another editor's. */
  boolean isCommitting() {
    return committing;
  }

  /** True if {@code b} is this connection's buffer. */
  boolean holds(IFileBuffer b) {
    return b == buffer;
  }

  /** The charset the buffer writes the file in. */
  String encoding() {
    return buffer.getEncoding();
  }

  /**
   * Right after a commit: shows {@code text} again although the file now holds other text, and keeps the buffer
   * clean, because what the file holds is what the user chose to save. The replacement ends with the stamp of the
   * saved state, so the buffer reports clean without first reporting dirty.
   */
  void showSaved(String text) {
    IDocument d = buffer.getDocument();
    long stamp = d instanceof IDocumentExtension4 e ? e.getModificationStamp()
        : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    buffer.setDirty(true); // silently: the stamped replacement below makes the buffer clean again
    TextSpans.replaceMinimal(d, text, stamp);
    buffer.setDirty(false);
    diverged = true;
  }

  /** Runs {@code r} once when this connection ends (close or release). */
  void onRelease(Runnable r) {
    this.onRelease = r;
  }

  /** Cancel: the document shows the file again (unsaved changes are dropped), then the connection is released. */
  void close() {
    if (released) {
      return;
    }
    try {
      runOnRelease();
    } finally {
      try {
        if (buffer.isDirty() || diverged) {
          buffer.revert(null);
        }
      } catch (CoreException e) {
        ILog.of(SharedBuffer.class).log(new Status(IStatus.ERROR, PLUGIN_ID, "Could not restore " + path, e));
      } finally {
        release();
      }
    }
  }

  /** Releases the connection and keeps the document as it is (it belongs to someone else's unsaved edits). */
  void release() {
    if (released) {
      return;
    }
    released = true;
    try {
      runOnRelease();
    } finally {
      try {
        buffer.releaseSynchronizationContext();
      } finally {
        try {
          FileBuffers.getTextFileBufferManager().disconnect(path, LocationKind.IFILE, null);
        } catch (CoreException e) {
          ILog.of(SharedBuffer.class).log(new Status(IStatus.ERROR, PLUGIN_ID, "Could not release " + path, e));
        }
      }
    }
  }

  /** The document may show other text than the file while the buffer is clean: {@link #close} reverts it. */
  void markDiverged() {
    diverged = true;
  }

  boolean isDiverged() {
    return diverged;
  }

  /** A failing hook is logged: it must not keep the connection or the document as they are. */
  private void runOnRelease() {
    Runnable r = onRelease;
    onRelease = null;
    if (r != null) {
      try {
        r.run();
      } catch (RuntimeException e) {
        ILog.of(SharedBuffer.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
            "A release hook of the buffer of " + path + " failed; the buffer is released anyway", e));
      }
    }
  }
}
