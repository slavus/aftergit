package net.aftergit.merge.ui;

import java.io.IOException;
import net.aftergit.merge.git.ConflictActions;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictKinds;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/** Resolves the selected conflicted file: the three-pane resolver for text, what fits for other kinds (spec §8.4). */
public final class ResolveConflictHandler extends AbstractHandler {
  private static ConflictChoices choicesForTests;

  static void setChoicesForTests(ConflictChoices choices) {
    choicesForTests = choices;
  }

  @Override
  public Object execute(ExecutionEvent event) {
    resolve(HandlerUtil.getActiveShell(event),
        fileFrom(HandlerUtil.getCurrentSelection(event), HandlerUtil.getActiveEditorInput(event)));
    return null;
  }

  /** Opens the resolver for a text conflict, or offers what fits another kind of conflict. */
  static void resolve(Shell shell, IFile file) {
    if (file == null || !new ConflictPropertyTester().test(file, "conflicted", null, null)) {
      MessageDialog.openInformation(shell, Messages.commandName, Messages.notConflicted);
      return;
    }
    RepoFile rf = RepositoryLocator.locate(file).orElse(null);
    ConflictKind kind = kindOf(rf);
    if (!kind.offersSides() && !kind.needsMergeTool()) {
      CompareUI.openCompareEditor(new MergeEditorInput(file));
      return;
    }
    ConflictChoices choices = choicesForTests != null ? choicesForTests : ConflictChoices.dialogs(shell);
    if (kind.offersSides()) {
      if (hasUnsavedEditor(file)) {
        choices.unsavedEditors(file.getName()); // keep/delete rewrite the file under the editor's unsaved text
        return;
      }
      apply(shell, file, rf, choices.chooseSide(file.getName(), kind));
    } else if (choices.offerMergeTool(file.getName(), kind) && !choices.openMergeTool(file)) {
      choices.mergeToolUnavailable();
    }
  }

  /** True when an open editor holds unsaved changes of the file (resolver or text editor). */
  static boolean hasUnsavedEditor(IFile file) {
    var workbench = PlatformUI.getWorkbench();
    for (IWorkbenchWindow w : workbench.getWorkbenchWindows()) {
      for (IWorkbenchPage page : w.getPages()) {
        for (IEditorReference ref : page.getEditorReferences()) {
          if (!ref.isDirty()) {
            continue;
          }
          try {
            if (ref.getEditorInput() instanceof MergeEditorInput m ? file.equals(m.file())
                : file.equals(Adapters.adapt(ref.getEditorInput(), IFile.class))) {
              return true;
            }
          } catch (PartInitException e) {
            return true; // cannot tell: do not overwrite
          }
        }
      }
    }
    return false;
  }

  /** The kind of the file's conflict; TEXT when it cannot be told (the editor then reports what it cannot read). */
  private static ConflictKind kindOf(RepoFile rf) {
    if (rf == null) {
      return ConflictKind.TEXT;
    }
    try {
      return ConflictKinds.classify(rf.repository(), rf.path());
    } catch (IOException e) {
      return ConflictKind.TEXT;
    }
  }

  private static void apply(Shell shell, IFile file, RepoFile rf, ConflictChoices.SideChoice choice) {
    if (choice == ConflictChoices.SideChoice.CANCEL) {
      return;
    }
    Exception failure = null;
    try {
      switch (choice) {
        case KEEP_OURS -> ConflictActions.keep(rf.repository(), rf.path(), ConflictActions.Side.OURS);
        case KEEP_THEIRS -> ConflictActions.keep(rf.repository(), rf.path(), ConflictActions.Side.THEIRS);
        case DELETE -> ConflictActions.delete(rf.repository(), rf.path());
        case CANCEL -> throw new AssertionError(); // returned above
      }
    } catch (IOException | GitAPIException | IllegalStateException | JGitInternalException e) {
      failure = e;
    } finally {
      // also after a failure: the checkout may have changed the file before git add failed
      try {
        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
      } catch (CoreException e) {
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      ErrorDialog.openError(shell, Messages.commandName, null, new Status(IStatus.ERROR, "net.aftergit.merge.ui",
          NLS.bind(Messages.sideActionFailed, file.getName()), failure));
    }
  }

  /**
   * The file for the selection's first element, else for the active editor input. Java elements adapt to
   * {@link IResource} rather than {@link IFile}, so both are tried.
   */
  static IFile fileFrom(ISelection selection, IEditorInput activeInput) {
    if (selection instanceof IStructuredSelection s && !s.isEmpty()) {
      return asFile(s.getFirstElement());
    }
    return activeInput == null ? null : asFile(activeInput);
  }

  private static IFile asFile(Object element) {
    IFile file = Adapters.adapt(element, IFile.class);
    if (file != null) {
      return file;
    }
    if (Adapters.adapt(element, IResource.class) instanceof IFile f) {
      return f;
    }
    // Git Staging's entries of files in projects EGit does not manage adapt only to their location
    IPath location = element instanceof IPath p ? p : Adapters.adapt(element, IPath.class);
    return location == null ? null : fileAt(location);
  }

  /** The workspace file at a file-system location, or null. */
  static IFile fileAt(IPath location) {
    IFile f = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(location);
    return f != null && f.exists() ? f : null;
  }
}
