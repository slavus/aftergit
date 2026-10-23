package net.aftergit.merge.ui;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

/** The Structure column left of the panes (structure-compare design §2): "Structure", «, "Show all members", the tree. */
final class StructurePanel {
  private final Composite root;
  private final Button showAll;
  private final ToolItem hide;
  private final StructureTree tree;

  StructurePanel(Composite parent, MergeViewer viewer) {
    root = new Composite(parent, SWT.NONE);
    GridLayout gl = new GridLayout(2, false);
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    root.setLayout(gl);
    Label title = new Label(root, SWT.NONE);
    title.setText(Messages.structureTitle);
    title.setFont(JFaceResources.getBannerFont());
    title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    ToolBar bar = new ToolBar(root, SWT.FLAT);
    hide = new ToolItem(bar, SWT.PUSH);
    hide.setText("«");
    hide.setToolTipText(KeyHints.tooltip(Messages.structureHide, SiteServices.TOGGLE_STRUCTURE));
    hide.addListener(SWT.Selection, e -> viewer.setStructureShown(false));
    AccessibleNames.set(bar, child -> child == bar.indexOf(hide) ? Messages.structureHide : null); // not "«"
    showAll = new Button(root, SWT.CHECK | SWT.WRAP); // two lines in a narrow column rather than cut
    showAll.setText(Messages.structureShowAll);
    showAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    showAll.setSelection(viewer.structure().showAll());
    showAll.addListener(SWT.Selection, e -> viewer.structure().setShowAll(showAll.getSelection()));
    tree = new StructureTree(root, viewer, false);
    tree.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    Runnable sync = () -> {
      if (!showAll.isDisposed()) {
        showAll.setSelection(viewer.structure().showAll()); // the Outline may have changed it
      }
    };
    viewer.structure().addListener(sync);
    root.addDisposeListener(e -> viewer.structure().removeListener(sync));
  }

  Control control() { return root; }
  StructureTree tree() { return tree; }
  Button showAllButton() { return showAll; }
  ToolItem hideItem() { return hide; }
}
