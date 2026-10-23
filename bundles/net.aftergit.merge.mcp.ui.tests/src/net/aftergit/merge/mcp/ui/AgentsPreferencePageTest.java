package net.aftergit.merge.mcp.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.git.agents.ProposalSource;
import net.aftergit.merge.mcp.AgentServer;
import net.aftergit.merge.mcp.McpPreferences;
import net.aftergit.merge.mcp.SecureTokenStore;
import net.aftergit.merge.mcp.TokenStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/** Agents design §1, §2, §4: the only visible trace while off, and the switch, port, token and configuration. */
class AgentsPreferencePageTest {
  AgentServer server;
  PreferenceDialog dialog;

  @BeforeEach
  void setUp() {
    McpPreferences.setEnabled(false);
    McpPreferences.setPort(0);
    server = new AgentServer(TokenStore.memory(), FrameworkUtil.getBundle(AgentsPreferencePageTest.class)
        .getBundleContext(), McpPreferences.node());
    AgentServer.setInstanceForTests(server);
  }

  @AfterEach
  void tearDown() {
    if (dialog != null && dialog.getShell() != null && !dialog.getShell().isDisposed()) {
      dialog.close();
    }
    McpPreferences.setEnabled(false);
    server.unfollow();
    AgentServer.setInstanceForTests(null);
  }

  AgentsPreferencePage open() {
    dialog = PreferencesUtil.createPreferenceDialogOn(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
        ProposalSource.PREFERENCE_PAGE_ID, new String[] {ProposalSource.PREFERENCE_PAGE_ID}, null);
    dialog.setBlockOnOpen(false);
    dialog.open();
    UiRepos.flush();
    return (AgentsPreferencePage) dialog.getSelectedPage();
  }

  AgentsPreferencePage openAndAllow() {
    AgentsPreferencePage page = open();
    page.enabledButton().setSelection(true);
    assertTrue(page.performOk());
    UiRepos.flush();
    return page;
  }

  @Test
  void thePageLivesUnderAfterGitAndIsOffByDefault() {
    assertNotNull(PlatformUI.getWorkbench().getPreferenceManager()
        .find("net.aftergit.merge.preferences/" + ProposalSource.PREFERENCE_PAGE_ID));
    AgentsPreferencePage page = open();
    assertFalse(page.enabledButton().getSelection());
    assertEquals("0", page.portText().getText());
    assertEquals(Messages.stopped, page.statusLabel().getText());
    assertFalse(page.copyButton().isEnabled());
    assertFalse(page.regenerateButton().isEnabled());
    assertFalse(server.running());
  }

  @Test
  void allowingStartsTheServerAndShowsWhereItListens() {
    AgentsPreferencePage page = openAndAllow();
    assertTrue(server.running());
    assertTrue(McpPreferences.enabled());
    assertEquals(NLS.bind(Messages.running, server.url()), page.statusLabel().getText());
    assertTrue(page.copyButton().isEnabled());
  }

  @Test
  void copyPutsTheConfigurationWithTheTokenOnTheClipboard() {
    AgentsPreferencePage page = openAndAllow();
    page.clientCombo().select(2); // VS Code
    page.copyConfiguration();
    Clipboard cb = new Clipboard(Display.getCurrent());
    String text;
    try {
      text = (String) cb.getContents(TextTransfer.getInstance());
    } finally {
      cb.dispose();
    }
    JsonObject s = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("servers")
        .getAsJsonObject("aftergit");
    assertEquals(server.url(), s.get("url").getAsString());
    assertEquals("Bearer " + server.token(), s.getAsJsonObject("headers").get("Authorization").getAsString());
  }

  @Test
  void regenerateMakesTheOldTokenInvalidAtOnce() {
    AgentsPreferencePage page = openAndAllow();
    String old = server.token();
    page.regenerateToken();
    assertNotEquals(old, server.token());
    String url = server.url();
    RawMcp.Reply r = UiRepos.offUi(() -> new RawMcp(url, old).post("{}"));
    assertEquals(401, r.status());
  }

  @Test
  void switchingOffOnThePageStopsTheServerAndRevokesTheToken() {
    AgentsPreferencePage page = openAndAllow();
    page.enabledButton().setSelection(false);
    assertTrue(page.performOk());
    UiRepos.flush();
    assertFalse(server.running());
    assertNull(server.token());
    assertEquals(Messages.stopped, page.statusLabel().getText());
  }

  @Test
  void aPortOutsideTheRangeCannotBeApplied() {
    AgentsPreferencePage page = open();
    page.portText().setText("70000");
    UiRepos.flush();
    assertFalse(page.isValid());
    page.portText().setText("abc");
    assertFalse(page.isValid());
    page.portText().setText("0");
    assertTrue(page.isValid());
  }

  @Test
  void restoreDefaultsMeansOffAndAnyPort() {
    AgentsPreferencePage page = open();
    page.enabledButton().setSelection(true);
    page.portText().setText("4711");
    page.performDefaults();
    assertFalse(page.enabledButton().getSelection());
    assertEquals("0", page.portText().getText());
  }

  @Test
  void deleteStoredProposalsAsksFirst() {
    AgentsPreferencePage page = open();
    page.confirmDelete = () -> false;
    page.deleteProposals();
    assertNull(page.getMessage());
    page.confirmDelete = () -> true;
    page.deleteProposals();
    assertEquals(MessageFormat.format(Messages.deleted, 0), page.getMessage());
  }

  @Test
  void onlyApplyingOnThePageMayPromptAndASecondApplyMeanwhileStartsNothing() throws Exception {
    List<Boolean> prompts = new ArrayList<>();
    List<Boolean> nestedOk = new ArrayList<>();
    AtomicReference<AgentsPreferencePage> again = new AtomicReference<>();
    TokenStore memory = TokenStore.memory();
    server = new AgentServer(new TokenStore() {
      @Override
      public String get() {
        prompts.add(SecureTokenStore.promptAllowed());
        AgentsPreferencePage p = again.getAndSet(null);
        if (p != null) {
          nestedOk.add(p.performOk()); // Apply pressed again from the event loop of the password dialog
        }
        return memory.get();
      }

      @Override public String create() { return memory.create(); }
      @Override public void revoke() { memory.revoke(); }
    }, FrameworkUtil.getBundle(AgentsPreferencePageTest.class).getBundleContext(), McpPreferences.node());
    AgentServer.setInstanceForTests(server);
    AgentsPreferencePage page = open();
    page.enabledButton().setSelection(true);
    again.set(page);
    assertTrue(page.performOk());
    assertEquals(List.of(true), prompts, "applying on the page may prompt");
    assertEquals(List.of(false), nestedOk);
    assertTrue(server.running());
    var context = FrameworkUtil.getBundle(AgentsPreferencePageTest.class).getBundleContext();
    assertEquals(1, context.getServiceReferences(ProposalSource.class, null).size(),
        "one server");
    McpPreferences.setEnabled(false);
    McpPreferences.setEnabled(true); // as a preference import or the startup: not an action on the page
    assertEquals(List.of(true, false), prompts);
    assertFalse(SecureTokenStore.promptAllowed());
  }

  @Test
  void aFailedRegenerateKeepsTheOldTokenAndSaysSo() {
    TokenStore memory = TokenStore.memory();
    int[] creates = {0};
    server = new AgentServer(new TokenStore() {
      @Override public String get() { return memory.get(); }
      @Override public String create() {
        if (creates[0]++ > 0) {
          throw new IllegalStateException("storage locked");
        }
        return memory.create();
      }
      @Override public void revoke() { memory.revoke(); }
    }, FrameworkUtil.getBundle(AgentsPreferencePageTest.class).getBundleContext(), McpPreferences.node());
    AgentServer.setInstanceForTests(server);
    AgentsPreferencePage page = openAndAllow();
    String old = server.token();
    page.regenerateToken();
    assertEquals(NLS.bind(Messages.regenerateFailed, "storage locked"), page.getErrorMessage());
    assertEquals(old, server.token());
    assertTrue(server.running());
  }

  @Test
  void aServerThatCannotStartKeepsThePageOpenWithTheReason() throws Exception {
    try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      AgentsPreferencePage page = open();
      page.enabledButton().setSelection(true);
      page.portText().setText(Integer.toString(taken.getLocalPort()));
      assertFalse(page.performOk(), "OK keeps the page open: the server is not running");
      UiRepos.flush();
      assertFalse(server.running());
      assertNotNull(server.failure());
      assertEquals(NLS.bind(Messages.failed, server.failure()), page.statusLabel().getText());
      assertFalse(page.copyButton().isEnabled());
      assertFalse(page.regenerateButton().isEnabled());
    }
  }

  @Test
  void cancelAfterAFailedOkLeavesTheSwitchOff() throws Exception {
    try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      AgentsPreferencePage page = open();
      page.enabledButton().setSelection(true);
      page.portText().setText(Integer.toString(taken.getLocalPort()));
      assertFalse(page.performOk());
      assertTrue(page.performCancel());
      UiRepos.flush();
      assertFalse(McpPreferences.enabled(), "the next startup must not start it silently");
      assertFalse(server.running());
    }
  }

  @Test
  void cancelAfterASuccessfulApplyKeepsTheServerRunning() {
    AgentsPreferencePage page = openAndAllow();
    assertTrue(page.performCancel());
    assertTrue(McpPreferences.enabled());
    assertTrue(server.running());
  }

  @Test
  void theMnemonicsOfThePageDoNotClashWithTheDialogButtons() {
    java.util.Set<Character> seen = new java.util.HashSet<>(List.of('a', 'd')); // &Apply, Restore &Defaults
    for (String text : List.of(Messages.allow, Messages.port, Messages.client, Messages.copyConfiguration,
        Messages.regenerateToken, Messages.deleteProposals)) {
      int i = text.indexOf('&');
      assertTrue(i >= 0 && i + 1 < text.length(), text);
      assertTrue(seen.add(Character.toLowerCase(text.charAt(i + 1))), "mnemonic taken twice: " + text);
    }
  }
}
