package net.aftergit.merge.core.imports;

/**
 * One import declaration of a source text. {@code name} is dotted, without ".*"; {@code line} is its line in that
 * text (model lines, 0-based).
 */
public record ImportDecl(String name, boolean isStatic, boolean onDemand, boolean module, int line) {
  /** Identity for merging: static and normal imports of one name differ, so do "a.B" and "a.B.*". */
  public String key() {
    return (module ? "module " : isStatic ? "static " : "") + name + (onDemand ? ".*" : "");
  }

  /** The simple name the import brings in (last segment), or null for on-demand and module imports. */
  public String simpleName() {
    if (onDemand || module) {
      return null;
    }
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(dot + 1);
  }
}
