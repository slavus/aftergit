package net.aftergit.merge.ui;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IWidgetTokenKeeper;
import org.eclipse.jface.text.IWidgetTokenOwner;
import org.eclipse.jface.text.IWidgetTokenOwnerExtension;

/**
 * Closes content assist and quick fix popups of a viewer before the program changes its text (spec §13).
 * Such popups hold the viewer's widget token; asking for it with the highest priority makes them hide.
 */
final class Popups {
  private static final IWidgetTokenKeeper CLOSER = owner -> true;
  private static final IWidgetTokenKeeper PROBE = owner -> true;

  private Popups() {}

  /**
   * True while a popup that keeps its token holds the viewer (content assist, quick fix); asks with the lowest
   * priority, which such a popup refuses, so it stays open.
   */
  static boolean open(ITextViewer viewer) {
    if (viewer instanceof IWidgetTokenOwnerExtension owner) {
      if (owner.requestWidgetToken(PROBE, 0)) {
        ((IWidgetTokenOwner) viewer).releaseWidgetToken(PROBE);
        return false;
      }
      return true;
    }
    return false;
  }

  static void close(ITextViewer viewer) {
    if (viewer instanceof IWidgetTokenOwnerExtension owner && owner.requestWidgetToken(CLOSER, Integer.MAX_VALUE)) {
      ((IWidgetTokenOwner) viewer).releaseWidgetToken(CLOSER);
    }
  }
}
