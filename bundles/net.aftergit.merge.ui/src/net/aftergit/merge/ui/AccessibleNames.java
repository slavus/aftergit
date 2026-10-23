package net.aftergit.merge.ui;

import java.util.function.IntFunction;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.widgets.Control;

/** The names assistive technology reads for controls whose text is a glyph or that have none. */
final class AccessibleNames {
  private AccessibleNames() {}

  /** Names the control ({@code ACC.CHILDID_SELF}) or its children by index; null leaves the platform's name. */
  static void set(Control c, IntFunction<String> name) {
    c.getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        String n = name.apply(e.childID);
        if (n != null) {
          e.result = n;
        }
      }
    });
  }
}
