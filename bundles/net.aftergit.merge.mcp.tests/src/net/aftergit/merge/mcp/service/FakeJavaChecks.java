package net.aftergit.merge.mcp.service;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.JavaChecks;
import org.eclipse.core.runtime.IProgressMonitor;

/** A compiler for tests: every line that contains {@code marker} has the error "bad <marker>". */
public final class FakeJavaChecks implements JavaChecks {
  private final String marker;

  public FakeJavaChecks(String marker) {
    this.marker = marker;
  }

  @Override
  public ImportLocator locator() {
    return null;
  }

  @Override
  public void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor) {}

  @Override
  public List<LineProblem> errors(String text, IProgressMonitor monitor) {
    List<LineProblem> out = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(marker)) {
        out.add(new LineProblem(i, "bad " + marker));
      }
    }
    return out;
  }
}
