package net.aftergit.merge.ui;

import net.aftergit.merge.git.ConflictKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;

/** Questions for conflicts the three-pane resolver does not show (spec §8.4); replaceable for tests. */
interface ConflictChoices {
  enum SideChoice { KEEP_OURS, KEEP_THEIRS, DELETE, CANCEL }

  /** How to resolve a conflict by keeping a side or deleting the file; Esc or closing answers CANCEL. */
  SideChoice chooseSide(String fileName, ConflictKind kind);

  /** True when the user wants EGit's Merge Tool for a conflict this resolver cannot show. */
  boolean offerMergeTool(String fileName, ConflictKind kind);

  /** Opens EGit's Merge Tool on the file; false when it is not available. */
  default boolean openMergeTool(IFile file) {
    return EGitMergeTool.open(file);
  }

  void mergeToolUnavailable();

  /** An editor of the file has unsaved changes: nothing is written until the user has saved or closed it. */
  void unsavedEditors(String fileName);

  /** What the kind means, for messages. */
  static String describe(ConflictKind kind) {
    return switch (kind) {
      case MODIFY_DELETE -> Messages.kindModifyDelete;
      case DELETE_MODIFY -> Messages.kindDeleteModify;
      case BINARY -> Messages.kindBinary;
      case SUBMODULE -> Messages.kindSubmodule;
      case SYMLINK -> Messages.kindSymlink;
      case RENAME -> Messages.kindRename;
      case TEXT, NONE -> "";
    };
  }

  static ConflictChoices dialogs(Shell shell) {
    return new ConflictChoices() {
      @Override
      public SideChoice chooseSide(String fileName, ConflictKind kind) {
        MessageDialog d = new MessageDialog(shell, Messages.sidesTitle, null,
            NLS.bind(Messages.sidesQuestion, fileName, describe(kind)), MessageDialog.QUESTION, 3,
            Messages.keepOurs, Messages.keepTheirs, Messages.deleteFile, IDialogConstants.CANCEL_LABEL);
        // Esc or closing the dialog returns SWT.DEFAULT (-1) and therefore cancels
        return switch (d.open()) {
          case 0 -> SideChoice.KEEP_OURS;
          case 1 -> SideChoice.KEEP_THEIRS;
          case 2 -> SideChoice.DELETE;
          default -> SideChoice.CANCEL;
        };
      }

      @Override
      public boolean offerMergeTool(String fileName, ConflictKind kind) {
        MessageDialog d = new MessageDialog(shell, Messages.sidesTitle, null,
            NLS.bind(Messages.mergeToolQuestion, fileName, describe(kind)), MessageDialog.INFORMATION, 0,
            Messages.openMergeTool, IDialogConstants.CLOSE_LABEL);
        return d.open() == 0;
      }

      @Override
      public void mergeToolUnavailable() {
        MessageDialog.openInformation(shell, Messages.sidesTitle, Messages.mergeToolUnavailable);
      }

      @Override
      public void unsavedEditors(String fileName) {
        MessageDialog.openInformation(shell, Messages.sidesTitle, NLS.bind(Messages.unsavedEditors, fileName));
      }
    };
  }
}
