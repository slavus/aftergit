package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IWidgetTokenKeeper;
import org.eclipse.jface.text.IWidgetTokenKeeperExtension;
import org.eclipse.jface.text.IWidgetTokenOwner;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

class PopupsTest {
  /** Stands for a content assist or quick fix popup: it holds the widget token while open. */
  private static final class Popup implements IWidgetTokenKeeper, IWidgetTokenKeeperExtension {
    boolean closed;

    @Override
    public boolean requestWidgetToken(IWidgetTokenOwner owner) {
      return false;
    }

    @Override
    public boolean requestWidgetToken(IWidgetTokenOwner owner, int priority) {
      closed = true;
      return true;
    }

    @Override
    public boolean setFocus(IWidgetTokenOwner owner) {
      return false;
    }
  }

  /** Like content assist: it gives the token up only to a request above its own priority. */
  static final class AssistPopup implements IWidgetTokenKeeper, IWidgetTokenKeeperExtension {
    boolean closed;

    @Override
    public boolean requestWidgetToken(IWidgetTokenOwner owner) {
      return false;
    }

    @Override
    public boolean requestWidgetToken(IWidgetTokenOwner owner, int priority) {
      if (priority > 20) {
        closed = true;
        return true;
      }
      return false;
    }

    @Override
    public boolean setFocus(IWidgetTokenOwner owner) {
      return false;
    }
  }

  @Test
  void tellsWhetherAPopupIsOpenWithoutClosingIt() {
    Shell shell = new Shell(Display.getCurrent());
    try {
      SourceViewer v = new SourceViewer(shell, null, SWT.NONE);
      v.setDocument(new Document("x"));
      assertFalse(Popups.open(v));
      assertFalse(Popups.open(v), "asking left no token behind");
      AssistPopup popup = new AssistPopup();
      assertTrue(v.requestWidgetToken(popup, 20));
      assertTrue(Popups.open(v));
      assertFalse(popup.closed);
      v.releaseWidgetToken(popup);
      assertFalse(Popups.open(v));
    } finally {
      shell.dispose();
    }
  }

  @Test
  void closesWhateverHoldsTheWidgetToken() {
    Shell shell = new Shell(Display.getCurrent());
    try {
      SourceViewer v = new SourceViewer(shell, null, SWT.NONE);
      v.setDocument(new Document("x"));
      Popup popup = new Popup();
      assertTrue(v.requestWidgetToken(popup, 20));
      Popups.close(v);
      assertTrue(popup.closed);
      IWidgetTokenKeeper next = owner -> true;
      assertTrue(v.requestWidgetToken(next, 0), "the token is free again");
      v.releaseWidgetToken(next);
    } finally {
      shell.dispose();
    }
  }
}
