package net.aftergit.merge.mcp;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/** The switch and the port of the MCP server (agents design §1, §2); off and 0 (any free port) by default. */
public final class McpPreferences {
  public static final String NODE = "net.aftergit.merge.mcp";
  public static final String ENABLED = "enabled";
  public static final String PORT = "port";

  private McpPreferences() {}

  public static IEclipsePreferences node() {
    return InstanceScope.INSTANCE.getNode(NODE);
  }

  public static boolean enabled() {
    return node().getBoolean(ENABLED, false);
  }

  public static int port() {
    return node().getInt(PORT, 0);
  }

  public static void setEnabled(boolean on) {
    node().putBoolean(ENABLED, on);
    flush();
  }

  public static void setPort(int port) {
    node().putInt(PORT, port);
    flush();
  }

  private static void flush() {
    try {
      node().flush();
    } catch (BackingStoreException e) {
      // the value holds for this session
    }
  }
}
