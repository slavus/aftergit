package net.aftergit.merge.ui;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * The appearance switches of Preferences › AfterGit › Appearance (preferences design §3), in the resolver's node;
 * the painters read them on every paint, open resolvers follow {@link #onChange}.
 */
final class Appearance {
  static final String SIGNS = "appearance.signs";
  static final String HATCH = "appearance.hatch";
  static final String CONNECTOR = "appearance.connector";
  /** Set by the High contrast scheme (Ruling 12). */
  static final String FRAMES = "appearance.frames";

  enum ConnectorWidth {
    NORMAL(70), NARROW(40), WIDE(100);

    private final int pixels;

    ConnectorWidth(int pixels) {
      this.pixels = pixels;
    }

    int pixels() {
      return pixels;
    }
  }

  enum Frames { NORMAL, THICK }

  private Appearance() {}

  private static IEclipsePreferences node() {
    return InstanceScope.INSTANCE.getNode(MergePreferences.NODE);
  }

  static boolean signs() { return node().getBoolean(SIGNS, true); }
  static void setSigns(boolean v) { node().putBoolean(SIGNS, v); flush(); }
  static boolean hatch() { return node().getBoolean(HATCH, true); }
  static void setHatch(boolean v) { node().putBoolean(HATCH, v); flush(); }

  static ConnectorWidth connector() {
    try {
      return ConnectorWidth.valueOf(node().get(CONNECTOR, ConnectorWidth.NORMAL.name()));
    } catch (IllegalArgumentException e) {
      return ConnectorWidth.NORMAL;
    }
  }

  static void setConnector(ConnectorWidth w) { node().put(CONNECTOR, w.name()); flush(); }

  static Frames frames() {
    return Frames.THICK.name().equals(node().get(FRAMES, null)) ? Frames.THICK : Frames.NORMAL;
  }

  static void setFrames(Frames f) {
    if (f == Frames.NORMAL) {
      node().remove(FRAMES);
    } else {
      node().put(FRAMES, f.name());
    }
    flush();
  }

  /** The current block's frame: 2 px, 3 px thick. */
  static int currentFrame() { return frames() == Frames.THICK ? 3 : 2; }
  /** Top and bottom borders of blocks and the outlines of connector bands: 1 px, 2 px thick. */
  static int border() { return frames() == Frames.THICK ? 2 : 1; }
  /** The frame of a block that does not compile: 2 px, 3 px thick. */
  static int errorFrame() { return frames() == Frames.THICK ? 3 : 2; }

  /** Runs {@code listener} (on the thread that changed it) after any appearance switch changed; the result stops it. */
  static Runnable onChange(Runnable listener) {
    IEclipsePreferences n = node();
    IEclipsePreferences.IPreferenceChangeListener l = e -> {
      if (e.getKey().startsWith("appearance.")) {
        listener.run();
      }
    };
    n.addPreferenceChangeListener(l);
    return () -> n.removePreferenceChangeListener(l);
  }

  private static void flush() {
    try {
      node().flush();
    } catch (BackingStoreException e) {
      // the value stays in memory for this session
    }
  }
}
