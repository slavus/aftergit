package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.Test;

class CompareWithBaseTest {
  @Test
  void oursAgainstBase() throws Exception {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\n", "a\nL\n", "a\nR\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    MergeSession s = new MergeSession(m, new Labels("main (\"ours\")", "feature (\"theirs\")"), "\n", true, "Calc.java");
    var input = CompareWithBase.input(s, CompareWithBase.Pair.OURS_BASE);
    input.run(new NullProgressMonitor());
    DiffNode node = (DiffNode) input.getCompareResult();
    assertEquals("a\nL\n", content(node.getLeft()));
    assertEquals("a\nb\n", content(node.getRight()));
    assertEquals("java", node.getLeft().getType());
    assertEquals("main (\"ours\")", input.getCompareConfiguration().getLeftLabel(node));
    assertFalse(input.getCompareConfiguration().isLeftEditable());
    assertFalse(input.getCompareConfiguration().isRightEditable());
  }

  @Test
  void aSideBuiltFromTheMarkersSaysSo() throws Exception {
    MergeModel m = HybridInitializer.initialize(new MergeInput("a\nb\n", "a\nL\n", "a\nR\n", null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    MergeSession s = new MergeSession(m, new Labels("ours", "theirs"), "\n", true, "f.txt", null, null, null,
        net.aftergit.merge.git.MarkerConfig.DEFAULT, java.util.Set.of(net.aftergit.merge.core.merge.ThreeSide.LEFT));
    var input = CompareWithBase.input(s, CompareWithBase.Pair.OURS_BASE);
    input.run(new NullProgressMonitor());
    DiffNode node = (DiffNode) input.getCompareResult();
    assertEquals(org.eclipse.osgi.util.NLS.bind(Messages.compareFromMarkers, "ours"),
        input.getCompareConfiguration().getLeftLabel(node));
    assertEquals(Messages.baseLabel, input.getCompareConfiguration().getRightLabel(node));
  }

  private static String content(ITypedElement e) throws Exception {
    return new String(((IStreamContentAccessor) e).getContents().readAllBytes(), StandardCharsets.UTF_8);
  }
}
