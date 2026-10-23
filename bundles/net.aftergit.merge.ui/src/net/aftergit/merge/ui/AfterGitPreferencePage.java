package net.aftergit.merge.ui;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Preferences › AfterGit (preferences design §4): what AfterGit does when it opens, resolves and saves a file, with
 * the defaults of the design; above the pages Appearance and those of the optional parts.
 */
public final class AfterGitPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  static final String ID = "net.aftergit.merge.preferences";
  private static final int WRAP = 460;

  private final Map<String, Button> checks = new LinkedHashMap<>();
  private final Map<MergePreferences.FinishPolicy, Button> finish = new EnumMap<>(MergePreferences.FinishPolicy.class);
  private Combo layout;

  @Override
  public void init(IWorkbench workbench) {
    // the values are read when the controls are made
  }

  @Override
  protected Control createContents(Composite parent) {
    Composite c = new Composite(parent, SWT.NONE);
    GridLayoutFactory.fillDefaults().applyTo(c);
    Label intro = new Label(c, SWT.WRAP);
    intro.setText(Messages.preferencesRoot);
    GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).applyTo(intro);

    Group opening = group(c, Messages.prefGroupOpening);
    for (ResultSetting s : ResultSetting.opening()) {
      check(opening, s.key(), s.label(), s.help());
    }
    Composite row = new Composite(opening, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(2).applyTo(row);
    new Label(row, SWT.NONE).setText(Messages.prefLayout);
    layout = new Combo(row, SWT.READ_ONLY);
    for (PaneLayout pl : PaneLayout.values()) {
      layout.add(MergeViewer.layoutLabel(pl));
    }
    layout.select(MergePreferences.layout().ordinal());
    check(opening, MergePreferences.STRUCTURE_SHOWN, Messages.prefShowStructure, null);

    Group resolvers = group(c, Messages.prefGroupResolvers);
    for (ResultSetting s : ResultSetting.resolvers()) {
      check(resolvers, s.key(), s.label(), s.help());
    }

    Group editing = group(c, Messages.prefGroupEditing);
    check(editing, MergePreferences.INNER_LINE, Messages.prefInnerLine, null);
    check(editing, MergePreferences.SAVE_ACTIONS, Messages.prefSaveActions, null);
    Composite finishRow = new Composite(editing, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(4).applyTo(finishRow);
    new Label(finishRow, SWT.NONE).setText(Messages.prefFinishWithConflicts); // "&&" shows as "&"
    radio(finishRow, MergePreferences.FinishPolicy.ASK, Messages.prefFinishAsk);
    radio(finishRow, MergePreferences.FinishPolicy.SAVE_WITH_MARKERS, Messages.prefFinishMarkers);
    radio(finishRow, MergePreferences.FinishPolicy.SAVE_WITHOUT_STAGING, Messages.prefFinishWithoutStaging);
    check(editing, MergePreferences.CLOSE_AFTER_FINISH, Messages.prefCloseAfterFinish, null);
    check(editing, MergePreferences.USE_AS_MERGE_TOOL, Messages.prefUseAsMergeTool, Messages.prefUseAsMergeToolHelp);
    check(editing, MergePreferences.MARK_ATTENTION, Messages.prefMarkAttention, Messages.prefMarkAttentionHelp);
    check(editing, MergePreferences.SEMANTIC_SIDES, Messages.preferencesSemanticSides,
        Messages.preferencesSemanticSidesNote);
    if (LspBridge.available()) { // without the languages part the page stays as it was (languages design, "Optional")
      check(editing, MergePreferences.SYNTAX_COLOURS, Messages.preferencesSyntaxColours,
          Messages.preferencesSyntaxColoursNote);
    }

    Link links = new Link(c, SWT.NONE);
    links.setText(Messages.prefLinks);
    links.addListener(SWT.Selection, e -> {
      if ("keys".equals(e.text)) {
        PreferenceLinks.open(this, PreferenceLinks.KEYS, null);
      } else {
        IProject p = PreferenceLinks.selectedProject();
        if (p == null) {
          p = PreferenceLinks.chooseProject(getShell());
        }
        if (p != null) {
          PreferenceLinks.openProjectSettings(getShell(), p);
        }
      }
    });
    return c;
  }

  private static Group group(Composite parent, String text) {
    Group g = new Group(parent, SWT.NONE);
    g.setText(text);
    GridLayoutFactory.swtDefaults().applyTo(g);
    GridDataFactory.fillDefaults().grab(true, false).indent(0, 6).applyTo(g);
    return g;
  }

  private void check(Composite parent, String key, String label, String help) {
    Button b = new Button(parent, SWT.CHECK);
    b.setText(label);
    b.setSelection(MergePreferences.bool(key));
    checks.put(key, b);
    if (help != null) {
      Label h = new Label(parent, SWT.WRAP);
      h.setText(help);
      GridDataFactory.fillDefaults().grab(true, false).hint(WRAP, SWT.DEFAULT).indent(20, 0).applyTo(h);
    }
  }

  private void radio(Composite parent, MergePreferences.FinishPolicy p, String label) {
    Button b = new Button(parent, SWT.RADIO);
    b.setText(label);
    b.setSelection(MergePreferences.finishPolicy() == p);
    finish.put(p, b);
  }

  Button check(String key) {
    return checks.get(key);
  }

  Combo layoutCombo() {
    return layout;
  }

  Button finishButton(MergePreferences.FinishPolicy p) {
    return finish.get(p);
  }

  @Override
  protected void performDefaults() {
    checks.forEach((k, b) -> b.setSelection(MergePreferences.defaultOf(k)));
    layout.select(PaneLayout.ALL.ordinal());
    finish.forEach((p, b) -> b.setSelection(p == MergePreferences.FinishPolicy.ASK));
    super.performDefaults();
  }

  @Override
  public boolean performOk() {
    boolean wasMarking = MergePreferences.markAttention();
    checks.forEach((k, b) -> {
      if (b.getSelection() != MergePreferences.bool(k)) {
        MergePreferences.setBool(k, b.getSelection());
      }
    });
    if (wasMarking != MergePreferences.markAttention()) {
      AttentionMarkers.preferenceChanged();
    }
    PaneLayout pl = PaneLayout.values()[Math.max(0, layout.getSelectionIndex())];
    if (pl != MergePreferences.layout()) {
      MergePreferences.setLayout(pl);
    }
    finish.forEach((p, b) -> {
      if (b.getSelection() && p != MergePreferences.finishPolicy()) {
        MergePreferences.setFinishPolicy(p);
      }
    });
    return true;
  }
}
