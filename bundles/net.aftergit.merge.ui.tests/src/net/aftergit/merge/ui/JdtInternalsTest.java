package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.jdt.JavaPanes;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.osgi.framework.Version;
import org.junit.jupiter.api.Test;

/**
 * Fails when an Eclipse update changes what JdtResultEditorAdapter relies on (spec §7.2), or the semantic
 * highlightings SemanticScanner re-implements for the side panes.
 */
class JdtInternalsTest {
  @Test
  void internalsUsedByTheResultEditorAreThere() {
    assertEquals(List.of(), JavaPanes.missingJdtInternals());
  }

  @Test
  void jdtUiIsAVersionTheAdapterWasCheckedAgainst() {
    Version v = Platform.getBundle("org.eclipse.jdt.ui").getVersion();
    assertTrue(JavaPanes.isTestedJdtUi(v), "re-check JdtResultEditorAdapter and SemanticScanner against org.eclipse.jdt.ui " + v
        + ", then widen JavaPanes.TESTED_JDT_UI");
  }

  @Test
  void theSidePanesKnowEverySemanticHighlightingOfJdtUi() throws Exception {
    PreferenceConstants.getPreferenceStore(); // its defaults are set once the store is first asked for
    Pattern enabled = Pattern.compile("semanticHighlighting\\.(.+)\\.enabled");
    Set<String> jdt = new TreeSet<>();
    for (String key : DefaultScope.INSTANCE.getNode("org.eclipse.jdt.ui").keys()) {
      Matcher m = enabled.matcher(key);
      if (m.matches()) {
        jdt.add(m.group(1));
      }
    }
    assertFalse(jdt.isEmpty(), "jdt.ui has semantic highlightings");
    assertEquals(jdt, new TreeSet<>(JavaPanes.semanticHighlightingKeys()),
        "jdt.ui added or removed a semantic highlighting: follow it in SemanticScanner.Kind");
  }
}
