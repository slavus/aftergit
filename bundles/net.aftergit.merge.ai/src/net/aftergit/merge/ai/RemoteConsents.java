package net.aftergit.merge.ai;

import net.aftergit.merge.core.ai.AiConsent;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/** The destinations the user said "Do not ask again" for (design §5), as the preference page shows and forgets them. */
public final class RemoteConsents {
  private RemoteConsents() {}

  private static IEclipsePreferences node() {
    return InstanceScope.INSTANCE.getNode(AiConsent.NODE);
  }

  /** How many destinations are kept; 0 when the preferences cannot be read. */
  public static int count() {
    try {
      int n = 0;
      for (String k : node().keys()) {
        if (k.startsWith(AiConsent.PREFIX)) {
          n++;
        }
      }
      return n;
    } catch (BackingStoreException e) {
      return 0;
    }
  }

  /** Forgets every kept destination, so the next run to any server off this computer asks again; how many. */
  public static int forget() throws BackingStoreException {
    IEclipsePreferences node = node();
    int n = 0;
    for (String k : node.keys()) {
      if (k.startsWith(AiConsent.PREFIX)) {
        node.remove(k);
        n++;
      }
    }
    if (n > 0) {
      node.flush();
    }
    return n;
  }
}
