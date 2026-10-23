package net.aftergit.merge.ui;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.part.Page;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

/**
 * The resolver's structure in the Outline view (structure-compare design §3): the same tree, compact, and "Show all
 * members" in the view menu. The Outline may ask before the editor has its viewer: the tree comes with the viewer.
 */
final class StructureOutlinePage extends Page implements IContentOutlinePage {
  private final MergeEditorInput input;
  private Composite root;
  private StructureTree tree;
  private Action showAll;
  private MergeViewer attached;
  private final Runnable sync = () -> {
    if (showAll != null && attached != null) {
      showAll.setChecked(attached.structure().showAll());
    }
  };

  StructureOutlinePage(MergeEditorInput input) {
    this.input = input;
  }

  @Override
  public void createControl(Composite parent) {
    root = new Composite(parent, SWT.NONE);
    root.setLayout(new FillLayout());
    input.pageCreated(this);
    attach();
  }

  /** Builds the tree once the editor has its viewer; called again by the editor input when the viewer comes. */
  void attach() {
    if (root == null || root.isDisposed() || tree != null) {
      return;
    }
    MergeViewer v = input.viewer();
    if (v == null || v.getControl().isDisposed()) {
      return;
    }
    attached = v;
    tree = new StructureTree(root, v, true);
    v.structure().addListener(sync);
    v.structure().setShowing(this, true);
    v.getControl().addDisposeListener(e -> attached = null);
    root.layout(true);
    sync.run();
  }

  @Override
  public void makeContributions(IMenuManager menu, IToolBarManager toolBar, IStatusLineManager status) {
    showAll = new Action(Messages.structureShowAll, IAction.AS_CHECK_BOX) {
      @Override
      public void run() {
        if (attached != null) {
          attached.structure().setShowAll(isChecked());
        } else {
          MergePreferences.setStructureShowAll(isChecked());
        }
      }
    };
    showAll.setChecked(MergePreferences.structureShowAll());
    menu.add(showAll);
  }

  @Override
  public Control getControl() {
    return root;
  }

  @Override
  public void setFocus() {
    if (tree != null) {
      tree.tree().setFocus();
    } else if (root != null) {
      root.setFocus();
    }
  }

  @Override
  public void dispose() {
    if (attached != null) {
      attached.structure().setShowing(this, false);
      attached.structure().removeListener(sync);
    }
    input.pageClosed(this);
    super.dispose();
  }

  StructureTree tree() { return tree; }
  Action showAllAction() { return showAll; }

  // the Outline's selection is not offered to other views
  @Override
  public void addSelectionChangedListener(ISelectionChangedListener listener) {}

  @Override
  public void removeSelectionChangedListener(ISelectionChangedListener listener) {}

  @Override
  public ISelection getSelection() {
    return StructuredSelection.EMPTY;
  }

  @Override
  public void setSelection(ISelection selection) {}
}
