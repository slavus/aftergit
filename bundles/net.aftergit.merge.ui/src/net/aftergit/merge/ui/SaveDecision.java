package net.aftergit.merge.ui;

import java.text.MessageFormat;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/** Questions asked while saving; replaceable for tests. */
public interface SaveDecision {
  /** How to save while conflicts are still unresolved (spec §8.3: markers are the default). */
  enum SaveChoice {
    /** Write unresolved blocks as git conflict markers; the file stays conflicted. */
    MARKERS,
    /** Write the text as it currently appears (base text for unresolved blocks). */
    CURRENT_TEXT,
    /** Write nothing; the editor stays dirty. */
    CANCEL
  }

  /** What Save & Mark Resolved does while conflicts are open (ui-polish design §3). */
  enum FinishChoice {
    /** Write nothing; the default. */
    CANCEL,
    /** Save as Ctrl+S saves (it asks how to write the open conflicts); not staged; the editor stays open. */
    SAVE_WITHOUT_STAGING,
    /** Write the open conflicts as markers in the repository's style; not staged; the resolver closes. */
    SAVE_WITH_MARKERS
  }

  SaveChoice onUnresolved(int unresolved);

  /** Asked by Save & Mark Resolved while conflicts are open; Esc or closing the dialog must answer CANCEL. */
  FinishChoice onFinishWithConflicts(int unresolved);

  static SaveDecision dialogs(Shell shell) {
    return new SaveDecision() {
      @Override
      public SaveChoice onUnresolved(int unresolved) {
        MessageDialog dialog = new MessageDialog(shell, Messages.saveQuestionTitle, null,
            String.format(Messages.saveQuestion, unresolved), MessageDialog.QUESTION, 0,
            Messages.saveWriteMarkers, Messages.saveWriteCurrentText, Messages.saveCancel);
        // Esc or closing the dialog returns SWT.DEFAULT (-1) and therefore cancels
        return switch (dialog.open()) {
          case 0 -> SaveChoice.MARKERS;
          case 1 -> SaveChoice.CURRENT_TEXT;
          default -> SaveChoice.CANCEL;
        };
      }

      @Override
      public FinishChoice onFinishWithConflicts(int unresolved) {
        MessageDialog dialog = new MessageDialog(shell, Messages.finishTitle, null,
            MessageFormat.format(Messages.finishQuestion, unresolved), MessageDialog.WARNING, 0,
            Messages.finishCancel, Messages.finishSaveWithoutStaging, Messages.finishSaveWithMarkers);
        // Cancel is the default button; Esc or closing the dialog returns -1 and cancels too
        return switch (dialog.open()) {
          case 1 -> FinishChoice.SAVE_WITHOUT_STAGING;
          case 2 -> FinishChoice.SAVE_WITH_MARKERS;
          default -> FinishChoice.CANCEL;
        };
      }
    };
  }
}
