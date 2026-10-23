package net.aftergit.merge.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.git.agents.ProposalSource;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The open resolvers and the MCP server's proposal source (agents design §1, §3). The source exists only while the
 * server runs; without it the resolvers show nothing about agents.
 */
final class Agents {
  private static final List<MergeEditorInput> INPUTS = new CopyOnWriteArrayList<>();
  private static volatile ServiceTracker<ProposalSource, ProposalSource> tracker;

  private Agents() {}

  /**
   * Starts watching for the proposal source (once). The tracker opens outside the class lock: opening it may get a
   * service that is already there, and that must not wait on a lock the UI thread takes.
   */
  static void init() {
    ServiceTracker<ProposalSource, ProposalSource> created;
    synchronized (Agents.class) {
      if (tracker != null) {
        return;
      }
      Bundle bundle = FrameworkUtil.getBundle(Agents.class);
      BundleContext context = bundle == null ? null : bundle.getBundleContext();
      if (context == null) {
        return;
      }
      created = newTracker(context);
      tracker = created;
    }
    created.open();
  }

  private static ServiceTracker<ProposalSource, ProposalSource> newTracker(BundleContext context) {
    return new ServiceTracker<>(context, ProposalSource.class, null) {
      @Override
      public ProposalSource addingService(ServiceReference<ProposalSource> reference) {
        ProposalSource s = super.addingService(reference);
        changed();
        return s;
      }

      @Override
      public void removedService(ServiceReference<ProposalSource> reference, ProposalSource service) {
        super.removedService(reference, service);
        changed();
      }
    };
  }

  /** The source while the MCP server runs, else null. */
  static ProposalSource source() {
    ServiceTracker<ProposalSource, ProposalSource> t = tracker;
    return t == null ? null : t.getService();
  }

  static void register(MergeEditorInput input) {
    init();
    INPUTS.add(input);
  }

  static void unregister(MergeEditorInput input) {
    INPUTS.remove(input);
  }

  /** The open resolver of {@code file}, matched by handle or by location. */
  static Optional<MergeEditorInput> openInput(IFile file) {
    for (MergeEditorInput input : INPUTS) {
      IFile f = input.file();
      if (f.equals(file) || f.getLocation() != null && f.getLocation().equals(file.getLocation())) {
        return Optional.of(input);
      }
    }
    return Optional.empty();
  }

  /** Tells every open resolver, on the UI thread, that the source came or went. */
  private static void changed() {
    if (!PlatformUI.isWorkbenchRunning()) {
      return;
    }
    Display d = PlatformUI.getWorkbench().getDisplay();
    if (d.isDisposed()) {
      return;
    }
    try {
      d.asyncExec(() -> {
        ProposalSource s = source();
        for (MergeEditorInput input : INPUTS) {
          try {
            input.agentsChanged(s);
          } catch (RuntimeException | LinkageError | StackOverflowError e) {
            // one resolver that fails must not keep the others from hearing it
            ILog.of(Agents.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
                "A resolver could not follow the AI agents being switched on or off", JavaBridge.loggable(e)));
          }
        }
      });
    } catch (SWTException e) {
      // the workbench is closing: no resolver to tell
    }
  }
}
