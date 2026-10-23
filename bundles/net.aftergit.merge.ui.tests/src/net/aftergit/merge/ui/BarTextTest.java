package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The status bar's text widget behaves like a label for the keyboard and for screen readers. */
class BarTextTest {
  Shell shell;
  BarText bar;

  @BeforeEach
  void setUp() {
    shell = new Shell(Display.getCurrent());
    shell.setLayout(new FillLayout());
    bar = new BarText(shell, false);
    bar.setText("2 conflicts");
    shell.open();
  }

  @AfterEach
  void tearDown() {
    shell.dispose();
  }

  @Test
  void neverTakesTheKeyboardFocus() {
    assertNotEquals(0, bar.getStyle() & SWT.NO_FOCUS);
    bar.setFocus();
    assertNotSame(bar, Display.getCurrent().getFocusControl());
  }

  @Test
  void isALabelForScreenReaders() {
    AccessibleControlEvent e = new AccessibleControlEvent(bar);
    e.childID = ACC.CHILDID_SELF;
    bar.accessibleControl().getRole(e);
    assertEquals(ACC.ROLE_LABEL, e.detail);
    e.result = null;
    bar.accessibleControl().getValue(e);
    assertEquals("2 conflicts", e.result);
  }
}
