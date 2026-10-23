package net.aftergit.merge.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;

/** What "Open in EGit Merge Tool" does with unsaved changes of the result; replaceable for tests. */
interface OpenInEGitDecision {
  enum Choice {
    /** Nothing happens; the resolver stays open with its changes. */
    CANCEL,
    /** The changes are dropped; the resolver closes and EGit's Merge Tool opens. */
    DISCARD_AND_OPEN,
    /** The result is saved (the working-tree file only, as Save does); then as {@link #DISCARD_AND_OPEN}. */
    SAVE_AND_OPEN
  }

  /** Asked only while the result has unsaved changes; Esc or closing the dialog must answer CANCEL. */
  Choice onUnsaved(String fileName);

  static OpenInEGitDecision dialog(Shell shell) {
    return fileName -> {
      MessageDialog d = new MessageDialog(shell, Messages.openInEGitTitle, null,
          NLS.bind(Messages.openInEGitQuestion, fileName), MessageDialog.QUESTION, 2,
          Messages.openInEGitCancel, Messages.openInEGitDiscard, Messages.openInEGitSave);
      // Save and Open is the default button; Esc or closing the dialog returns -1 and cancels
      return switch (d.open()) {
        case 1 -> Choice.DISCARD_AND_OPEN;
        case 2 -> Choice.SAVE_AND_OPEN;
        default -> Choice.CANCEL;
      };
    };
  }
}
