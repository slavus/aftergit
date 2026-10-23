package net.aftergit.merge.jdt;

import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextViewer;

/** The resolver's hover (base text of a conflict, review warnings) first; elsewhere the Java hover. */
public final class MergeTextHover implements ITextHover, ITextHoverExtension, ITextHoverExtension2 {
  /** Shows the resolver's text as it is: no HTML, so type arguments, '&&' and indentation survive. */
  public static final IInformationControlCreator PLAIN = parent -> new DefaultInformationControl(parent);

  private final ITextHover merge;
  private final ITextHover java;
  private ITextHover answered;

  /** {@code java} may be null. */
  public MergeTextHover(ITextHover merge, ITextHover java) {
    this.merge = merge;
    this.java = java;
  }

  @Override
  public IRegion getHoverRegion(ITextViewer viewer, int offset) {
    IRegion r = java == null ? null : java.getHoverRegion(viewer, offset);
    return r != null ? r : merge.getHoverRegion(viewer, offset);
  }

  @Override
  public Object getHoverInfo2(ITextViewer viewer, IRegion region) {
    String own = merge.getHoverInfo(viewer, region);
    if (own != null) {
      answered = merge;
      return own;
    }
    answered = java;
    if (java == null) {
      return null;
    }
    return java instanceof ITextHoverExtension2 e ? e.getHoverInfo2(viewer, region) : java.getHoverInfo(viewer, region);
  }

  @Override
  @SuppressWarnings("deprecation")
  public String getHoverInfo(ITextViewer viewer, IRegion region) {
    Object info = getHoverInfo2(viewer, region);
    return info == null ? null : info.toString();
  }

  @Override
  public IInformationControlCreator getHoverControlCreator() {
    if (answered == merge) {
      return PLAIN;
    }
    return java instanceof ITextHoverExtension e ? e.getHoverControlCreator() : null;
  }
}
