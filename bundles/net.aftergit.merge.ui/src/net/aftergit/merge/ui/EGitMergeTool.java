package net.aftergit.merge.ui;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * EGit's Merge Tool, run as its public command on one file (spec §8.4: submodule, rename, symbolic link; and "Open in
 * EGit Merge Tool"). AfterGit handles that command for text conflicts ({@link MergeToolHandler}); while this runs it,
 * AfterGit's handler stands aside, so EGit's own handler opens EGit's merge editor.
 */
final class EGitMergeTool {
  static final String COMMAND = "org.eclipse.egit.ui.team.MergeTool";

  private EGitMergeTool() {}

  /** The command run and checked: EGit's, or for tests one nobody defines (EGit UI absent). */
  private static String command = COMMAND;

  static void setCommandForTests(String id) {
    command = id == null ? COMMAND : id;
  }

  static String command() {
    return command;
  }

  /** Above zero while EGit's own handler must run the command (UI thread only). */
  private static int bypass;

  /** Runs {@code run} with AfterGit's handler of the command standing aside. */
  static void bypassing(Runnable run) {
    bypass++;
    try {
      run.run();
    } finally {
      bypass--;
    }
  }

  /** True while {@link #bypassing} runs. */
  static boolean bypassed() {
    return bypass > 0;
  }

  /** True when EGit UI defines the Merge Tool command (EGit UI is installed). */
  static boolean available() {
    if (!PlatformUI.isWorkbenchRunning()) {
      return false;
    }
    ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
    return commands != null && commands.getCommand(command).isDefined();
  }

  /** Runs the Merge Tool as if {@code file} were selected; false when EGit UI does not offer it here. */
  static boolean open(IFile file) {
    IWorkbench workbench = PlatformUI.getWorkbench();
    ICommandService commands = workbench.getService(ICommandService.class);
    IHandlerService handlers = workbench.getService(IHandlerService.class);
    Command cmd = commands.getCommand(command);
    if (!cmd.isDefined()) {
      return false;
    }
    IEvaluationContext context = handlers.createContextSnapshot(false);
    context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, new StructuredSelection(file));
    boolean[] ran = new boolean[1];
    bypassing(() -> {
      try {
        handlers.executeCommandInContext(new ParameterizedCommand(cmd, null), null, context);
        ran[0] = true;
      } catch (ExecutionException | NotDefinedException | NotEnabledException | NotHandledException e) {
        ILog.of(EGitMergeTool.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
            "The EGit Merge Tool could not be run on " + file.getFullPath(), e));
      }
    });
    return ran[0];
  }
}
