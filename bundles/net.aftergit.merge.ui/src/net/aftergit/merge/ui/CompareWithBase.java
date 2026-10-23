package net.aftergit.merge.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.text.LineText;
import org.eclipse.osgi.util.NLS;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/** Standard Eclipse two-pane compare of the merge inputs (spec 6.2 "Compare with Base"). */
final class CompareWithBase {
  enum Pair { OURS_BASE, THEIRS_BASE, OURS_THEIRS }

  private CompareWithBase() {}

  static CompareEditorInput input(MergeSession s, Pair pair) {
    var m = s.model();
    String type = extension(s.fileName());
    Element ours = new Element(label(s, ThreeSide.LEFT, s.labels().left()), type, render(m.left()));
    Element base = new Element(label(s, ThreeSide.BASE, Messages.baseLabel), type, render(m.base()));
    Element theirs = new Element(label(s, ThreeSide.RIGHT, s.labels().right()), type, render(m.right()));
    Element left = pair == Pair.THEIRS_BASE ? theirs : ours;
    Element right = pair == Pair.OURS_THEIRS ? theirs : base;
    CompareConfiguration cc = new CompareConfiguration();
    cc.setLeftEditable(false);
    cc.setRightEditable(false);
    cc.setLeftLabel(left.getName());
    cc.setRightLabel(right.getName());
    CompareEditorInput input = new CompareEditorInput(cc) {
      @Override
      protected Object prepareInput(IProgressMonitor monitor) {
        return new DiffNode(left, right);
      }
    };
    input.setTitle(String.format(Messages.compareTitle, s.fileName(), left.getName(), right.getName()));
    return input;
  }

  /** A side built from the conflict markers must not pass for the stage its label names. */
  private static String label(MergeSession s, ThreeSide side, String label) {
    return s.sidesFromMarkers().contains(side) ? NLS.bind(Messages.compareFromMarkers, label) : label;
  }

  static void open(MergeSession s, Pair pair) {
    CompareUI.openCompareEditor(input(s, pair));
  }

  private static String render(LineText t) {
    return LineText.render(t.lines(), "\n", true);
  }

  private static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 || dot == fileName.length() - 1 ? ITypedElement.TEXT_TYPE : fileName.substring(dot + 1);
  }

  private record Element(String name, String type, String content)
      implements ITypedElement, IEncodedStreamContentAccessor {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public Image getImage() {
      return null;
    }

    @Override
    public String getType() {
      return type;
    }

    @Override
    public InputStream getContents() {
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getCharset() {
      return StandardCharsets.UTF_8.name();
    }
  }
}
