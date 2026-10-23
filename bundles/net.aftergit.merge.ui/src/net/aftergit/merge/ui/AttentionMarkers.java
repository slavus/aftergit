package net.aftergit.merge.ui;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.jgit.lib.Repository;

/**
 * Warnings on files that still need attention after AfterGit wrote them (docs/INSTALL.md, "Files that still need attention"): type
 * {@value #TYPE}, a problem marker shown as "AfterGit" in Problems and on the file in Git Staging and the explorers,
 * not persistent, at most one per file and kind.
 *
 * <ul>
 * <li>"n conflicts not resolved": set by a save of the resolver (Save, Save &amp; Mark Resolved, Save with markers)
 * that leaves n blocks unresolved, and by Resolve All for a file it resolved partly. It goes when such a save or run
 * leaves none, when Resolve All's Undo puts the file back as git left it, and when the file is no longer conflicted
 * in the index (staged by AfterGit or anyone else; checked on every index change JGit reports).
 * <li>"n AI-resolved blocks not reviewed yet": set by a save of the resolver while n AI-resolved blocks wait for
 * review, also by Save &amp; Mark Resolved (it stays after staging). It goes with a later save without such blocks,
 * when the user deletes it in Problems, and when Eclipse restarts.
 * </ul>
 * "Mark files that still need attention" off removes all of them and adds none. All marker work runs in one workspace
 * job with a rule of its own, in the order it was asked for (each marker created in one step),
 * never on the UI thread; other plug-ins' markers are never touched.
 */
final class AttentionMarkers {
  static final String TYPE = "net.aftergit.merge.attention";
  /** Which of the two warnings a marker is: a {@link Kind} name. */
  static final String KIND = "net.aftergit.merge.attentionKind";
  static final Object FAMILY = AttentionMarkers.class;
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  enum Kind { CONFLICTS, AI_NOT_REVIEWED }

  private AttentionMarkers() {}

  /** After the resolver wrote {@code file}: what its model still has open. UI thread. */
  static void saved(IFile file, MergeModel model) {
    int ai = 0;
    for (MergeBlock b : model.blocks()) {
      if (b.needsReview() && b.state() == BlockState.AI_RESOLVED) {
        ai++;
      }
    }
    update(file, model.unresolvedConflicts(), ai);
  }

  /** After Resolve All wrote {@code file} with {@code unresolved} conflicts left; the AI warning stays as it is. */
  static void resolvedAll(IFile file, int unresolved) {
    update(file, unresolved, -1);
  }

  /** {@code file} is back as git left it (Resolve All's Undo): no AfterGit save stands for it any more. */
  static void undone(IFile file) {
    update(file, 0, -1);
  }

  /** "Mark files that still need attention" changed: when off, all AfterGit markers go. */
  static void preferenceChanged() {
    if (MergePreferences.markAttention()) {
      return;
    }
    enqueue(monitor -> ResourcesPlugin.getWorkspace().getRoot().deleteMarkers(TYPE, false, IResource.DEPTH_INFINITE),
        0);
  }

  /** {@code ai} below zero: leave that warning as it is. */
  static void update(IFile file, int unresolved, int ai) {
    if (unresolved > 0 && MergePreferences.markAttention()) {
      IndexWatch.install();
    }
    enqueue(monitor -> {
      if (!file.exists()) {
        return;
      }
      if (!MergePreferences.markAttention()) {
        file.deleteMarkers(TYPE, false, IResource.DEPTH_ZERO);
        return;
      }
      // staged meanwhile (a check of the index may have run before this update): no conflict to warn about
      int open = unresolved > 0 && !conflicted(file) ? 0 : unresolved;
      set(file, Kind.CONFLICTS, open, MessageFormat.format(Messages.attentionConflicts, open));
      if (ai >= 0) {
        set(file, Kind.AI_NOT_REVIEWED, ai, MessageFormat.format(Messages.attentionAiNotReviewed, ai));
      }
    }, 0);
  }

  /** One marker of {@code kind} on the file with {@code message} while {@code count} &gt; 0, else none. */
  private static void set(IFile file, Kind kind, int count, String message) throws CoreException {
    IMarker kept = null;
    for (IMarker m : file.findMarkers(TYPE, false, IResource.DEPTH_ZERO)) {
      if (!kind.name().equals(m.getAttribute(KIND, null))) {
        continue;
      }
      if (count > 0 && kept == null) {
        kept = m;
      } else {
        m.delete();
      }
    }
    if (count <= 0) {
      return;
    }
    if (kept == null) {
      // in one step: Problems never sees a marker without its severity or message
      file.createMarker(TYPE, Map.of(KIND, kind.name(), IMarker.SEVERITY, IMarker.SEVERITY_WARNING,
          IMarker.MESSAGE, message));
    } else if (!message.equals(kept.getAttribute(IMarker.MESSAGE, null))) {
      kept.setAttribute(IMarker.MESSAGE, message);
    }
  }

  /** Drops "conflicts not resolved" from files git no longer holds as conflicted. */
  private static void recheck(IProgressMonitor monitor) throws CoreException {
    for (IMarker m : ResourcesPlugin.getWorkspace().getRoot().findMarkers(TYPE, false, IResource.DEPTH_INFINITE)) {
      if (monitor.isCanceled()) {
        return;
      }
      if (Kind.CONFLICTS.name().equals(m.getAttribute(KIND, null)) && m.getResource() instanceof IFile f
          && !conflicted(f)) {
        m.delete();
      }
    }
  }

  private static boolean conflicted(IFile file) {
    RepoFile rf = RepositoryLocator.locate(file).orElse(null);
    if (rf == null) {
      return false;
    }
    try {
      return ConflictState.isConflicted(rf.repository(), rf.path());
    } catch (IOException e) {
      return true; // cannot tell: keep the warning
    }
  }

  interface Work {
    void run(IProgressMonitor monitor) throws CoreException;
  }

  /** All marker work, run one after the other in the order it came, by {@link #WORKER}. */
  private static final Queue<Work> QUEUE = new ConcurrentLinkedQueue<>();
  /** True while a check of the index is queued and not yet run: a burst of index events queues one. */
  private static final AtomicBoolean CHECK_QUEUED = new AtomicBoolean();

  /** No other job of AfterGit's markers runs at the same time (the workspace's marker rule is null). */
  static final ISchedulingRule RULE = new ISchedulingRule() {
    @Override
    public boolean contains(ISchedulingRule rule) {
      return rule == this;
    }

    @Override
    public boolean isConflicting(ISchedulingRule rule) {
      return rule == this;
    }
  };

  private static final WorkspaceJob WORKER = new WorkspaceJob(Messages.attentionJob) {
    @Override
    public IStatus runInWorkspace(IProgressMonitor monitor) {
      IStatus result = Status.OK_STATUS;
      for (Work w; (w = QUEUE.poll()) != null;) {
        try {
          w.run(monitor);
        } catch (CoreException e) {
          result = new Status(IStatus.WARNING, PLUGIN_ID, "The AfterGit markers could not be updated", e);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          // one broken item must not hold up the ones after it
          ILog.of(AttentionMarkers.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
              "An update of the AfterGit markers failed", JavaBridge.loggable(e)));
        }
      }
      return result;
    }

    @Override
    public boolean belongsTo(Object family) {
      return family == FAMILY;
    }
  };

  static {
    WORKER.setSystem(true);
    WORKER.setRule(RULE);
  }

  static void enqueue(Work work, long delay) {
    QUEUE.add(work);
    WORKER.schedule(delay); // a running worker takes it in this run or runs once more
  }

  /**
   * Listens to JGit's index events of every repository instance (AfterGit's, EGit's, a command-line git seen by
   * EGit's rescan) once a "conflicts" warning exists, and then checks those warnings again, a moment later so that a
   * burst of events causes one check.
   */
  private static final class IndexWatch {
    private static boolean installed;

    static synchronized void install() {
      if (installed) {
        return;
      }
      installed = true;
      Repository.getGlobalListenerList().addIndexChangedListener(event -> {
        if (MergePreferences.markAttention() && CHECK_QUEUED.compareAndSet(false, true)) {
          enqueue(monitor -> {
            try {
              recheck(monitor);
            } finally {
              CHECK_QUEUED.set(false);
            }
          }, 200);
        }
      });
    }
  }
}
