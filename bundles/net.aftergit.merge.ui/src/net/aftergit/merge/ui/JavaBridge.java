package net.aftergit.merge.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.git.JavaChecks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IEditorSite;

/**
 * Whether the optional Java integration (bundle net.aftergit.merge.jdt and the JDT) is wired to this bundle.
 * Only JavaPaneSetup touches its classes, and only after this check.
 */
final class JavaBridge {
  private static Boolean override;
  private static Boolean wired;

  private JavaBridge() {}

  static void setAvailableForTests(Boolean value) {
    override = value;
  }

  private static volatile ImportLocator locatorForTests;

  /** Replaces the Java import locator for Java files (null: the real one). */
  static void setImportLocatorForTests(ImportLocator locator) {
    locatorForTests = locator;
  }

  static boolean available() {
    if (override != null) {
      return override;
    }
    if (wired == null) {
      try {
        Class.forName("net.aftergit.merge.jdt.JavaPanes", false, JavaBridge.class.getClassLoader());
        wired = true;
      } catch (ClassNotFoundException | LinkageError e) {
        wired = false;
      }
    }
    return wired;
  }

  /** Java panes for a Java file, or null (not Java, no JDT, or the integration failed to load or to start). */
  static PaneSetup create(IFile file, IEditorSite site, MergeSession session) {
    if (!available()) {
      return null;
    }
    try {
      return JavaPaneSetup.create(file, site, session);
    } catch (RuntimeException | LinkageError e) {
      // a broken integration must not cost the resolver: plain panes instead (spec §9); create released the buffer
      ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "Java panes unavailable for " + file.getFullPath() + "; the resolver shows plain panes", e));
      return null;
    }
  }

  /**
   * The import locator of the Java integration for {@code file}, or null (not Java, no JDT, or it failed). A failure
   * while locating gives no section, so the file is merged as plain text; it is logged once.
   */
  static ImportLocator importLocator(IFile file) {
    return importLocator(file, null);
  }

  /**
   * Like {@link #importLocator(IFile)}; once {@code monitor} is cancelled the locator finds nothing and parses
   * nothing, so a cancelled open does not wait for the remaining parses.
   */
  static ImportLocator importLocator(IFile file, IProgressMonitor monitor) {
    if (!available()) {
      return null;
    }
    ImportLocator l;
    try {
      l = JavaPaneSetup.importLocator(file);
    } catch (RuntimeException | LinkageError e) {
      logPlainImports(file, e);
      return null;
    }
    if (l == null) {
      return null;
    }
    ImportLocator locator = locatorForTests != null ? locatorForTests : l;
    AtomicBoolean logged = new AtomicBoolean();
    return text -> {
      if (monitor != null && monitor.isCanceled()) {
        return Optional.empty();
      }
      try {
        return locator.locate(text);
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        // a broken or overwhelmed parser must not keep the resolver from opening (spec §9)
        if (logged.compareAndSet(false, true)) {
          logPlainImports(file, e);
        }
        return Optional.empty();
      }
    };
  }

  /**
   * Saves through the Java Save Actions (preferences design §4); false when the file is no Java file or the Java
   * integration is missing, and the caller saves plainly.
   */
  static boolean saveWithActions(IFile file, IProgressMonitor monitor) throws CoreException {
    if (!available()) {
      return false;
    }
    try {
      return JavaPaneSetup.saveWithActions(file, monitor);
    } catch (org.eclipse.core.runtime.OperationCanceledException e) {
      throw e;
    } catch (RuntimeException e) {
      // a failing save participant fails the save like any other save error: reported, the editor stays dirty
      throw new CoreException(new Status(IStatus.ERROR, "net.aftergit.merge.ui", org.eclipse.osgi.util.NLS.bind(
          Messages.saveActionsFailed, file.getFullPath(),
          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e));
    } catch (LinkageError e) {
      // a broken integration must not cost the save: the text is written as it is (spec §9)
      ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The Java Save Actions are not available; " + file.getFullPath() + " is saved without them", loggable(e)));
      return false;
    }
  }

  /** The Java structure locator, or null without the Java integration (or when it failed to load). */
  static StructureLocator structureLocator() {
    if (!available()) {
      return null;
    }
    try {
      return JavaPaneSetup.structureLocator();
    } catch (RuntimeException | LinkageError e) {
      ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The Java structure is not available; the Structure panel shows no Java members", loggable(e)));
      return null;
    }
  }

  /** JDT's icons for Java nodes, or null without the Java integration; the tree then draws its own. */
  static StructureImages structureImages() {
    if (!available()) {
      return null;
    }
    try {
      return JavaPaneSetup.structureImages();
    } catch (RuntimeException | LinkageError e) {
      return null; // the neutral icons stand in; structureLocator() logs a broken integration
    }
  }

  /**
   * Adjusts the imports of a freshly initialized Java result (no-op without the Java integration or import
   * sections). A failure leaves the result as auto-resolution made it; it is logged.
   */
  static void fixImportsAtOpen(IFile file, MergeModel model, ImportSections imports, IProgressMonitor monitor) {
    if (imports == null || !available()) {
      return;
    }
    try {
      JavaPaneSetup.fixImportsAtOpen(file, model, imports, monitor);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      // a broken or overwhelmed fixer must not keep the resolver from opening (spec §9)
      ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The imports of " + file.getFullPath() + " were not adjusted after auto-resolution", loggable(e)));
    }
  }

  private static volatile Runnable errorsHookForTests;

  /** Runs {@code hook} before each compile of Resolve All; a throwing hook is a failing compile. */
  static void setErrorsHookForTests(Runnable hook) {
    errorsHookForTests = hook;
  }

  /**
   * The Java checks of Resolve All for {@code file} (spec §5.5), or null (not Java, no JDT, or the integration did not
   * load). They never throw: a failing compile reads as unknown and is logged once per file. They run on the job's
   * thread; neither the import fix nor the compile needs the UI thread.
   */
  static JavaChecks javaChecks(IFile file) {
    ImportLocator locator = importLocator(file);
    if (locator == null) {
      return null;
    }
    AtomicBoolean logged = new AtomicBoolean();
    return new JavaChecks() {
      @Override
      public ImportLocator locator() {
        return locator;
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        fixImportsAtOpen(file, model, sections, monitor);
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        try {
          Runnable hook = errorsHookForTests;
          if (hook != null) {
            hook.run();
          }
          return JavaPaneSetup.errors(file, text, monitor);
        } catch (CoreException | RuntimeException | LinkageError | StackOverflowError e) {
          // a cancelled run stops the compile: that is no failure to log, and a later failure is still logged
          boolean cancelled = monitor != null && monitor.isCanceled();
          if (!cancelled && logged.compareAndSet(false, true)) {
            ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "Resolve All could not "
                + "compile " + file.getFullPath() + "; its auto-resolved blocks are not checked", loggable(e)));
          }
          return null;
        }
      }
    };
  }

  private static void logPlainImports(IFile file, Throwable e) {
    ILog.of(JavaBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
        "Java imports of " + file.getFullPath() + " are merged as plain text", loggable(e)));
  }

  /**
   * {@code e} for a logged status. A VirtualMachineError (a parser's StackOverflowError) goes in as the cause of a
   * wrapper: the workbench treats a logged one as fatal and offers to exit, though the resolver recovered from it.
   */
  static Throwable loggable(Throwable e) {
    return e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e;
  }
}
