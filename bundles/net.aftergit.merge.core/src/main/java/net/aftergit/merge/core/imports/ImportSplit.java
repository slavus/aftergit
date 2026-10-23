package net.aftergit.merge.core.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;

/**
 * The 3-way merge with the import sections as a segment of their own (spec §5.3): no change crosses a section
 * boundary, and conflicting changes inside the section become one block over the whole section, for pass 1.
 */
public final class ImportSplit {
  private static final Logger LOG = Logger.getLogger(ImportSplit.class.getName());

  private ImportSplit() {}

  /** The sections of all three texts, or empty when one has none or the locator fails. */
  public static Optional<ImportSections> locate(ImportLocator locator, String left, String base, String right) {
    try {
      Optional<ImportSection> l = locator.locate(left);
      Optional<ImportSection> b = locator.locate(base);
      Optional<ImportSection> r = locator.locate(right);
      if (l.isEmpty() || b.isEmpty() || r.isEmpty()
          || !fits(l.get(), left) || !fits(b.get(), base) || !fits(r.get(), right)) {
        return Optional.empty();
      }
      return Optional.of(new ImportSections(l.get(), b.get(), r.get()));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Import sections could not be located; the file is merged without them", e);
      return Optional.empty();
    }
  }

  /** The section lies in the text and every declaration lies in the section. */
  private static boolean fits(ImportSection s, String text) {
    if (s.endLine() > LineText.of(text).size()) {
      return false;
    }
    for (ImportDecl d : s.imports()) {
      if (d.line() < s.startLine() || d.line() >= s.endLine()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Changes in base order: before the sections, inside them (one conflict or single changes), after them. When a
   * side has no imports, the blank lines around each section belong to the import segment on all sides: an empty
   * section has no fixed place among the blank lines that separate it, and where it is put would otherwise read
   * as a blank line deleted before the section on one side and after it on the others. The IMPORTS changes then
   * cover more lines than the {@code ImportSections} report.
   */
  public static List<MergeChange> merge(LineText left, LineText base, LineText right, ComparisonPolicy policy,
      ImportSections s) {
    boolean widen = isEmpty(s.left()) || isEmpty(s.base()) || isEmpty(s.right());
    int[] l = segment(left, s.left(), widen);
    int[] b = segment(base, s.base(), widen);
    int[] r = segment(right, s.right(), widen);
    List<MergeChange> out = new ArrayList<>();
    part(out, left, base, right, policy, 0, l[0], 0, b[0], 0, r[0], Region.BODY);
    int mark = out.size();
    part(out, left, base, right, policy, l[0], l[1], b[0], b[1], r[0], r[1], Region.IMPORTS);
    List<MergeChange> imports = out.subList(mark, out.size());
    if (imports.stream().anyMatch(c -> c.type().isConflict())) {
      imports.clear();
      out.add(new MergeChange(new MergeRange(l[0], l[1], b[0], b[1], r[0], r[1]),
          new ConflictType(ConflictType.Kind.CONFLICT, true, true), Region.IMPORTS));
    }
    part(out, left, base, right, policy, l[1], left.size(), b[1], base.size(), r[1], right.size(), Region.BODY);
    return out;
  }

  private static boolean isEmpty(ImportSection s) {
    return s.startLine() == s.endLine();
  }

  /** The lines [start, end) of the import segment: the section, with {@code widen} also the blank lines around it. */
  private static int[] segment(LineText text, ImportSection s, boolean widen) {
    int start = s.startLine();
    int end = s.endLine();
    if (widen) {
      while (start > 0 && text.line(start - 1).isBlank()) {
        start--;
      }
      while (end < text.size() && text.line(end).isBlank()) {
        end++;
      }
    }
    return new int[] {start, end};
  }

  private static void part(List<MergeChange> out, LineText left, LineText base, LineText right,
      ComparisonPolicy policy, int l0, int l1, int b0, int b1, int r0, int r1, Region region) {
    for (MergeChange c : LineMerger.merge(slice(left, l0, l1), slice(base, b0, b1), slice(right, r0, r1), policy)) {
      MergeRange m = c.range();
      out.add(new MergeChange(new MergeRange(m.start1() + l0, m.end1() + l0, m.start2() + b0, m.end2() + b0,
          m.start3() + r0, m.end3() + r0), c.type(), region));
    }
  }

  private static LineText slice(LineText text, int from, int to) {
    return LineText.ofLines(text.lines().subList(from, to));
  }

  /** True while an import block waits for the user: pass 1 could not resolve it (import rewrites wait too). */
  public static boolean importsUnresolved(MergeModel model) {
    for (MergeBlock b : model.blocks()) {
      if (b.region() == Region.IMPORTS && b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        return true;
      }
    }
    return false;
  }
}
