package net.aftergit.merge.ai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

/** The page's texts exist and their files are ASCII (NLS reads .properties as ISO-8859-1). */
class AiMessagesTest {
  @Test
  void everyMessageHasAText() throws Exception {
    for (Field f : Messages.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        String text = (String) f.get(null);
        assertNotNull(text, f.getName());
        assertFalse(text.startsWith("NLS missing message"), f.getName());
      }
    }
  }

  @Test
  void propertiesFilesAreAscii() throws Exception {
    try (InputStream in = Messages.class.getResourceAsStream("messages.properties")) {
      assertAscii("messages.properties", in.readAllBytes());
    }
    URL plugin = Platform.getBundle("net.aftergit.merge.ai").getEntry("plugin.properties");
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
