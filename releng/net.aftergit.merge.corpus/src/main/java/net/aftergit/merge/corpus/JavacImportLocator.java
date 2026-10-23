package net.aftergit.merge.corpus;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSection;

/**
 * The corpus stand-in for the resolver's import locator (net.aftergit.merge.jdt JavaImportLocator, spec §5.3): the
 * JDK parser, no project. The section runs from the first to the last import line. Approximations: a syntax error
 * before the end of the imports finds nothing (one after them does not matter, as for JDT); without imports the
 * section is after the package, else at the first type, moved up over a Javadoc comment directly above it.
 */
final class JavacImportLocator implements ImportLocator {
  static final JavacImportLocator INSTANCE = new JavacImportLocator();

  private static final Pattern MODULE_IMPORT = Pattern.compile("import\\s+module\\s+[\\p{L}_$]");

  private JavacImportLocator() {}

  @Override
  public Optional<ImportSection> locate(String text) {
    if (hasLoneCarriageReturn(text)) {
      return Optional.empty(); // the parser would end a line there, LineText does not
    }
    try {
      return find(text);
    } catch (RuntimeException | StackOverflowError | IOException e) {
      return Optional.empty();
    }
  }

  private static Optional<ImportSection> find(String text) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
      }
    };
    JavacTask task = (JavacTask) javac.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(source));
    CompilationUnitTree cu = task.parse().iterator().next();
    if (cu.getModule() != null) {
      return Optional.empty();
    }
    SourcePositions pos = Trees.instance(task).getSourcePositions();
    LineMap map = cu.getLineMap();
    List<? extends ImportTree> imports = cu.getImports();
    long parsedUpTo = imports.isEmpty() ? firstTypeStart(cu, pos)
        : pos.getEndPosition(cu, imports.get(imports.size() - 1));
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      if (d.getKind() == Diagnostic.Kind.ERROR && (parsedUpTo < 0 || d.getPosition() <= parsedUpTo)) {
        return Optional.empty();
      }
    }
    if (imports.isEmpty()) {
      int line = insertionLine(cu, pos, map, text);
      return line < 0 ? Optional.empty() : Optional.of(new ImportSection(line, line, List.of()));
    }
    int start = (int) pos.getStartPosition(cu, imports.get(0));
    int end = (int) parsedUpTo;
    if (start < 0 || end <= start || !blankBefore(text, start) || !blankOrCommentAfter(text, end)) {
      return Optional.empty();
    }
    List<ImportDecl> decls = new ArrayList<>();
    for (ImportTree d : imports) {
      int s = (int) pos.getStartPosition(cu, d);
      boolean module = MODULE_IMPORT.matcher(text).region(s, text.length()).lookingAt();
      String name = d.getQualifiedIdentifier().toString();
      boolean onDemand = !module && name.endsWith(".*");
      if (onDemand) {
        name = name.substring(0, name.length() - 2);
      }
      decls.add(new ImportDecl(name, d.isStatic(), onDemand, module, (int) map.getLineNumber(s) - 1));
    }
    return Optional.of(new ImportSection((int) map.getLineNumber(start) - 1, (int) map.getLineNumber(end - 1), decls));
  }

  private static long firstTypeStart(CompilationUnitTree cu, SourcePositions pos) {
    for (Tree t : cu.getTypeDecls()) {
      if (t instanceof ClassTree) {
        return pos.getStartPosition(cu, t);
      }
    }
    return -1;
  }

  /** Where imports would go: after the package declaration, else at the first type (its Javadoc); -1 for none. */
  private static int insertionLine(CompilationUnitTree cu, SourcePositions pos, LineMap map, String text) {
    if (cu.getPackage() != null) {
      int end = (int) pos.getEndPosition(cu, cu.getPackage());
      return end > 0 && blankOrCommentAfter(text, end) ? (int) map.getLineNumber(end - 1) : -1;
    }
    long first = firstTypeStart(cu, pos);
    if (first < 0) {
      return text.isEmpty() ? 0 : -1;
    }
    int start = javadocStart(text, (int) first);
    return blankBefore(text, start) ? (int) map.getLineNumber(start) - 1 : -1;
  }

  /** {@code at} moved back to a Javadoc comment that only whitespace separates from it, as JDT starts a type there. */
  private static int javadocStart(String text, int at) {
    int i = at - 1;
    while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
      i--;
    }
    if (i < 1 || text.charAt(i) != '/' || text.charAt(i - 1) != '*') {
      return at;
    }
    int open = text.lastIndexOf("/*", i - 2);
    return open >= 0 && text.startsWith("/**", open) ? open : at;
  }

  /** Only whitespace precedes {@code at} on its line. */
  private static boolean blankBefore(String text, int at) {
    for (int i = at - 1; i >= 0 && text.charAt(i) != '\n'; i--) {
      if (!Character.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Only whitespace or a line comment follows {@code at} on its line. */
  private static boolean blankOrCommentAfter(String text, int at) {
    for (int i = at; i < text.length() && text.charAt(i) != '\n'; i++) {
      char c = text.charAt(i);
      if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
        return true;
      }
      if (!Character.isWhitespace(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasLoneCarriageReturn(String text) {
    for (int i = text.indexOf('\r'); i >= 0; i = text.indexOf('\r', i + 1)) {
      if (i + 1 == text.length() || text.charAt(i + 1) != '\n') {
        return true;
      }
    }
    return false;
  }
}
