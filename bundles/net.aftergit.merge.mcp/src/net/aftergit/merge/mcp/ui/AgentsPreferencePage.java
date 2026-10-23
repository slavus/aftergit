package net.aftergit.merge.mcp.ui;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.function.BooleanSupplier;
import net.aftergit.merge.mcp.AgentServer;
import net.aftergit.merge.mcp.McpConfigSnippet;
import net.aftergit.merge.mcp.McpPreferences;
import net.aftergit.merge.mcp.SecureTokenStore;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * AfterGit → AI Agents (MCP) (agents design §1, §2, §4): the switch, the port, the server's state, the client
 * configuration with the token (clipboard only), a new token, and deleting stored proposals.
 */
public final class AgentsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  private Button enabled;
  private Text port;
  private Label status;
  private Combo client;
  private Button copy;
  private Button regenerate;
  private Button delete;
  /** OK or Apply is being applied (e.g. while secure storage asks for its password in a dialog). */
  private boolean applying;
  /** The switch as it was saved when the page opened; Cancel goes back to it after a failed OK. */
  private boolean enabledWhenOpened;
  /** Asks before "Delete Stored Proposals"; replaceable in tests. */
  BooleanSupplier confirmDelete = () -> MessageDialog.openConfirm(getShell(), Messages.deleteTitle,
      Messages.deleteQuestion);
  /** The server this page shows and switches, taken once when the page is made. */
  private AgentServer agents;
  private Display display;
  /** Runs on the thread that changed the server: only hands over to the UI thread, never touches a widget. */
  private final Runnable serverListener = () -> {
    Display d = display;
    if (d != null && !d.isDisposed()) {
      d.asyncExec(() -> {
        if (status != null && !status.isDisposed()) {
          showStatus();
        }
      });
    }
  };

  @Override
  public void init(IWorkbench workbench) {
    setDescription(Messages.description);
  }

  @Override
  protected Control createContents(Composite parent) {
    agents = AgentServer.instance();
    display = parent.getDisplay();
    Composite c = new Composite(parent, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(2).applyTo(c);
    enabled = new Button(c, SWT.CHECK);
    enabled.setText(Messages.allow);
    enabledWhenOpened = McpPreferences.enabled();
    enabled.setSelection(enabledWhenOpened);
    GridDataFactory.fillDefaults().span(2, 1).applyTo(enabled);
    new Label(c, SWT.NONE).setText(Messages.port);
    port = new Text(c, SWT.BORDER);
    port.setText(Integer.toString(McpPreferences.port()));
    port.addModifyListener(e -> validatePort());
    GridDataFactory.fillDefaults().hint(80, SWT.DEFAULT).applyTo(port);
    status = new Label(c, SWT.WRAP);
    GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(420, SWT.DEFAULT).applyTo(status);
    new Label(c, SWT.NONE).setText(Messages.client);
    client = new Combo(c, SWT.READ_ONLY);
    for (McpConfigSnippet.Client cl : McpConfigSnippet.Client.values()) {
      client.add(cl.label());
    }
    client.select(0);
    Composite buttons = new Composite(c, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(3).applyTo(buttons);
    GridDataFactory.fillDefaults().span(2, 1).applyTo(buttons);
    copy = button(buttons, Messages.copyConfiguration, this::copyConfiguration);
    regenerate = button(buttons, Messages.regenerateToken, this::regenerateToken);
    delete = button(buttons, Messages.deleteProposals, this::deleteProposals);
    AgentServer server = agents;
    server.addListener(serverListener);
    c.addDisposeListener(e -> server.removeListener(serverListener));
    showStatus();
    return c;
  }

  private static Button button(Composite parent, String text, Runnable run) {
    Button b = new Button(parent, SWT.PUSH);
    b.setText(text);
    b.addListener(SWT.Selection, e -> run.run());
    return b;
  }

  @Override
  public boolean performOk() {
    Integer p = parsePort();
    if (p == null) {
      return false;
    }
    if (applying) {
      return false; // pressed again from the event loop of a password dialog: the first press still applies
    }
    applying = true;
    try {
      boolean on = enabled.getSelection();
      byUser(() -> {
        McpPreferences.setPort(p);
        McpPreferences.setEnabled(on);
        agents.follow();
      });
    } finally {
      applying = false;
    }
    showStatus();
    return !(enabled.getSelection() && !agents.running()); // not running: stay open with the reason shown
  }

  /**
   * After an OK or Apply whose server did not start (the page stayed open with the reason), Cancel puts the switch
   * back as it was when the page opened: a switch saved on would start the server at the next startup without the
   * user having seen it run. A server that runs keeps running (Apply was successful).
   */
  @Override
  public boolean performCancel() {
    if (agents != null && !agents.running() && McpPreferences.enabled() && !enabledWhenOpened) {
      McpPreferences.setEnabled(false);
    }
    return super.performCancel();
  }

  /**
   * Runs an explicit action of the user on this page; only such an action, on the UI thread, may let secure storage
   * ask for its password (startup and preference events never do).
   */
  private static void byUser(Runnable action) {
    if (Display.getCurrent() != null) {
      SecureTokenStore.allowPromptWhile(action);
    } else {
      action.run();
    }
  }

  @Override
  protected void performDefaults() {
    enabled.setSelection(false);
    port.setText("0");
    super.performDefaults();
  }

  void showStatus() {
    AgentServer s = agents;
    if (s.running()) {
      status.setText(NLS.bind(Messages.running, s.url()));
    } else if (s.failure() != null) {
      status.setText(NLS.bind(Messages.failed, s.failure()));
    } else {
      status.setText(Messages.stopped);
    }
    copy.setEnabled(s.running());
    regenerate.setEnabled(s.running());
    status.getParent().layout(true);
  }

  private Integer parsePort() {
    try {
      int p = Integer.parseInt(port.getText().trim());
      return p >= 0 && p <= 65535 ? p : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void validatePort() {
    boolean ok = parsePort() != null;
    setErrorMessage(ok ? null : Messages.badPort);
    setValid(ok);
  }

  void copyConfiguration() {
    AgentServer s = agents;
    if (!s.running()) {
      return;
    }
    McpConfigSnippet.Client c = McpConfigSnippet.Client.values()[Math.max(0, client.getSelectionIndex())];
    String json = McpConfigSnippet.json(c, s.url(), s.token());
    Clipboard cb = new Clipboard(getShell().getDisplay());
    try {
      cb.setContents(new Object[] {json}, new Transfer[] {TextTransfer.getInstance()});
    } finally {
      cb.dispose();
    }
    setMessage(Messages.copied, IMessageProvider.INFORMATION);
  }

  void regenerateToken() {
    String[] fresh = new String[1];
    try {
      byUser(() -> fresh[0] = agents.regenerateToken());
    } catch (RuntimeException e) {
      setErrorMessage(NLS.bind(Messages.regenerateFailed, reason(e)));
      return;
    }
    if (fresh[0] != null) {
      setErrorMessage(null);
      setMessage(Messages.regenerated, IMessageProvider.INFORMATION);
    }
  }

  private static String reason(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  void deleteProposals() {
    if (!confirmDelete.getAsBoolean()) {
      return;
    }
    try {
      int n = agents.deleteStoredProposals();
      setMessage(MessageFormat.format(Messages.deleted, n), IMessageProvider.INFORMATION);
    } catch (IOException | RuntimeException e) {
      setErrorMessage(NLS.bind(Messages.deleteFailed, reason(e)));
    }
  }

  Button enabledButton() {
    return enabled;
  }

  Text portText() {
    return port;
  }

  Label statusLabel() {
    return status;
  }

  Combo clientCombo() {
    return client;
  }

  Button copyButton() {
    return copy;
  }

  Button regenerateButton() {
    return regenerate;
  }

  Button deleteButton() {
    return delete;
  }
}
