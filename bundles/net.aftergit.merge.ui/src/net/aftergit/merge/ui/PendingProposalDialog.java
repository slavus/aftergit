package net.aftergit.merge.ui;

import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** A waiting proposal of an agent with its rationale: Apply, Discard or Close (agents design §3). */
final class PendingProposalDialog extends Dialog {
  enum Choice { APPLY, DISCARD, CLOSE }

  /** The longest rationale shown; an agent's text has no other bound below the request size. */
  static final int MAX_RATIONALE = 4_000;
  /** Height of the rationale field in lines; a longer one scrolls, so the buttons always stay in the window. */
  private static final int RATIONALE_LINES = 5;
  private static final int APPLY_ID = IDialogConstants.CLIENT_ID + 1;
  private static final int DISCARD_ID = IDialogConstants.CLIENT_ID + 2;
  private final Proposal proposal;
  private Choice choice = Choice.CLOSE;

  private PendingProposalDialog(Shell parent, Proposal proposal) {
    super(parent);
    this.proposal = proposal;
  }

  /** Shows {@code proposal}; Esc or closing the window answers CLOSE. */
  static Choice ask(Shell parent, Proposal proposal) {
    PendingProposalDialog d = new PendingProposalDialog(parent, proposal);
    d.open();
    return d.choice;
  }

  @Override
  protected void configureShell(Shell shell) {
    super.configureShell(shell);
    shell.setText(NLS.bind(Messages.proposalTitle, BaseTextHover.agentName(proposal)));
  }

  @Override
  protected boolean isResizable() {
    return true;
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite area = (Composite) super.createDialogArea(parent);
    if (!proposal.rationale().isEmpty()) {
      // a Text, not a Label: no mnemonics eat the "&" of "a && b", and a long rationale scrolls
      Text why = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
      why.setText(NLS.bind(Messages.proposalRationale, BaseTextHover.capped(proposal.rationale(), MAX_RATIONALE)));
      GridDataFactory.fillDefaults().grab(true, false).hint(560, why.getLineHeight() * RATIONALE_LINES).applyTo(why);
    }
    Text text = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
    text.setFont(JFaceResources.getTextFont());
    text.setText(proposal.text());
    GridDataFactory.fillDefaults().grab(true, true).hint(560, 240).applyTo(text);
    return area;
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    createButton(parent, APPLY_ID, Messages.proposalApply, true);
    createButton(parent, DISCARD_ID, Messages.proposalDiscard, false);
    createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, false);
  }

  @Override
  protected void buttonPressed(int buttonId) {
    choice = buttonId == APPLY_ID ? Choice.APPLY : buttonId == DISCARD_ID ? Choice.DISCARD : Choice.CLOSE;
    close();
  }
}
