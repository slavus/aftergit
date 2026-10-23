package net.aftergit.merge.core.imports;

import java.util.List;

/**
 * The import declarations of one text: lines [startLine, endLine) hold them, with any blank or comment lines between
 * them. An empty section (startLine == endLine) marks the line where imports would go.
 */
public record ImportSection(int startLine, int endLine, List<ImportDecl> imports) {
  public ImportSection {
    if (startLine < 0 || endLine < startLine) {
      throw new IllegalArgumentException("Lines " + startLine + ".." + endLine);
    }
    imports = List.copyOf(imports);
  }
}
