package net.aftergit.merge.ui;

import java.io.IOException;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictKinds;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandler2;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * AfterGit's handler of EGit's Merge Tool command ({@code org.eclipse.egit.ui.team.MergeTool}), which Git Staging
 * runs on a double-click, Enter or its "Merge Tool" entry (docs/INSTALL.md, "AfterGit as the Merge Tool"). Eclipse makes it the
 * command's handler while the workbench selection is one file with a text conflict and "Use AfterGit as the Merge
 * Tool" is on (plugin.xml, {@link ConflictPropertyTester} {@code mergeTool}); otherwise EGit's own handler runs.
 *
 * <p>Eclipse chooses the handler by the workbench's selection and keeps that choice until the selection changes, so
 * the decision is made again on the selection the command runs on: when the switch is off, the file is no text
 * conflict, or {@link EGitMergeTool} runs the command for EGit, this handler passes the call to EGit's own handler
 * (the command's default handler, created from its declaration; no EGit class is referenced).
 */
public final class MergeToolHandler extends AbstractHandler {
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  private IHandler egit;
  private boolean egitLookedUp;

  @Override
  public Object execute(ExecutionEvent event) throws ExecutionException {
    ISelection selection = HandlerUtil.getCurrentSelection(event);
    IFile file = selection instanceof IStructuredSelection s && s.size() == 1
        ? ResolveConflictHandler.fileFrom(selection, null) : null;
    if (takesOver(file)) {
      ResolveConflictHandler.resolve(HandlerUtil.getActiveShell(event), file);
      return null;
    }
    IHandler h = egit();
    if (h == null) {
      throw new ExecutionException("EGit's Merge Tool handler is not available");
    }
    return h.execute(event);
  }

  /** True when AfterGit opens {@code file} for EGit's Merge Tool command. */
  static boolean takesOver(IFile file) {
    if (file == null || EGitMergeTool.bypassed() || !new ConflictPropertyTester().test(file, "mergeTool", null, null)) {
      return false;
    }
    RepoFile rf = RepositoryLocator.locate(file).orElse(null);
    try {
      return rf != null && ConflictKinds.classify(rf.repository(), rf.path()) == ConflictKind.TEXT;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void setEnabled(Object evaluationContext) {
    if (egit() instanceof IHandler2 h) {
      h.setEnabled(evaluationContext);
    }
  }

  @Override
  public boolean isEnabled() {
    if (EGitMergeTool.bypassed() || !MergePreferences.useAsMergeTool()) {
      IHandler h = egit();
      return h != null && h.isEnabled();
    }
    return true;
  }

  @Override
  public void dispose() {
    if (egit != null) {
      egit.dispose();
    }
    super.dispose();
  }

  /** EGit's own handler of the command, from the command's {@code defaultHandler}; null when it cannot be made. */
  private IHandler egit() {
    if (!egitLookedUp) {
      egitLookedUp = true;
      egit = createDefaultHandler();
    }
    return egit;
  }

  private static IHandler createDefaultHandler() {
    for (IConfigurationElement e : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.commands")) {
      if ("command".equals(e.getName()) && EGitMergeTool.command().equals(e.getAttribute("id"))
          && e.getAttribute("defaultHandler") != null) {
        try {
          return e.createExecutableExtension("defaultHandler") instanceof IHandler h ? h : null;
        } catch (CoreException | RuntimeException | LinkageError ex) {
          ILog.of(MergeToolHandler.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
              "EGit's Merge Tool handler could not be created", ex));
          return null;
        }
      }
    }
    return null;
  }
}
