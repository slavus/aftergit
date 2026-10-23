package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSection;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;

/**
 * The import section of Java source (spec §5.3): the lines from the first to the last of CompilationUnit.imports().
 * Parsed without a project and without bindings, so it runs on any thread before the editor opens. A section that
 * does not cover whole lines is not reported; the merge is then not split.
 */
public final class JavaImportLocator implements ImportLocator {
  public static final JavaImportLocator INSTANCE = new JavaImportLocator();

  private JavaImportLocator() {}

  @Override
  public Optional<ImportSection> locate(String text) {
    if (hasLoneCarriageReturn(text)) {
      return Optional.empty(); // the parser would end a line there, LineText does not
    }
    CompilationUnit cu = parse(text);
    List<?> imports = cu.imports();
    if (imports.isEmpty()) {
      int line = insertionLine(cu, text);
      return line < 0 ? Optional.empty() : Optional.of(new ImportSection(line, line, List.of()));
    }
    ASTNode first = (ASTNode) imports.get(0);
    ASTNode last = (ASTNode) imports.get(imports.size() - 1);
    int start = first.getStartPosition();
    int end = last.getStartPosition() + last.getLength();
    if (!blankBefore(text, start) || !blankOrCommentAfter(text, end)) {
      return Optional.empty();
    }
    List<ImportDecl> decls = new ArrayList<>();
    for (Object o : imports) {
      ImportDeclaration d = (ImportDeclaration) o;
      boolean module = Modifier.isModule(d.getModifiers());
      // JDT reports module imports as on-demand; ImportDecl keys them without ".*", as ImportMerge does
      decls.add(new ImportDecl(d.getName().getFullyQualifiedName(), d.isStatic(), d.isOnDemand() && !module,
          module, cu.getLineNumber(d.getStartPosition()) - 1));
    }
    return Optional.of(new ImportSection(cu.getLineNumber(start) - 1, cu.getLineNumber(end - 1), decls));
  }

  private static CompilationUnit parse(String text) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    Map<String, String> options = JavaCore.getOptions();
    JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
    parser.setCompilerOptions(options);
    parser.setSource(text.toCharArray());
    parser.setIgnoreMethodBodies(true); // only the package, imports and type starts are read
    return (CompilationUnit) parser.createAST(null);
  }

  /** Where imports would go: after the package declaration, else at the first type (its Javadoc); -1 for none. */
  private static int insertionLine(CompilationUnit cu, String text) {
    PackageDeclaration p = cu.getPackage();
    if (p != null) {
      int end = p.getStartPosition() + p.getLength();
      return blankOrCommentAfter(text, end) ? cu.getLineNumber(end - 1) : -1;
    }
    if (cu.types().isEmpty()) {
      return text.isEmpty() ? 0 : -1;
    }
    int start = ((ASTNode) cu.types().get(0)).getStartPosition();
    return blankBefore(text, start) ? cu.getLineNumber(start) - 1 : -1;
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
