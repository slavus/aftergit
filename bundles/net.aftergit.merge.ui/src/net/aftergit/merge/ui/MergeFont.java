package net.aftergit.merge.ui;

import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;

/**
 * The font of the merge panes (preferences design §2): "AfterGit merge panes" in Colors and Fonts, by default the
 * Text Font. Callers must not keep it: a change replaces it in the registry.
 */
final class MergeFont {
  static final String ID = "net.aftergit.merge.font.panes";
  /** The Java editor's font: its editor sets it on the result's viewer when it changes (Ruling 16). */
  static final String JDT_FONT = "org.eclipse.jdt.ui.editors.textfont";

  private MergeFont() {}

  static Font get() {
    if (!PlatformUI.isWorkbenchRunning()) {
      return JFaceResources.getTextFont();
    }
    FontRegistry r = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getFontRegistry();
    return r.hasValueFor(ID) ? r.get(ID) : JFaceResources.getTextFont();
  }

  /**
   * Runs {@code apply} on the UI thread after every change that may change the merge font or take it from a pane
   * (its entry, the Text Font it follows, the Java editor's font, a theme switch), until {@code owner} is disposed.
   */
  static void watch(Control owner, Runnable apply) {
    if (!PlatformUI.isWorkbenchRunning()) {
      return;
    }
    IThemeManager themes = PlatformUI.getWorkbench().getThemeManager();
    Display display = owner.getDisplay();
    FontRegistry[] watched = {null};
    IPropertyChangeListener fontListener = e -> {
      String p = e.getProperty();
      if (ID.equals(p) || JDT_FONT.equals(p) || JFaceResources.TEXT_FONT.equals(p)) {
        // after the listeners that react first (the Java editor resets its viewer's font)
        display.asyncExec(() -> {
          if (!owner.isDisposed()) {
            apply.run();
          }
        });
      }
    };
    Runnable follow = () -> {
      if (watched[0] != null) {
        watched[0].removeListener(fontListener);
      }
      watched[0] = themes.getCurrentTheme().getFontRegistry();
      watched[0].addListener(fontListener);
    };
    IPropertyChangeListener themeListener = e -> {
      if (IThemeManager.CHANGE_CURRENT_THEME.equals(e.getProperty())) {
        follow.run();
        fontListener.propertyChange(new org.eclipse.jface.util.PropertyChangeEvent(themes, ID, null, null));
      }
    };
    follow.run();
    themes.addPropertyChangeListener(themeListener);
    owner.addDisposeListener(e -> {
      themes.removePropertyChangeListener(themeListener);
      watched[0].removeListener(fontListener);
    });
  }
}
