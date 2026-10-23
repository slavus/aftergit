package net.aftergit.merge.core.imports;

import java.util.ArrayList;
import java.util.List;

/** The import sections of the three inputs of one merge. */
public record ImportSections(ImportSection left, ImportSection base, ImportSection right) {
  /** Imports a body change may need (spec §5.3: candidates from ours and theirs), ours first. */
  public List<ImportDecl> candidates() {
    List<ImportDecl> out = new ArrayList<>(left.imports());
    out.addAll(right.imports());
    return List.copyOf(out);
  }
}
