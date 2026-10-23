package net.aftergit.merge.ui;

import java.util.Arrays;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.model.WorkbenchLabelProvider;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;

/** Links of the AfterGit pages to Eclipse's own pages (preferences design §3–§6, Ruling 21). */
final class PreferenceLinks {
  static final String COLORS_AND_FONTS = "org.eclipse.ui.preferencePages.ColorsAndFonts";
  static final String KEYS = "org.eclipse.ui.preferencePages.Keys";
  static final String KEYS_FILTER = "AfterGit";

  private PreferenceLinks() {}

  /** Shows {@code pageId} with {@code data} in the dialog {@code from} is in, or in a new one. */
  static void open(PreferencePage from, String pageId, Object data) {
    if (from.getContainer() instanceof IWorkbenchPreferenceContainer c && c.openPage(pageId, data)) {
      if (KEYS.equals(pageId) && from.getContainer() instanceof PreferenceDialog d) {
        filterKeys((IPreferencePage) d.getSelectedPage());
      }
      return;
    }
    PreferenceDialog d = KEYS.equals(pageId) ? keysDialog(from.getShell())
        : PreferencesUtil.createPreferenceDialogOn(from.getShell(), pageId, null, data);
    d.open();
  }

  /** The Keys page filtered to AfterGit, created but not open. */
  static PreferenceDialog keysDialog(Shell shell) {
    PreferenceDialog d = PreferencesUtil.createPreferenceDialogOn(shell, KEYS, null, null);
    filterKeys((IPreferencePage) d.getSelectedPage());
    return d;
  }

  /** Colors and Fonts with {@code data} ("selectCategory:…", "selectFont:…"), created but not open. */
  static PreferenceDialog colorsAndFontsDialog(Shell shell, String data) {
    return PreferencesUtil.createPreferenceDialogOn(shell, COLORS_AND_FONTS, null, data);
  }

  /** Types the filter of the Keys page: the page takes no filter as data. */
  private static void filterKeys(IPreferencePage page) {
    Text filter = page == null ? null : findFilter(page.getControl());
    if (filter != null) {
      filter.setText(KEYS_FILTER);
    }
  }

  /** The first search field under {@code c}: the filter of a filtered tree. */
  static Text findFilter(Control c) {
    if (c instanceof Text t && (t.getStyle() & SWT.SEARCH) != 0) {
      return t;
    }
    if (c instanceof Composite parent) {
      for (Control child : parent.getChildren()) {
        Text found = findFilter(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** Project › Properties › AfterGit of {@code project}. */
  static void openProjectSettings(Shell shell, IProject project) {
    PreferenceDialog d = projectSettingsDialog(shell, project);
    if (d != null) { // null for an element without property pages
      d.open();
    }
  }

  /** Project › Properties › AfterGit of {@code project}, created but not open. */
  static PreferenceDialog projectSettingsDialog(Shell shell, IProject project) {
    return PreferencesUtil.createPropertyDialogOn(shell, project, ProjectSettings.PROPERTY_PAGE_ID,
        new String[] {ProjectSettings.PROPERTY_PAGE_ID}, null);
  }

  /** The project of the selection's first resource; null without one. */
  static IProject projectOf(ISelection selection) {
    if (selection instanceof IStructuredSelection s && !s.isEmpty()) {
      IResource r = Adapters.adapt(s.getFirstElement(), IResource.class);
      return r == null ? null : r.getProject();
    }
    return null;
  }

  /** The selected project (Ruling 3): selection of the active part, else the active editor's file, else null. */
  static IProject selectedProject() {
    IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IWorkbenchPage page = w == null ? null : w.getActivePage();
    if (page == null) {
      return null;
    }
    IWorkbenchPart part = page.getActivePart();
    IProject p = part == null || part.getSite().getSelectionProvider() == null ? null
        : projectOf(part.getSite().getSelectionProvider().getSelection());
    IEditorPart editor = page.getActiveEditor();
    if (p == null && editor != null) {
      IResource r = Adapters.adapt(editor.getEditorInput(), IResource.class);
      p = r == null ? null : r.getProject();
    }
    return p;
  }

  /** Asks for one of the open projects; null when there is none or the user cancels. */
  static IProject chooseProject(Shell shell) {
    IProject[] open = Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
        .filter(IProject::isAccessible).toArray(IProject[]::new);
    if (open.length == 0) {
      return null;
    }
    ElementListSelectionDialog d = new ElementListSelectionDialog(shell, new WorkbenchLabelProvider());
    d.setTitle(Messages.prefChooseProjectTitle);
    d.setMessage(Messages.prefChooseProject);
    d.setElements(open);
    return d.open() == Window.OK && d.getFirstResult() instanceof IProject p ? p : null;
  }
}
