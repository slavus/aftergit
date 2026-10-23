package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.eclipse.e4.ui.css.swt.theme.ITheme;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.e4.ui.css.swt.theme.IThemeManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/** The e4 dark theme for one test, not persisted; {@link #close} puts the previous theme back (spec §6.4). */
final class ThemeSwitch implements AutoCloseable {
  static final String DARK = "org.eclipse.e4.ui.css.theme.e4_dark";
  static final String LIGHT = "org.eclipse.e4.ui.css.theme.e4_default";
  /** Conflict strong of css/dark.css: our colors have followed the theme once the palette shows it. */
  static final RGB DARK_CONFLICT = new RGB(140, 40, 24);

  private final IThemeEngine engine;
  private final String previous;
  /** GTK's selection colour in the theme the switch leaves (its light variant). */
  private final RGB lightSelection = selection();

  private ThemeSwitch(IThemeEngine engine, String previous) {
    this.engine = engine;
    this.previous = previous;
  }

  /** The theme engine of the workbench display. */
  static IThemeEngine engine() {
    Display d = Display.getCurrent();
    if (d.getData("org.eclipse.e4.ui.css.swt.theme") instanceof IThemeEngine e) {
      return e;
    }
    BundleContext ctx = FrameworkUtil.getBundle(ThemeSwitch.class).getBundleContext();
    ServiceReference<IThemeManager> ref = ctx.getServiceReference(IThemeManager.class);
    assertNotNull(ref, "a theme manager service");
    return ctx.getService(ref).getEngineForDisplay(d);
  }

  private static RGB selection() {
    return Display.getCurrent().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB();
  }

  /**
   * Lets the workbench idle until GTK's system colours follow the theme (Eclipse's DarkThemeProcessor picks GTK's dark
   * or light variant, and SWT reads the new colours only when the event loop sleeps, which the tests' flushing never
   * does), then styles the theme again, so its CSS and new editors take the colours of that variant, as after the
   * restart a real switch asks for.
   */
  private void settle(String theme, java.util.function.BooleanSupplier gtkFollowed) {
    Display d = Display.getCurrent();
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (!gtkFollowed.getAsBoolean() && System.nanoTime() < end) {
      d.timerExec(20, () -> { });
      if (!d.readAndDispatch()) {
        d.sleep();
      }
    }
    engine.setTheme(theme, false);
    UiDriver.flush();
  }

  static ThemeSwitch dark() {
    IThemeEngine engine = engine();
    ITheme active = engine.getActiveTheme();
    ThemeSwitch s = new ThemeSwitch(engine, active == null ? LIGHT : active.getId());
    try {
      engine.setTheme(DARK, false);
      s.settle(DARK, () -> !s.lightSelection.equals(selection()));
      ConflictRepos.waitUntil(() -> DARK_CONFLICT.equals(Palette.strong(LineStyle.Kind.CONFLICT).getRGB()), 10,
          "the dark AfterGit colors");
      UiDriver.flush();
    } catch (RuntimeException | AssertionError e) {
      try {
        s.close(); // never leave the next tests in the dark theme
      } catch (RuntimeException | AssertionError again) {
        e.addSuppressed(again);
      }
      throw e;
    }
    return s;
  }

  @Override
  public void close() {
    engine.setTheme(previous, false);
    UiDriver.flush();
    if (!DARK.equals(previous)) {
      settle(previous, () -> lightSelection.equals(selection()));
      ConflictRepos.waitUntil(() -> !DARK_CONFLICT.equals(Palette.strong(LineStyle.Kind.CONFLICT).getRGB()), 10,
          "the light AfterGit colors");
    }
  }
}
