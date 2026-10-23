package net.aftergit.merge.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.JavaChecks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Whether the optional languages integration (bundle net.aftergit.merge.lsp and tm4e) is wired to this bundle
 * (languages design, "Optional"). Only LspPaneSetup touches its classes, and only after this check.
 */
final class LspBridge {
  private static Boolean override;
  private static Boolean wired;

  private LspBridge() {}

  static void setAvailableForTests(Boolean value) {
    override = value;
  }

  static boolean available() {
    if (override != null) {
      return override;
    }
    if (wired == null) {
      try {
        Class.forName("net.aftergit.merge.lsp.Grammars", false, LspBridge.class.getClassLoader());
        wired = true;
      } catch (ClassNotFoundException | LinkageError e) {
        wired = false;
      }
    }
    return wired;
  }

  /**
   * The checks of Resolve All for a file with a language server (languages design, Part 2), or null (no bundle, no
   * server, a Java file, or a failing integration). No import pass; the compile check is the server's errors. They
   * never throw: a failing check reads as unknown and is logged once per file. They block while the server starts and
   * answers, so they run on Resolve All's job thread only.
   */
  static JavaChecks checks(IFile file) {
    if (!available()) {
      return null;
    }
    try {
      if (!LspPaneSetup.hasServer(file)) {
        return null;
      }
    } catch (RuntimeException | LinkageError e) {
      ILog.of(LspBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "The language servers are "
          + "not available for " + file.getFullPath() + "; Resolve All does not check its errors",
          JavaBridge.loggable(e)));
      return null;
    }
    AtomicBoolean logged = new AtomicBoolean();
    return new JavaChecks() {
      @Override
      public ImportLocator locator() {
        return null;
      }

      @Override
      public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {
        // imports are Java's (spec §5.3)
      }

      @Override
      public List<LineProblem> errors(String text, IProgressMonitor monitor) {
        try {
          return LspPaneSetup.errors(file, text, monitor);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          boolean cancelled = monitor != null && monitor.isCanceled();
          if (!cancelled && logged.compareAndSet(false, true)) {
            ILog.of(LspBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "Resolve All could not "
                + "check " + file.getFullPath() + " with its language server; its auto-resolved blocks are not checked",
                JavaBridge.loggable(e)));
          }
          return null;
        }
      }

      @Override
      public List<List<LineProblem>> errors(String result, String left, String right, IProgressMonitor monitor) {
        try {
          return LspPaneSetup.errors(file, result, left, right, monitor);
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          boolean cancelled = monitor != null && monitor.isCanceled();
          if (!cancelled && logged.compareAndSet(false, true)) {
            ILog.of(LspBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", "Resolve All could not "
                + "check " + file.getFullPath() + " with its language server; its auto-resolved blocks are not checked",
                JavaBridge.loggable(e)));
          }
          return null;
        }
      }
    };
  }

  /**
   * Panes of the languages integration for {@code file} (TextMate colours, a language server), or null (no bundle,
   * neither of them, or a failing integration).
   */
  static PaneSetup create(IFile file, MergeSession session) {
    if (!available()) {
      return null;
    }
    try {
      return LspPaneSetup.create(file, session);
    } catch (RuntimeException | LinkageError e) {
      // a broken integration must not cost the resolver: plain panes instead (spec Principles)
      ILog.of(LspBridge.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "The languages integration failed for " + file.getFullPath() + "; the resolver shows plain panes "
          + "without TextMate colours or language server errors",
          JavaBridge.loggable(e)));
      return null;
    }
  }
}
