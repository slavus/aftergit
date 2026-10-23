package net.aftergit.merge.ui;

/** A conflicted Java class: line 6 is "return 0;" in base, ours returns a field, theirs adds an unknown name. */
final class JavaSources {
  static final String PATH = "src/p/A.java";
  static final String BASE = "package p;\n\npublic class A {\n  int count;\n\n  int value() {\n    return 0;\n  }\n}\n";
  static final String OURS = BASE.replace("return 0;", "return count;");
  static final String THEIRS = BASE.replace("return 0;", "return count + undefinedName;");

  private JavaSources() {}

  static String crlf(String s) {
    return s.replace("\n", "\r\n");
  }

  static MergeEditorInput open(ConflictRepos repos, String project, String base, String ours, String theirs,
      SaveDecision decision) throws Exception {
    repos.conflict(PATH, base, ours, theirs);
    return ConflictRepos.open(repos.javaProject(project, PATH), decision);
  }
}
