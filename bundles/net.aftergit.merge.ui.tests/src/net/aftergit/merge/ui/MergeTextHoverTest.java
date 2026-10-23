package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Function;
import net.aftergit.merge.jdt.MergeTextHover;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.junit.jupiter.api.Test;

class MergeTextHoverTest {
  private static ITextHover hover(Function<IRegion, String> info) {
    return new ITextHover() {
      @Override
      public String getHoverInfo(ITextViewer viewer, IRegion region) {
        return info.apply(region);
      }

      @Override
      public IRegion getHoverRegion(ITextViewer viewer, int offset) {
        return new Region(offset, 1);
      }
    };
  }

  @Test
  void baseTextWinsAndTheJavaHoverFillsTheRest() {
    ITextHover merge = hover(r -> r.getOffset() == 0 ? "Base:\nb" : null);
    ITextHover java = hover(r -> "javadoc");
    MergeTextHover h = new MergeTextHover(merge, java);
    assertEquals("Base:\nb", h.getHoverInfo2(null, new Region(0, 1)));
    assertNotNull(h.getHoverControlCreator(), "a plain text control shows the base text verbatim");
    assertSame(MergeTextHover.PLAIN, h.getHoverControlCreator());
    assertEquals("javadoc", h.getHoverInfo2(null, new Region(5, 1)));
    assertNull(new MergeTextHover(merge, null).getHoverInfo2(null, new Region(5, 1)));
  }
}
