package net.aftergit.merge.mcp.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.Test;

/** NLS reads .properties files as ISO-8859-1: anything else must be written as a unicode escape. */
class McpMessagesTest {
  @Test
  void everyMessageIsThereAndReadable() throws Exception {
    for (Field f : Messages.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        String text = (String) f.get(null);
        assertNotNull(text, f.getName());
        assertFalse(text.startsWith("NLS missing message"), f.getName());
        assertFalse(text.contains("Â") || text.contains("Ã") || text.contains("�"), f.getName());
      }
    }
  }

  @Test
  void theStartupEntryHasTheNameTheDocumentationUses() {
    // General > Startup and Shutdown lists the bundle by its Bundle-Name
    String name = Platform.getBundle("net.aftergit.merge.mcp").getHeaders().get("Bundle-Name");
    assertEquals("AfterGit AI Agents", name);
    assertTrue(Messages.description.contains("\"" + name + "\""), Messages.description);
  }

  @Test
  void propertiesFilesAreAscii() throws Exception {
    try (InputStream in = Messages.class.getResourceAsStream("messages.properties")) {
      assertAscii("messages.properties", in.readAllBytes());
    }
    try (InputStream in = Platform.getBundle("net.aftergit.merge.mcp").getEntry("plugin.properties").openStream()) {
      assertAscii("plugin.properties", in.readAllBytes());
    }
  }

  private static void assertAscii(String name, byte[] bytes) {
    for (int i = 0; i < bytes.length; i++) {
      assertTrue(bytes[i] >= 0, name + ": byte " + (bytes[i] & 0xff) + " at offset " + i + " is not ASCII");
    }
  }
}
