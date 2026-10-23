package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preferences design §6: Project › Properties › AfterGit. */
class AfterGitPropertyPageTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  Shell shell;
  IProject project;
  AfterGitPropertyPage page;

  @BeforeEach
  void setUp() throws Exception {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    project = repos.plainProject("project-page", "a.txt").getProject();
    shell = new Shell(Display.getCurrent());
  }

  @AfterEach
  void cleanup() throws Exception {
    if (page != null) {
      page.dispose();
    }
    shell.dispose();
    repos.dispose();
    MergePreferences.reset();
  }

  private AfterGitPropertyPage page() {
    page = new AfterGitPropertyPage();
    page.setElement(project);
    page.createControl(shell);
    return page;
  }

  private static void toggle(Button b, boolean on) {
    b.setSelection(on);
    b.notifyListeners(SWT.Selection, new Event());
  }

  private String settingsFile() throws Exception {
    File f = project.getFile(".settings/net.aftergit.merge.ui.prefs").getLocation().toFile();
    return f.exists() ? Files.readString(f.toPath(), StandardCharsets.ISO_8859_1) : "";
  }

  @Test
  void thePageIsThereForEveryProject() {
    PreferenceDialog d = PreferencesUtil.createPropertyDialogOn(shell, project, ProjectSettings.PROPERTY_PAGE_ID,
        new String[] {ProjectSettings.PROPERTY_PAGE_ID}, null);
    try {
      assertInstanceOf(AfterGitPropertyPage.class, d.getSelectedPage());
    } finally {
      d.close();
    }
  }

  @Test
  void withoutOwnSettingsThePageShowsTheWorkspaceValuesDisabled() {
    MergePreferences.setGreedy(true);
    AfterGitPropertyPage p = page();
    assertFalse(p.enableButton().getSelection());
    assertTrue(p.check(MergePreferences.GREEDY).getSelection(), "the workspace value");
    assertFalse(p.check(MergePreferences.GREEDY).isEnabled());
    assertNotNull(p.check(MergePreferences.AUTO_RESOLVE_ON_OPEN));
    assertNotNull(p.check(MergePreferences.IGNORE_WHITESPACE));
    assertNotNull(p.check(MergePreferences.RESOLVER_PREFIX + NewerVersionResolver.ID));
    assertNull(p.check(MergePreferences.INNER_LINE), "personal settings are not on the project page");
    assertNull(p.check(MergePreferences.LAYOUT), "personal settings are not on the project page");
    assertNull(p.check(MergePreferences.SYNTAX_COLOURS), "personal settings are not on the project page");
  }

  @Test
  void enablingWritesEveryResultSettingIntoTheProjectsSettingsFile() throws Exception {
    AfterGitPropertyPage p = page();
    toggle(p.enableButton(), true);
    assertTrue(p.check(MergePreferences.GREEDY).isEnabled());
    p.check(MergePreferences.GREEDY).setSelection(true);
    assertTrue(p.performOk());
    String text = settingsFile();
    assertTrue(text.contains("greedy=true"), text);
    assertTrue(text.contains("autoResolveOnOpen=true"), text);
    assertTrue(text.contains("ignoreWhitespace=false"), text);
    assertTrue(text.contains("resolver." + PropertiesResolver.ID + "=true"), text);
    assertTrue(text.contains("resolver." + NewerVersionResolver.ID + "=false"), text);
    assertFalse(text.contains("innerLine"), text);
    assertTrue(MergePreferences.greedy(project));
    assertFalse(MergePreferences.greedy(), "the workspace is untouched");
  }

  @Test
  void disablingRemovesTheProjectsValues() throws Exception {
    ProjectSettings.store(project, Map.of(MergePreferences.GREEDY, true));
    AfterGitPropertyPage p = page();
    assertTrue(p.enableButton().getSelection());
    assertTrue(p.check(MergePreferences.GREEDY).getSelection());
    assertTrue(p.check(MergePreferences.GREEDY).isEnabled());
    toggle(p.enableButton(), false);
    assertFalse(p.check(MergePreferences.GREEDY).isEnabled());
    assertFalse(p.check(MergePreferences.GREEDY).getSelection(), "what applies now: the workspace value");
    assertTrue(p.performOk());
    assertFalse(ProjectSettings.enabled(project));
    assertFalse(settingsFile().contains("greedy"));
  }

  @Test
  void restoreDefaultsShowsTheWorkspaceAndTurnsTheProjectsOwnSettingsOff() {
    ProjectSettings.store(project, Map.of(MergePreferences.GREEDY, true));
    AfterGitPropertyPage p = page();
    p.performDefaults();
    assertFalse(p.enableButton().getSelection());
    assertFalse(p.check(MergePreferences.GREEDY).getSelection());
    assertFalse(p.check(MergePreferences.GREEDY).isEnabled());
    assertTrue(ProjectSettings.enabled(project), "only OK writes");
  }

  @Test
  void everyMnemonicOfThePageIsItsOwnWithRestoreDefaultsAndApply() {
    page();
    List<String> texts = new ArrayList<>();
    collect(shell, texts);
    Map<Character, String> seen = new HashMap<>();
    for (String t : texts) {
      Character m = mnemonic(t);
      if (m != null) {
        String other = seen.put(m, t);
        assertNull(other, "Alt+" + m + " is taken by \"" + other + "\" and \"" + t + "\"");
      }
    }
    for (char c : "JRGWDAIPFN".toCharArray()) {
      assertTrue(seen.containsKey(c), "Alt+" + c + " in " + seen);
    }
  }

  /** The letter after a single '&' ("&&" is a plain '&'), upper case; null without one. */
  private static Character mnemonic(String text) {
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

  private static void collect(Composite c, List<String> out) {
    for (Control child : c.getChildren()) {
      if (child instanceof Button b) {
        out.add(b.getText());
      } else if (child instanceof Label l) {
        out.add(l.getText());
      } else if (child instanceof Group g) {
        out.add(g.getText());
      }
      if (child instanceof Composite inner) {
        collect(inner, out);
      }
    }
  }

  @Test
  void theProjectSettingsLinkAndToolbarItemOpenThisPage() {
    PreferenceDialog d = PreferenceLinks.projectSettingsDialog(shell, project);
    try {
      assertInstanceOf(AfterGitPropertyPage.class, d.getSelectedPage());
    } finally {
      d.close();
    }
  }

  @Test
  void amongAllThePagesOfTheProject() {
    PreferenceDialog d = PreferencesUtil.createPropertyDialogOn(shell, project, ProjectSettings.PROPERTY_PAGE_ID,
        null, null);
    try {
      assertInstanceOf(AfterGitPropertyPage.class, d.getSelectedPage(), "selected among all the project's pages");
    } finally {
      d.close();
    }
  }
}
