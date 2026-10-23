package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.dialogs.PropertyPage;

/**
 * Project › Properties › AfterGit (preferences design §6): the settings that shape results, for the team, in the
 * project's .settings; Eclipse's "Enable project specific settings" pattern (Ruling 2). Without its own settings the
 * page shows the workspace values that apply.
 */
public final class AfterGitPropertyPage extends PropertyPage {
  private static final int WRAP = 460;
  private final Map<String, Button> checks = new LinkedHashMap<>();
  private final List<Control> helps = new ArrayList<>();
  private Button enable;

  private IProject project() {
    return Adapters.adapt(getElement(), IProject.class);
  }

  @Override
  protected Control createContents(Composite parent) {
    IProject p = project();
    Composite c = new Composite(parent, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(2).applyTo(c);
    enable = new Button(c, SWT.CHECK);
    enable.setText(Messages.propEnable);
    enable.setSelection(ProjectSettings.enabled(p));
    enable.addListener(SWT.Selection, e -> {
      if (!enable.getSelection()) {
        showWorkspace(); // what applies without the project's own settings
      }
      updateEnabled();
    });
    Link workspace = new Link(c, SWT.NONE);
    workspace.setText(Messages.propWorkspaceLink);
    GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).grab(true, false).applyTo(workspace);
    workspace.addListener(SWT.Selection, e -> {
      PreferenceDialog d = PreferencesUtil.createPreferenceDialogOn(getShell(), AfterGitPreferencePage.ID,
          new String[] {AfterGitPreferencePage.ID}, null);
      d.open();
    });
    Label stored = new Label(c, SWT.WRAP);
    stored.setText(Messages.propStored);
    GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(WRAP, SWT.DEFAULT).indent(0, 4).applyTo(stored);

    Group opening = group(c, Messages.prefGroupOpening);
    for (ResultSetting s : ResultSetting.opening()) {
      check(opening, p, s);
    }
    Group resolvers = group(c, Messages.prefGroupResolvers);
    for (ResultSetting s : ResultSetting.resolvers()) {
      check(resolvers, p, s);
    }

    Label personal = new Label(c, SWT.WRAP);
    personal.setText(Messages.propPersonal);
    GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(WRAP, SWT.DEFAULT).indent(0, 8).applyTo(personal);
    updateEnabled();
    return c;
  }

  private static Group group(Composite parent, String text) {
    Group g = new Group(parent, SWT.NONE);
    g.setText(text);
    GridLayoutFactory.swtDefaults().applyTo(g);
    GridDataFactory.fillDefaults().span(2, 1).grab(true, false).indent(0, 6).applyTo(g);
    return g;
  }

  private void check(Composite parent, IProject p, ResultSetting s) {
    Button b = new Button(parent, SWT.CHECK);
    b.setText(s.label());
    b.setSelection(ProjectSettings.bool(p, s.key(), s.def())); // without own settings: the workspace's
    checks.put(s.key(), b);
    if (s.help() != null) {
      Label h = new Label(parent, SWT.WRAP);
      h.setText(s.help());
      GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).indent(20, 0).applyTo(h);
      helps.add(h);
    }
  }

  private void showWorkspace() {
    checks.forEach((k, b) -> b.setSelection(MergePreferences.bool(k)));
  }

  private void updateEnabled() {
    boolean on = enable.getSelection();
    checks.values().forEach(b -> b.setEnabled(on));
    helps.forEach(h -> h.setEnabled(on));
  }

  Button enableButton() {
    return enable;
  }

  /** The check box of {@code key}; null for a key the project does not carry. */
  Button check(String key) {
    return checks.get(key);
  }

  @Override
  protected void performDefaults() {
    enable.setSelection(false);
    showWorkspace();
    updateEnabled();
    super.performDefaults();
  }

  @Override
  public boolean performOk() {
    IProject p = project();
    if (p == null || enable == null) {
      return true;
    }
    Map<String, Boolean> values = new LinkedHashMap<>();
    if (enable.getSelection()) {
      checks.forEach((k, b) -> values.put(k, b.getSelection())); // every value, also a default one (Ruling 1)
    }
    ProjectSettings.store(p, values);
    return true;
  }
}
