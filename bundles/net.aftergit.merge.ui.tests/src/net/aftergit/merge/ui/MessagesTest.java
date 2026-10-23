package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.junit.jupiter.api.Test;

/** NLS reads .properties files as ISO-8859-1: anything else must be written as a unicode escape. */
class MessagesTest {
  @Test
  void noMessageShowsMisreadUtf8() throws Exception {
    for (Field f : Messages.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        String text = (String) f.get(null);
        assertNotNull(text, f.getName());
        assertFalse(text.contains("Â") || text.contains("Ã") || text.contains("�"),
            f.getName() + " = " + text);
      }
    }
  }

  @Test
  void theNotCompilingDetailHasAMiddleDot() {
    assertEquals("a · b", NLS.bind(Messages.detailWithNotCompiling, "a", "b"));
  }

  @Test
  void propertiesFilesAreAscii() throws Exception {
    try (InputStream in = Messages.class.getResourceAsStream("messages.properties")) {
      assertAscii("messages.properties", in.readAllBytes());
    }
    URL plugin = Platform.getBundle("net.aftergit.merge.ui").getEntry("plugin.properties");
    try (InputStream in = plugin.openStream()) {
      assertAscii("plugin.properties", in.readAllBytes());
    }
  }

  private static void assertAscii(String name, byte[] bytes) {
    for (int i = 0; i < bytes.length; i++) {
      assertTrue(bytes[i] >= 0, name + ": byte " + (bytes[i] & 0xff) + " at offset " + i + " is not ASCII");
    }
  }
}
