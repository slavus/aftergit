package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Languages design, "Optional": the bundle does not see tm4e, lsp4e or lsp4j (only net.aftergit.merge.lsp does). Such
 * a type in the bytecode of one of its classes fails that class with NoClassDefFoundError in an Eclipse without
 * them, while this fragment, which requires tm4e, hides the failure from every other test.
 */
class LspIsolationTest {
  private static final List<String> FORBIDDEN = List.of("org/eclipse/tm4e/", "org/eclipse/lsp4e/", "org/eclipse/lsp4j/");

  @Test
  void noClassOfTheBundleNamesATm4eOrLsp4eType() throws Exception {
    Bundle host = FrameworkUtil.getBundle(LspBridge.class);
    BundleWiring wiring = host.adapt(BundleWiring.class);
    // the port of a bundle resource URL is the index of its class path entry: the fragment's classes are elsewhere
    int hostEntry = LspBridge.class.getResource("LspBridge.class").getPort();
    List<String> offenders = new ArrayList<>();
    int checked = 0;
    for (String name : wiring.listResources("net/aftergit/merge/ui", "*.class", BundleWiring.LISTRESOURCES_RECURSE)) {
      URL url = wiring.getClassLoader().getResource(name);
      if (url == null || url.getPort() != hostEntry) {
        continue; // a class of this fragment
      }
      checked++;
      try (InputStream in = url.openStream()) {
        String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        if (FORBIDDEN.stream().anyMatch(bytes::contains)) {
          offenders.add(name);
        }
      }
    }
    assertTrue(checked > 50, "classes of the bundle found: " + checked);
    assertEquals(List.of(), offenders);
  }
}
