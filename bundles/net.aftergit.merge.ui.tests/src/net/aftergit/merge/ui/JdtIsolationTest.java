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
 * The bundle does not see JDT (only net.aftergit.merge.jdt does): a JDT type in the bytecode of one of its classes
 * fails that class with NoClassDefFoundError in an installed Eclipse, while this fragment, which requires JDT, hides
 * the failure from every other test.
 */
class JdtIsolationTest {

  @Test
  void noClassOfTheBundleNamesAJdtType() throws Exception {
    Bundle host = FrameworkUtil.getBundle(JavaBridge.class);
    BundleWiring wiring = host.adapt(BundleWiring.class);
    // the port of a bundle resource URL is the index of its class path entry: the fragment's classes are elsewhere
    int hostEntry = JavaBridge.class.getResource("JavaBridge.class").getPort();
    List<String> offenders = new ArrayList<>();
    int checked = 0;
    for (String name : wiring.listResources("net/aftergit/merge/ui", "*.class", BundleWiring.LISTRESOURCES_RECURSE)) {
      URL url = wiring.getClassLoader().getResource(name);
      if (url == null || url.getPort() != hostEntry) {
        continue; // a class of this fragment
      }
      checked++;
      try (InputStream in = url.openStream()) {
        if (new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("org/eclipse/jdt/")) {
          offenders.add(name);
        }
      }
    }
    assertTrue(checked > 50, "classes of the bundle found: " + checked);
    assertEquals(List.of(), offenders);
  }
}
