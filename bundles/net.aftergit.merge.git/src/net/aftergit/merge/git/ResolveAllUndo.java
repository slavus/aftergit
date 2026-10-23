package net.aftergit.merge.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.RepositoryState;

/**
 * Everything one Resolve All run changed, to stage or to undo (spec §5.5). The UI keeps it until its dialog closes
 * or the next run starts, then {@link #discard()}s it.
 */
public final class ResolveAllUndo {
  /** A file the run wrote; {@code before}/{@code after}: its index entries before the first and after the last stage. */
  private static final class Written {
    final IFile file;
    final RepoFile repoFile;
    final byte[] original;
    final byte[] written;
    IndexSnapshot before;
    IndexSnapshot after;

    Written(IFile file, RepoFile repoFile, byte[] original, byte[] written) {
      this.file = file;
      this.repoFile = repoFile;
      this.original = original;
      this.written = written;
    }
  }

  /**
   * An undo that could not finish: {@link #failed()} are still in the undo and a later {@link #undo} retries them;
   * {@link #changed()} changed since the run and were left alone. The status has one child per file.
   */
  public static final class IncompleteUndoException extends CoreException {
    private static final long serialVersionUID = 1L;
    private final transient List<IFile> changed;
    private final transient List<IFile> failed;
    private final transient List<IFile> busy;

    IncompleteUndoException(MultiStatus status, List<IFile> changed, List<IFile> failed, List<IFile> busy) {
      super(status);
      this.changed = List.copyOf(changed);
      this.failed = List.copyOf(failed);
      this.busy = List.copyOf(busy);
    }

    /** Busy files left in the undo, as in {@link Result#busy()}. */
    public List<IFile> busy() {
      return busy;
    }

    public List<IFile> changed() {
      return changed;
    }

    public List<IFile> failed() {
      return failed;
    }
  }

  /**
   * What an undo did besides undoing: {@code changed} files were left alone and left the undo; {@code busy} files
   * (open in an editor) were not touched and stay in the undo; {@code cancelled}: it stopped before the files still
   * in the undo.
   */
  public record Result(List<IFile> changed, List<IFile> busy, boolean cancelled) {
    public Result {
      changed = List.copyOf(changed);
      busy = List.copyOf(busy);
    }
  }

  /**
   * What staging left out: {@code changed} files are no longer what the run wrote (or were staged outside
   * AfterGit), {@code busy} files are open in an editor and can be staged later.
   */
  public record StageResult(List<IFile> changed, List<IFile> busy) {
    public StageResult {
      changed = List.copyOf(changed);
      busy = List.copyOf(busy);
    }
  }

  private final List<Written> written = new ArrayList<>();

  synchronized void add(IFile file, RepoFile repoFile, byte[] original, byte[] bytes) {
    written.add(new Written(file, repoFile, original, bytes));
  }

  public synchronized boolean isEmpty() {
    return written.isEmpty();
  }

  /** The files this run wrote and that are not undone yet, in order. */
  public synchronized List<IFile> files() {
    return written.stream().map(w -> w.file).toList();
  }

  /** {@link #stage(List, IProgressMonitor)} without progress. */
  public List<IFile> stage(List<FileOutcome> files) throws IOException, GitAPIException, CoreException {
    return stage(files, new NullProgressMonitor());
  }

  /**
   * {@code git add} of the given files this run wrote; their index entries are saved first, for undo. A file whose
   * bytes are no longer what the run wrote, or that was staged or committed outside AfterGit (no longer conflicted)
   * before this stages it, is not staged and is returned. Each file is checked and staged under its
   * modify rule (call without a scheduling rule, or under one that contains every file); cancelling stops before the
   * next file.
   */
  public List<IFile> stage(List<FileOutcome> files, IProgressMonitor monitor)
      throws IOException, GitAPIException, CoreException {
    return stage(files, Set.of(), monitor).changed();
  }

  /**
   * Like {@link #stage(List, IProgressMonitor)}; {@code busy} files (an open resolver, unsaved editors; matched by
   * handle or by location) are not staged and are returned as such.
   */
  public synchronized StageResult stage(List<FileOutcome> files, Set<IFile> busy, IProgressMonitor monitor)
      throws IOException, GitAPIException, CoreException {
    SubMonitor sm = SubMonitor.convert(monitor, files.size());
    List<IFile> changed = new ArrayList<>();
    List<IFile> busyLeft = new ArrayList<>();
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    for (FileOutcome o : files) {
      if (sm.isCanceled()) {
        throw new OperationCanceledException();
      }
      Written w = find(o.file());
      if (w == null) {
        sm.worked(1);
        continue;
      }
      if (ResolveAll.isBusy(w.file, busy)) {
        busyLeft.add(w.file);
        sm.worked(1);
        continue;
      }
      Exception[] failure = new Exception[1];
      boolean[] staged = new boolean[1];
      ws.run(pm -> {
        try {
          staged[0] = stage(w);
        } catch (IOException | GitAPIException e) {
          failure[0] = e;
        }
      }, ws.getRuleFactory().modifyRule(w.file), 0, sm.newChild(1));
      if (failure[0] instanceof IOException e) {
        throw e;
      }
      if (failure[0] instanceof GitAPIException e) {
        throw e;
      }
      if (!staged[0]) {
        changed.add(w.file);
      }
    }
    return new StageResult(changed, busyLeft);
  }

  /**
   * Stages one file if its bytes are still the written ones and it is still conflicted (or staged by this undo
   * before); false when it changed since the run, or git took it as resolved outside AfterGit: entries recorded then
   * would let an undo put conflict markers into a file the index (or a commit) holds as resolved.
   */
  private static boolean stage(Written w) throws IOException, GitAPIException {
    Path p = w.repoFile.file().toPath();
    byte[] now = Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    if (!Arrays.equals(now, w.written)) {
      return false;
    }
    if (w.before == null && !ConflictState.isConflicted(w.repoFile.repository(), w.repoFile.path())) {
      return false;
    }
    List<String> path = List.of(w.repoFile.path());
    IndexSnapshot before = IndexSnapshot.take(w.repoFile.repository(), path);
    GitIndex.markResolved(w.repoFile.repository(), w.repoFile.path());
    IndexSnapshot after;
    try {
      after = IndexSnapshot.take(w.repoFile.repository(), path);
    } catch (IOException | RuntimeException e) {
      try {
        before.restore(); // without the entries after staging an undo could never tell whether they are still there
      } catch (IOException | RuntimeException again) {
        e.addSuppressed(again);
      }
      throw e;
    }
    if (w.before == null) { // staged twice: the entries from before the first stage are the ones to restore
      w.before = before;
    }
    w.after = after;
    return true;
  }

  /**
   * Gives every written file its bytes from before the run, and staged ones their conflict stages; a file is undone
   * completely or not at all. A file whose bytes are no longer what the run wrote, or whose index entry is no longer
   * what staging made (or whose merge was committed), changed since: it is left alone and returned; so is a file
   * staged or committed outside AfterGit (no longer conflicted, or the merge is over). Undone and changed files leave
   * the undo. Each file is checked and written under its modify rule: call without a scheduling rule, or under one
   * that contains every file.
   *
   * @throws IncompleteUndoException when some files could not be undone; they stay in the undo for a retry, the
   *     other files are undone
   */
  public List<IFile> undo(IProgressMonitor monitor) throws IncompleteUndoException {
    return undo(Set.of(), monitor).changed();
  }

  /**
   * Like {@link #undo(IProgressMonitor)}; {@code busy} files (an open resolver, unsaved editors; matched by handle or
   * by location) are not written and stay in the undo for a later undo. Cancelling, also while waiting for a file's
   * rule, stops before the next file; the files not reached stay in the undo.
   *
   * @throws IncompleteUndoException when some files could not be undone; they stay in the undo for a retry
   */
  public synchronized Result undo(Set<IFile> busy, IProgressMonitor monitor) throws IncompleteUndoException {
    SubMonitor sm = SubMonitor.convert(monitor, written.size());
    List<IFile> changed = new ArrayList<>();
    List<IFile> failed = new ArrayList<>();
    List<IFile> busyLeft = new ArrayList<>();
    boolean cancelled = false;
    MultiStatus status = new MultiStatus(ResolveAll.PLUGIN_ID, 0, "Resolve All could not undo every file");
    for (int k = written.size() - 1; k >= 0; k--) {
      if (sm.isCanceled()) {
        cancelled = true;
        break;
      }
      Written w = written.get(k);
      if (ResolveAll.isBusy(w.file, busy)) {
        busyLeft.add(w.file);
        sm.worked(1);
        continue;
      }
      try {
        if (undoUnderRule(w, sm.newChild(1))) {
          written.remove(k);
        } else {
          changed.add(w.file);
          written.remove(k);
          status.add(Status.info("Changed since Resolve All, left as it is: " + w.file.getFullPath()));
        }
      } catch (OperationCanceledException e) {
        cancelled = true; // while waiting for the file's rule: nothing of it was undone
        break;
      } catch (CoreException | IOException | RuntimeException e) {
        failed.add(w.file);
        status.add(new Status(IStatus.ERROR, ResolveAll.PLUGIN_ID, "Could not undo " + w.file.getFullPath(), e));
      }
    }
    if (!failed.isEmpty()) {
      throw new IncompleteUndoException(status, changed, failed, busyLeft);
    }
    return new Result(changed, busyLeft, cancelled);
  }

  /**
   * {@link #undo(Written, IProgressMonitor)} under the file's modify rule, so no Eclipse save can come between the
   * check of the bytes and the write. Call without a scheduling rule, or under one that contains the file. Cancelling
   * {@code monitor} while it waits for the rule throws OperationCanceledException.
   */
  private static boolean undoUnderRule(Written w, IProgressMonitor monitor) throws CoreException, IOException {
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    boolean[] undone = new boolean[1];
    IOException[] io = new IOException[1];
    ws.run(pm -> {
      try {
        undone[0] = undo(w, new NullProgressMonitor()); // a file is undone completely: no cancelling halfway
      } catch (IOException e) {
        io[0] = e;
      }
    }, ws.getRuleFactory().modifyRule(w.file), 0, monitor);
    if (io[0] != null) {
      throw io[0];
    }
    return undone[0];
  }

  /**
   * Undoes one file: the index first, then the bytes, so a retry after a failed write finds the index already
   * restored and the bytes still the written ones. False when the file changed since the run: other bytes, or
   * staged or committed outside this undo (its conflict markers no longer belong into the file).
   */
  private static boolean undo(Written w, IProgressMonitor monitor) throws CoreException, IOException {
    Path p = w.repoFile.file().toPath();
    byte[] now = Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    if (!Arrays.equals(now, w.written)) {
      return false;
    }
    boolean merging = w.repoFile.repository().getRepositoryState() != RepositoryState.SAFE;
    if (w.before == null) {
      if (!merging || !ConflictState.isConflicted(w.repoFile.repository(), w.repoFile.path())) {
        return false; // git add or a commit outside AfterGit took the written bytes as the resolution
      }
    } else if (!w.before.isCurrent()) {
      if (!merging || w.after == null || !w.after.isCurrent()) {
        return false; // staged or committed outside this undo: its conflict stages no longer belong there
      }
      w.before.restore();
      try {
        writeOriginal(w, monitor);
      } catch (CoreException | RuntimeException e) {
        try {
          w.after.restore(); // staged again, as before this undo; a retry restores the stages once more
        } catch (IOException | RuntimeException again) {
          e.addSuppressed(again); // the stages stay restored: a retry only writes the bytes
        }
        throw e;
      }
      return true;
    }
    if (w.before != null && (!merging || !w.before.hasConflictStages())) {
      return false; // the entries of the first stage were no conflict: never write markers back over them
    }
    writeOriginal(w, monitor);
    return true;
  }

  private static void writeOriginal(Written w, IProgressMonitor monitor) throws CoreException {
    w.file.refreshLocal(IResource.DEPTH_ZERO, monitor);
    w.file.setContents(new ByteArrayInputStream(w.original), IResource.KEEP_HISTORY, monitor);
  }

  public synchronized void discard() {
    written.clear();
  }

  private Written find(IFile file) {
    for (Written w : written) {
      if (w.file.equals(file)) {
        return w;
      }
    }
    return null;
  }
}
