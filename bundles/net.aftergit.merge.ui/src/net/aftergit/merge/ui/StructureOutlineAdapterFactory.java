package net.aftergit.merge.ui;

import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

/** The Outline page of a resolver's editor (structure-compare design §3, Ruling 13); nothing for any other editor. */
public final class StructureOutlineAdapterFactory implements IAdapterFactory {
  @Override
  public <T> T getAdapter(Object adaptable, Class<T> type) {
    if (type == IContentOutlinePage.class && adaptable instanceof IEditorPart part
        && part.getEditorInput() instanceof MergeEditorInput input) {
      return type.cast(input.outlinePage());
    }
    return null;
  }

  @Override
  public Class<?>[] getAdapterList() {
    return new Class<?>[] {IContentOutlinePage.class};
  }
}
