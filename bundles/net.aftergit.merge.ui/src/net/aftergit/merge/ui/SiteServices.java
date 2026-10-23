package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.IHandler;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.services.IServiceLocator;

/** Context and command handlers of one resolver, active while its editor is active. */
final class SiteServices {
  static final String SCOPE = "net.aftergit.merge.scope";
  static final String NEXT_CHANGE = "net.aftergit.merge.commands.nextChange";
  static final String PREV_CHANGE = "net.aftergit.merge.commands.prevChange";
  static final String NEXT_CONFLICT = "net.aftergit.merge.commands.nextConflict";
  static final String PREV_CONFLICT = "net.aftergit.merge.commands.prevConflict";
  static final String NEXT_ANY_CONFLICT = "net.aftergit.merge.commands.nextAnyConflict";
  static final String PREV_ANY_CONFLICT = "net.aftergit.merge.commands.prevAnyConflict";
  static final String NEXT_ERROR = "net.aftergit.merge.commands.nextError";
  static final String PREV_ERROR = "net.aftergit.merge.commands.prevError";
  static final String NEXT_AUTO = "net.aftergit.merge.commands.nextAutoResolved";
  static final String PREV_AUTO = "net.aftergit.merge.commands.prevAutoResolved";
  static final String TAKE_LEFT = "net.aftergit.merge.commands.takeLeft";
  static final String TAKE_RIGHT = "net.aftergit.merge.commands.takeRight";
  static final String TOGGLE_STRUCTURE = "net.aftergit.merge.commands.toggleStructure";
  static final String TAKE_BOTH = "net.aftergit.merge.commands.takeBoth";
  static final String IGNORE = "net.aftergit.merge.commands.ignore";
  static final String REVERT = "net.aftergit.merge.commands.revert";
  static final String MARK_REVIEWED = "net.aftergit.merge.commands.markReviewed";
  static final String SHOW_PROPOSAL = "net.aftergit.merge.commands.showProposal";
  static final String AI_RESOLVE_CONFLICT = "net.aftergit.merge.commands.aiResolveConflict";
  static final String AI_RESOLVE_ALL = "net.aftergit.merge.commands.aiResolveAll";
  static final String SAVE_AND_MARK_RESOLVED = "net.aftergit.merge.commands.saveAndMarkResolved";
  static final String COMPARE_OURS_BASE = "net.aftergit.merge.commands.compareOursBase";
  static final String COMPARE_THEIRS_BASE = "net.aftergit.merge.commands.compareTheirsBase";
  static final String COMPARE_OURS_THEIRS = "net.aftergit.merge.commands.compareOursTheirs";
  static final String LAYOUT_ALL = "net.aftergit.merge.commands.layoutAll";
  static final String LAYOUT_LEFT_RESULT = "net.aftergit.merge.commands.layoutLeftResult";
  static final String LAYOUT_RESULT_RIGHT = "net.aftergit.merge.commands.layoutResultRight";
  static final String LAYOUT_RESULT_BELOW = "net.aftergit.merge.commands.layoutResultBelow";

  /** Every command of the resolver (preferences design §5), plus the two of the Team menu. */
  static final List<String> ALL = List.of(NEXT_CHANGE, PREV_CHANGE, NEXT_CONFLICT, PREV_CONFLICT, NEXT_ANY_CONFLICT,
      PREV_ANY_CONFLICT, NEXT_ERROR, PREV_ERROR, NEXT_AUTO, PREV_AUTO, TAKE_LEFT, TAKE_RIGHT, TAKE_BOTH, IGNORE, REVERT,
      MARK_REVIEWED, SHOW_PROPOSAL, AI_RESOLVE_CONFLICT, AI_RESOLVE_ALL, TOGGLE_STRUCTURE, SAVE_AND_MARK_RESOLVED,
      COMPARE_OURS_BASE, COMPARE_THEIRS_BASE, COMPARE_OURS_THEIRS, LAYOUT_ALL, LAYOUT_LEFT_RESULT, LAYOUT_RESULT_RIGHT,
      LAYOUT_RESULT_BELOW, "net.aftergit.merge.commands.resolve", "net.aftergit.merge.commands.resolveAll");

  static String layoutCommand(PaneLayout pl) {
    return switch (pl) {
      case ALL -> LAYOUT_ALL;
      case LEFT_RESULT -> LAYOUT_LEFT_RESULT;
      case RESULT_RIGHT -> LAYOUT_RESULT_RIGHT;
      case RESULT_BELOW -> LAYOUT_RESULT_BELOW;
    };
  }

  private final IHandlerService handlers;
  private final IContextService contexts;
  private final List<IHandlerActivation> activations = new ArrayList<>();
  private IContextActivation context;
  private IContextActivation resultContext;

  private SiteServices(IHandlerService handlers, IContextService contexts) {
    this.handlers = handlers;
    this.contexts = contexts;
  }

  static SiteServices activate(IServiceLocator locator, MergeViewer viewer) {
    SiteServices s = new SiteServices(locator.getService(IHandlerService.class),
        locator.getService(IContextService.class));
    if (s.contexts != null) {
      s.context = s.contexts.activateContext(SCOPE);
    }
    s.add(NEXT_CHANGE, run(viewer::nextChange));
    s.add(PREV_CHANGE, run(viewer::previousChange));
    s.add(NEXT_CONFLICT, run(viewer::nextConflict));
    s.add(PREV_CONFLICT, run(viewer::previousConflict));
    s.add(NEXT_ANY_CONFLICT, run(viewer::nextAnyConflict));
    s.add(PREV_ANY_CONFLICT, run(viewer::previousAnyConflict));
    s.add(NEXT_ERROR, run(viewer::nextError));
    s.add(PREV_ERROR, run(viewer::previousError));
    s.add(NEXT_AUTO, run(viewer::nextAutoResolved));
    s.add(PREV_AUTO, run(viewer::previousAutoResolved));
    s.add(TAKE_LEFT, run(viewer::takeLeft));
    s.add(TAKE_RIGHT, run(viewer::takeRight));
    s.add(TAKE_BOTH, run(viewer::takeLeftThenRight));
    s.add(IGNORE, run(viewer::ignore));
    s.add(REVERT, run(viewer::revert));
    s.add(MARK_REVIEWED, run(viewer::markReviewed));
    s.add(SHOW_PROPOSAL, run(viewer::showPendingProposal));
    s.add(AI_RESOLVE_CONFLICT, run(() -> viewer.ai().resolveConflictCommand()));
    s.add(AI_RESOLVE_ALL, run(() -> viewer.ai().resolveAllCommand()));
    s.add(SAVE_AND_MARK_RESOLVED, run(viewer::runSaveAndMarkResolved));
    s.add(COMPARE_OURS_BASE, run(() -> viewer.compareWithBase(CompareWithBase.Pair.OURS_BASE)));
    s.add(COMPARE_THEIRS_BASE, run(() -> viewer.compareWithBase(CompareWithBase.Pair.THEIRS_BASE)));
    s.add(COMPARE_OURS_THEIRS, run(() -> viewer.compareWithBase(CompareWithBase.Pair.OURS_THEIRS)));
    for (PaneLayout pl : PaneLayout.values()) {
      s.add(layoutCommand(pl), run(() -> viewer.setPaneLayout(pl)));
    }
    s.add(TOGGLE_STRUCTURE, run(viewer::toggleStructure));
    UndoHandler undoHandler = new UndoHandler(viewer::undo, viewer::canUndo);
    UndoHandler redoHandler = new UndoHandler(viewer::redo, viewer::canRedo);
    viewer.addUndoListener(() -> {
      undoHandler.update();
      redoHandler.update();
    });
    s.add(IWorkbenchCommandConstants.EDIT_UNDO, undoHandler);
    s.add(IWorkbenchCommandConstants.EDIT_REDO, redoHandler);
    return s;
  }

  private static final class UndoHandler extends AbstractHandler {
    private final Runnable action;
    private final BooleanSupplier enabled;

    UndoHandler(Runnable action, BooleanSupplier enabled) {
      this.action = action;
      this.enabled = enabled;
      update();
    }

    void update() {
      setBaseEnabled(enabled.getAsBoolean());
    }

    @Override
    public Object execute(ExecutionEvent event) {
      action.run();
      return null;
    }
  }

  static IHandler run(Runnable r) {
    return new AbstractHandler() {
      @Override
      public Object execute(ExecutionEvent event) {
        r.run();
        return null;
      }
    };
  }

  void add(String commandId, IHandler handler) {
    if (handlers != null) {
      activations.add(handlers.activateHandler(commandId, handler));
    }
  }

  /** Activates {@code contextId} while the result has the focus (e.g. the Java editor's key bindings). */
  void setResultContext(String contextId, boolean active) {
    if (contexts == null || contextId == null) {
      return;
    }
    if (active && resultContext == null) {
      resultContext = contexts.activateContext(contextId);
    } else if (!active && resultContext != null) {
      contexts.deactivateContext(resultContext);
      resultContext = null;
    }
  }

  void dispose() {
    if (handlers != null) {
      handlers.deactivateHandlers(activations);
    }
    activations.clear();
    if (contexts != null && resultContext != null) {
      contexts.deactivateContext(resultContext);
      resultContext = null;
    }
    if (contexts != null && context != null) {
      contexts.deactivateContext(context);
    }
  }
}
