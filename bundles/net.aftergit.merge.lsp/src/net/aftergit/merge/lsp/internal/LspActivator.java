package net.aftergit.merge.lsp.internal;

import net.aftergit.merge.lsp.diagnostics.SideServers;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/** Ends AfterGit's own language server instances when the bundle stops, as when Eclipse shuts down; not API. */
public final class LspActivator implements BundleActivator {
  /** Set once a side instance was asked for: a bundle that never checked a side does not load lsp4e to stop. */
  public static volatile boolean sideServersUsed;
  /** Set when the bundle stops: from then on no side instance starts (a Resolve All job may still run). */
  public static volatile boolean stopped;

  /**
   * A start after a stop in the same class loader (the OSGi console, some p2 flows) is a new session: instances may
   * start again and the servers remembered as silent are forgotten. Nothing else until a side is checked.
   */
  @Override
  public void start(BundleContext context) {
    stopped = false;
    if (sideServersUsed) { // a bundle that never checked a side does not load lsp4e here
      SideServers.forgetSilent();
    }
  }

  @Override
  public void stop(BundleContext context) {
    stopped = true; // before the instances stop, so that no later use starts one
    if (sideServersUsed) {
      SideServers.stopAll();
    }
  }
}
