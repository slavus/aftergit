package net.aftergit.merge.git;

import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * What Resolve All asks the language integration about one file (spec §5.5: pass 1 and the compile check): the Java
 * integration over net.aftergit.merge.jdt, or the file's language server (languages design, Part 2: no import pass,
 * {@link #locator()} is null). Implementations never throw: a failure reads as "nothing to do" ({@link #fixImports})
 * or "unknown" ({@link #errors} returns null).
 */
public interface JavaChecks {
  /** Locator of import sections for pass 1 (spec §5.3), or null. */
  ImportLocator locator();

  /** Adjusts the imports of the freshly auto-resolved result (spec §5.3); edits go through the model. */
  void fixImports(MergeModel model, ImportSections sections, IProgressMonitor monitor);

  /** Compile errors of {@code text} ('\n' lines) as the content of the file, by model line; null when unknown. */
  List<LineProblem> errors(String text, IProgressMonitor monitor);

  /**
   * Compile errors of the result, ours and theirs ('\n' lines), in this order, for the compile check of Resolve All;
   * null when unknown. The result is checked first, and ours and theirs only when it has an error (empty lists
   * otherwise: no block can then be marked). A language server integration compares the three as one server found
   * them (languages design, Part 2).
   */
  default List<List<LineProblem>> errors(String result, String left, String right, IProgressMonitor monitor) {
    List<LineProblem> r = errors(result, monitor);
    if (r == null || r.isEmpty()) {
      return r == null ? null : List.of(r, List.of(), List.of());
    }
    List<LineProblem> l = errors(left, monitor);
    List<LineProblem> t = l == null ? null : errors(right, monitor);
    return t == null ? null : List.of(r, l, t);
  }
}
