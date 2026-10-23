package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ManifestResolver;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preferences › AfterGit (preferences design §4): every behaviour setting with the defaults of the design, the same
 * keys as the toolbar, and the links to Keys and the project settings. The root page comes with the resolver, for
 * every optional page below it (Ruling 18 of plan 6b).
 */
class AfterGitPreferencePageTest {
  static final String ID = AfterGitPreferencePage.ID;

  private Shell shell;
  private AfterGitPreferencePage page;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    shell = new Shell(Display.getCurrent());
  }

  @AfterEach
  void cleanup() {
    if (page != null) {
      page.dispose();
    }
    shell.dispose();
    ResolverContributions.setForTests(null);
    LspBridge.setAvailableForTests(null);
    MergePreferences.reset();
  }

  private AfterGitPreferencePage page() {
    page = new AfterGitPreferencePage();
    page.init(PlatformUI.getWorkbench());
    page.createControl(shell);
    return page;
  }

  private static final List<String> KEYS = List.of(MergePreferences.AUTO_RESOLVE_ON_OPEN, MergePreferences.GREEDY,
      MergePreferences.IGNORE_WHITESPACE, MergePreferences.STRUCTURE_SHOWN,
      MergePreferences.RESOLVER_PREFIX + ImportsResolver.ID, MergePreferences.RESOLVER_PREFIX + PropertiesResolver.ID,
      MergePreferences.RESOLVER_PREFIX + ManifestResolver.ID, MergePreferences.RESOLVER_PREFIX + NewerVersionResolver.ID,
      MergePreferences.INNER_LINE, MergePreferences.SAVE_ACTIONS, MergePreferences.CLOSE_AFTER_FINISH,
      MergePreferences.USE_AS_MERGE_TOOL, MergePreferences.MARK_ATTENTION, MergePreferences.SEMANTIC_SIDES,
      MergePreferences.SYNTAX_COLOURS);

  @Test
  void theSyntaxColoursSwitchStandsNextToTheSemanticOneAndIsWritten() {
    page();
    Button syntax = page.check(MergePreferences.SYNTAX_COLOURS);
    assertNotNull(syntax);
    assertTrue(syntax.getSelection(), "on by default");
    assertEquals(Messages.preferencesSyntaxColours, syntax.getText());
    Control[] siblings = syntax.getParent().getChildren();
    int semantic = java.util.Arrays.asList(siblings).indexOf(page.check(MergePreferences.SEMANTIC_SIDES));
    int own = java.util.Arrays.asList(siblings).indexOf(syntax);
    assertEquals(semantic + 2, own, "right after the semantic switch and its note");
    syntax.setSelection(false);
    assertTrue(page.performOk());
    assertFalse(MergePreferences.syntaxColours());
    page.performDefaults();
    assertTrue(syntax.getSelection());
  }

  @Test
  void withoutTheLanguagesBundleThereIsNoSyntaxColoursSwitch() {
    LspBridge.setAvailableForTests(false);
    page();
    assertNull(page.check(MergePreferences.SYNTAX_COLOURS));
  }

  @Test
  void theRootPageComesFromTheResolverBundle() {
    IPreferenceNode node = PlatformUI.getWorkbench().getPreferenceManager().find(ID);
    assertNotNull(node);
    assertEquals("AfterGit", node.getLabelText());
    node.createPage();
    try {
      assertInstanceOf(AfterGitPreferencePage.class, node.getPage());
    } finally {
      node.disposeResources();
    }
  }

  @Test
  void everyKeyHasTheDefaultOfTheDesign() {
    assertTrue(MergePreferences.autoResolveOnOpen());
    assertFalse(MergePreferences.greedy());
    assertFalse(MergePreferences.ignoreWhitespace(), "off, as before the page");
    assertEquals(PaneLayout.ALL, MergePreferences.layout());
    assertFalse(MergePreferences.structureShown());
    for (String id : List.of(ImportsResolver.ID, ResultSetting.MEMBERS, PropertiesResolver.ID, ManifestResolver.ID)) {
      assertTrue(MergePreferences.resolverEnabled(id), id);
    }
    assertFalse(MergePreferences.resolverEnabled(NewerVersionResolver.ID));
    assertTrue(MergePreferences.innerLine());
    assertFalse(MergePreferences.saveActions(), "AfterGit ran no Save Actions before the switch");
    assertEquals(MergePreferences.FinishPolicy.ASK, MergePreferences.finishPolicy());
    assertTrue(MergePreferences.closeAfterFinish());
    assertTrue(MergePreferences.useAsMergeTool());
    assertTrue(MergePreferences.markAttention());
    assertTrue(MergePreferences.semanticSides());
    assertTrue(MergePreferences.syntaxColours());
    for (String k : KEYS) {
      assertEquals(MergePreferences.bool(k), MergePreferences.defaultOf(k), k);
    }
  }

  @Test
  void anUnknownKeyHasNoDefault() {
    assertThrows(IllegalArgumentException.class, () -> MergePreferences.defaultOf(MergePreferences.STRUCTURE_SHOW_ALL));
    assertThrows(IllegalArgumentException.class, () -> MergePreferences.defaultOf("misspelt"));
  }

  @Test
  void everyMnemonicOfThePageIsItsOwnWithRestoreDefaultsAndApply() {
    ResolverContributions.setForTests(List.of(new ResolverContributions.Contribution(ResultSetting.MEMBERS,
        "Java members", new PropertiesResolver())));
    page();
    List<String> texts = new ArrayList<>();
    collect(shell, texts);
    assertTrue(texts.contains(org.eclipse.jface.resource.JFaceResources.getString("defaults")), texts.toString());
    assertTrue(texts.contains(org.eclipse.jface.resource.JFaceResources.getString("apply")), texts.toString());
    Map<Character, String> seen = new java.util.HashMap<>();
    for (String t : texts) {
      Character m = mnemonic(t);
      if (m != null) {
        String other = seen.put(m, t);
        assertNull(other, "Alt+" + m + " is taken by \"" + other + "\" and \"" + t + "\"");
      }
    }
    for (char c : "RGWLTHVCUMSDIBPFNX".toCharArray()) {
      assertTrue(seen.containsKey(c), "Alt+" + c + " in " + seen);
    }
  }

  /** The letter after a single '&' ("&&" is a plain '&'), upper case; null without one. */
  static Character mnemonic(String text) {
    for (int i = 0; i < text.length() - 1; i++) {
      if (text.charAt(i) == '&') {
        if (text.charAt(i + 1) == '&') {
          i++;
        } else {
          return Character.toUpperCase(text.charAt(i + 1));
        }
      }
    }
    return null;
  }

  static void collect(Composite c, List<String> out) {
    for (Control child : c.getChildren()) {
      if (child instanceof Button b) {
        out.add(b.getText());
      } else if (child instanceof Label l) {
        out.add(l.getText());
      } else if (child instanceof org.eclipse.swt.widgets.Group g) {
        out.add(g.getText());
      }
      if (child instanceof Composite inner) {
        collect(inner, out);
      }
    }
  }

  @Test
  void thePageShowsEveryDefaultAndWritesEveryKeyOnOk() {
    AfterGitPreferencePage p = page();
    for (String k : KEYS) {
      assertNotNull(p.check(k), k);
      assertEquals(MergePreferences.defaultOf(k), p.check(k).getSelection(), k);
      p.check(k).setSelection(!p.check(k).getSelection());
    }
    assertEquals(MergeViewer.layoutLabel(PaneLayout.ALL), p.layoutCombo().getText());
    assertTrue(p.finishButton(MergePreferences.FinishPolicy.ASK).getSelection());
    p.layoutCombo().select(PaneLayout.RESULT_BELOW.ordinal());
    p.finishButton(MergePreferences.FinishPolicy.ASK).setSelection(false);
    p.finishButton(MergePreferences.FinishPolicy.SAVE_WITH_MARKERS).setSelection(true);
    assertTrue(p.performOk());
    for (String k : KEYS) {
      assertEquals(!MergePreferences.defaultOf(k), MergePreferences.bool(k), k);
    }
    assertEquals(PaneLayout.RESULT_BELOW, MergePreferences.layout());
    assertEquals(MergePreferences.FinishPolicy.SAVE_WITH_MARKERS, MergePreferences.finishPolicy());
  }

  @Test
  void restoreDefaultsPutsEveryDefaultBackOnOk() {
    MergePreferences.setGreedy(true);
    MergePreferences.setInnerLine(false);
    MergePreferences.setSaveActions(true);
    MergePreferences.setLayout(PaneLayout.LEFT_RESULT);
    MergePreferences.setFinishPolicy(MergePreferences.FinishPolicy.SAVE_WITHOUT_STAGING);
    AfterGitPreferencePage p = page();
    assertTrue(p.check(MergePreferences.GREEDY).getSelection());
    assertTrue(p.finishButton(MergePreferences.FinishPolicy.SAVE_WITHOUT_STAGING).getSelection());
    p.performDefaults();
    assertTrue(p.performOk());
    assertFalse(MergePreferences.greedy());
    assertTrue(MergePreferences.innerLine());
    assertFalse(MergePreferences.saveActions());
    assertEquals(PaneLayout.ALL, MergePreferences.layout());
    assertEquals(MergePreferences.FinishPolicy.ASK, MergePreferences.finishPolicy());
  }

  @Test
  void theNewerVersionSwitchCarriesItsWarningAndAContributionGetsItsOwnSwitch() {
    ResolverContributions.setForTests(List.of(new ResolverContributions.Contribution("x.example", "Example resolver",
        new PropertiesResolver())));
    AfterGitPreferencePage p = page();
    assertTrue(texts(shell).contains(Messages.prefResolverNewerVersionHelp));
    Button contributed = p.check(MergePreferences.RESOLVER_PREFIX + "x.example");
    assertEquals(NLS.bind(Messages.prefResolverContributed, "Example resolver"), contributed.getText());
    assertTrue(contributed.getSelection());
    assertNull(p.check(MergePreferences.RESOLVER_PREFIX + ResultSetting.MEMBERS), "not contributed here");
  }

  @Test
  void javaMembersStandsAmongTheBuiltInResolversWhenContributed() {
    ResolverContributions.setForTests(List.of(new ResolverContributions.Contribution(ResultSetting.MEMBERS,
        "Java members", new PropertiesResolver())));
    Button members = page().check(MergePreferences.RESOLVER_PREFIX + ResultSetting.MEMBERS);
    assertEquals(Messages.prefResolverMembers, members.getText());
    List<String> labels = ResultSetting.resolvers().stream().map(ResultSetting::label).toList();
    assertEquals(List.of(Messages.prefResolverImports, Messages.prefResolverMembers, Messages.prefResolverProperties,
        Messages.prefResolverManifest, Messages.prefResolverNewerVersion), labels);
  }

  @Test
  void thePageAndTheToolbarWriteTheSameKeys() {
    MergeViewer v = new MergeViewer(shell);
    Menu more = v.toolbar().createMoreMenu(shell);
    try {
      MenuItem greedy = item(submenu(more, Messages.settings), Messages.settingGreedy);
      assertFalse(greedy.getSelection());
      greedy.setSelection(true);
      greedy.notifyListeners(SWT.Selection, new Event());
      assertTrue(MergePreferences.greedy());
      assertTrue(page().check(MergePreferences.GREEDY).getSelection(), "the page shows what the toolbar wrote");
      page.check(MergePreferences.IGNORE_WHITESPACE).setSelection(true);
      page.performOk();
    } finally {
      more.dispose();
    }
    Menu again = v.toolbar().createMoreMenu(shell);
    try {
      assertTrue(item(submenu(again, Messages.settings), Messages.settingIgnoreWhitespace).getSelection());
    } finally {
      again.dispose();
    }
  }

  @Test
  void theInnerLineSwitchOfThePageReachesAnOpenResolver() {
    MergeViewer v = new MergeViewer(shell);
    assertTrue(v.innerLineEnabled());
    page().check(MergePreferences.INNER_LINE).setSelection(false);
    page.performOk();
    UiDriver.flush();
    assertFalse(v.innerLineEnabled(), "inner-line follows the page at once");
  }

  @Test
  void withProjectSettingsTheToolbarShowsTheProjectsValueAndCannotChangeIt(@TempDir File dir) throws Exception {
    ConflictRepos repos = new ConflictRepos(dir);
    try {
      repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
      IProject project = repos.plainProject("prefs-toolbar", "a.txt").getProject();
      ProjectSettings.store(project, Map.of(MergePreferences.GREEDY, true));
      MergeViewer v = new MergeViewer(shell);
      v.setProject(project);
      Menu more = v.toolbar().createMoreMenu(shell);
      try {
        Menu settings = submenu(more, Messages.settings);
        MenuItem greedy = item(settings, Messages.settingGreedy);
        assertTrue(greedy.getSelection(), "the project's value");
        assertFalse(greedy.isEnabled(), "a click would change a workspace value with no effect here");
        assertTrue(item(settings, Messages.settingInnerLine).isEnabled(), "personal settings stay");
        assertNotNull(item(settings, Messages.settingsInProject));
      } finally {
        more.dispose();
      }
      assertFalse(MergePreferences.greedy(), "the workspace is untouched");
    } finally {
      repos.dispose();
    }
  }

  @Test
  void theKeysLinkShowsTheAfterGitCommands() {
    PreferenceDialog d = PreferenceLinks.keysDialog(shell);
    try {
      Text filter = PreferenceLinks.findFilter(((IPreferencePage) d.getSelectedPage()).getControl());
      assertNotNull(filter, "the Keys page has a filter field");
      assertEquals(PreferenceLinks.KEYS_FILTER, filter.getText());
    } finally {
      d.close();
    }
  }

  @Test
  void theProjectLinkTakesTheProjectOfTheSelection(@TempDir File dir) throws Exception {
    ConflictRepos repos = new ConflictRepos(dir);
    try {
      repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
      IFile file = repos.plainProject("prefs-selection", "a.txt");
      assertEquals(file.getProject(), PreferenceLinks.projectOf(new StructuredSelection(file)));
      assertNull(PreferenceLinks.projectOf(StructuredSelection.EMPTY));
    } finally {
      repos.dispose();
    }
  }

  private static List<String> texts(Composite c) {
    List<String> out = new ArrayList<>();
    for (Control child : c.getChildren()) {
      if (child instanceof Label l) {
        out.add(l.getText());
      } else if (child instanceof Composite inner) {
        out.addAll(texts(inner));
      }
    }
    return out;
  }

  private static Menu submenu(Menu menu, String text) {
    return item(menu, text).getMenu();
  }

  private static MenuItem item(Menu menu, String text) {
    for (MenuItem i : menu.getItems()) {
      if (i.getText().equals(text) || i.getText().startsWith(text + "\t")) {
        return i;
      }
    }
    return fail("no menu item " + text);
  }
}
