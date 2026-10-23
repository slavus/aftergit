package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.aftergit.merge.git.ResolveAllReport;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/** Resolve All Simple Conflicts over the selection, or over the whole repositories of the selection (spec §5.5, §8.1). */
public final class ResolveAllHandler extends AbstractHandler {
  static final String SCOPE = "net.aftergit.merge.commands.resolveAll.scope";
  static final String REPOSITORY = "repository";

  /** The run started last; only its report may open the summary. UI thread only. */
  private static ResolveAllJob latest;
  /** The run started last while its report is neither delivered nor dropped. UI thread only. */
  private static ResolveAllJob pending;

  @Override
  public Object execute(ExecutionEvent event) {
    Shell shell = HandlerUtil.getActiveShell(event);
    List<IResource> resources = resources(HandlerUtil.getCurrentSelection(event),
        HandlerUtil.getActiveEditorInput(event));
    if (resources.isEmpty()) {
      MessageDialog.openInformation(shell, Messages.resolveAllTitle, Messages.resolveAllNothingSelected);
      return null;
    }
    Display display = shell != null ? shell.getDisplay() : PlatformUI.getWorkbench().getDisplay();
    AtomicReference<ResolveAllJob> job = new AtomicReference<>();
    job.set(start(resources, REPOSITORY.equals(event.getParameter(SCOPE)), report -> {
      if (display.isDisposed()) {
        report.undo().discard();
        return;
      }
      try {
        display.asyncExec(() -> deliver(job.get(), report, shell));
      } catch (SWTException e) { // disposed meanwhile
        report.undo().discard();
      }
    }));
    if (job.get() == null) {
      MessageDialog.openInformation(shell, Messages.resolveAllTitle, Messages.resolveAllRunning);
    }
    return null;
  }

  /**
   * Starts a run, unless one is still running (then null: a superseded run must never close the dialog of a newer
   * one). The dialog of the previous run closes first, which drops its undo (spec §5.5: kept until the dialog closes
   * or the next run). The files others hold are asked again for each file of the run. Call on the UI thread.
   */
  static ResolveAllJob start(List<IResource> resources, boolean wholeRepository, Consumer<ResolveAllReport> done) {
    if (isRunning()) {
      return null;
    }
    ResolveAllDialog.closeCurrent();
    Display display = Display.getCurrent();
    Set<IFile> initial = busyFiles();
    ResolveAllJob job = new ResolveAllJob(resources, wholeRepository, () -> busyFiles(display, initial), done);
    latest = job;
    pending = job;
    // after the run's own report was posted (asyncExec keeps the order): a run that never reported ends here
    job.addJobChangeListener(new JobChangeAdapter() {
      @Override
      public void done(IJobChangeEvent event) {
        onUiThread(display, () -> {
          if (pending == job) {
            pending = null;
          }
        });
      }
    });
    job.schedule();
    return job;
  }

  /**
   * True while a run started by {@link #start} has not finished, or its report has not been delivered yet: a new
   * run then would drop the undo of that report without showing it. UI thread only.
   */
  static boolean isRunning() {
    ResolveAllJob job = latest;
    return job != null && (job.getState() != Job.NONE || job == pending);
  }

  /** {@link #busyFiles()} from any thread; {@code otherwise} when the display is gone or the workbench closing. */
  private static Set<IFile> busyFiles(Display display, Set<IFile> otherwise) {
    AtomicReference<Set<IFile>> out = new AtomicReference<>(otherwise);
    if (PlatformUI.getWorkbench().isClosing()) {
      return otherwise; // the UI thread may be waiting for this job
    }
    onUiThread(display, () -> out.set(busyFiles()), true);
    return out.get();
  }

  private static void onUiThread(Display display, Runnable r) {
    onUiThread(display, r, false);
  }

  private static void onUiThread(Display display, Runnable r, boolean wait) {
    if (display == null || display.isDisposed()) {
      return;
    }
    try {
      if (wait) {
        display.syncExec(r);
      } else {
        display.asyncExec(r);
      }
    } catch (SWTException e) {
      // disposed meanwhile
    }
  }

  /**
   * Shows the summary of {@code job}'s report, unless a newer run started since or the workbench is closing: then
   * its undo is dropped and the newer dialog stays. Call on the UI thread.
   */
  static void deliver(ResolveAllJob job, ResolveAllReport report, Shell fallback) {
    if (job != null && pending == job) {
      pending = null;
    }
    if (job == null || job != latest || PlatformUI.getWorkbench().isClosing()) {
      report.undo().discard();
      return;
    }
    IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    Shell parent = w != null ? w.getShell() : fallback != null && !fallback.isDisposed() ? fallback : null;
    ResolveAllDialog.show(parent, report);
  }

  /** The resources of the selection (files, folders, projects, Java elements, Git Staging entries), else of the active editor. */
  static List<IResource> resources(ISelection selection, IEditorInput activeInput) {
    List<IResource> out = new ArrayList<>();
    if (selection instanceof IStructuredSelection s && !s.isEmpty()) {
      for (Object o : s.toList()) {
        IResource r = Adapters.adapt(o, IResource.class);
        if (r != null && !out.contains(r)) {
          out.add(r);
        }
      }
      return out;
    }
    IResource r = activeInput == null ? null : Adapters.adapt(activeInput, IResource.class);
    if (r != null) {
      out.add(r);
    }
    return out;
  }

  /**
   * Files Resolve All must not write: unsaved changes in a file buffer or an editor, or open in the resolver. Call on
   * the UI thread.
   */
  static Set<IFile> busyFiles() {
    Set<IFile> out = new HashSet<>();
    for (IFileBuffer b : FileBuffers.getTextFileBufferManager().getFileBuffers()) {
      if (b.isDirty()) {
        IFile f = workspaceFile(b.getLocation());
        if (f != null) {
          out.add(f);
        }
      }
    }
    for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
      for (IWorkbenchPage p : w.getPages()) {
        for (IEditorReference ref : p.getEditorReferences()) {
          try {
            IEditorInput input = ref.getEditorInput();
            if (input instanceof MergeEditorInput m) {
              out.add(m.file());
            } else if (ref.isDirty() && Adapters.adapt(input, IFile.class) instanceof IFile f) {
              out.add(f); // an editor that keeps its unsaved text outside a file buffer
            }
          } catch (PartInitException e) {
            // an editor that cannot restore its input holds no buffer
          }
        }
      }
    }
    return out;
  }

  /** The workspace file of a buffer location: a file system location, or the full path of a buffer opened by IFile. */
  private static IFile workspaceFile(IPath location) {
    if (location == null) {
      return null;
    }
    IFile f = FileBuffers.getWorkspaceFileAtLocation(location);
    if (f != null) {
      return f;
    }
    IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    return root.findMember(location) instanceof IFile member ? member : null;
  }
}
