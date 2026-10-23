package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.aftergit.merge.core.members.Member;
import net.aftergit.merge.core.members.MemberLocator;
import net.aftergit.merge.core.members.TypeBody;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * The members around a block of Java source (resolvers 6c design §1), parsed without a project and without bindings,
 * so it runs on any thread. Only member types count as bodies (not local or anonymous classes, not annotation types).
 * A member starts at its Javadoc or annotations, or at the comments that stand alone on the lines directly above it;
 * it ends on its last line, which may carry a line comment; an enum constant may be followed by its ',' or ';'.
 * Anything else on those lines finds nothing, as does a syntax error anywhere in the top-level type around the block
 * or a failing parser.
 */
public final class JavaMemberLocator implements MemberLocator {
  public static final JavaMemberLocator INSTANCE = new JavaMemberLocator();

  private JavaMemberLocator() {}

  @Override
  public Optional<TypeBody> locate(String text, int from, int to) {
    try {
      return find(text, from, to);
    } catch (VirtualMachineError e) {
      return Optional.empty(); // an overwhelmed parser: the block stays a conflict, never logged (a fatal dialog)
    } catch (RuntimeException e) {
      ILog.of(JavaMemberLocator.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "The Java members around a conflict block could not be located; the block stays a conflict", e));
      return Optional.empty();
    }
  }

  private static Optional<TypeBody> find(String text, int from, int to) {
    if (hasLoneCarriageReturn(text)) {
      return Optional.empty(); // the parser would end a line there, LineText does not
    }
    CompilationUnit cu = parse(text);
    Runnable hook = JdtTestHooks.memberLocatorHook();
    if (hook != null) {
      hook.run();
    }
    AbstractTypeDeclaration best = null;
    int bestStart = -1;
    int bestEnd = -1;
    for (AbstractTypeDeclaration t : types(cu)) {
      int open = openBrace(text, t);
      int close = t.getStartPosition() + t.getLength() - 1;
      if (open < 0 || close <= open || close >= text.length() || text.charAt(close) != '}') {
        continue;
      }
      int start = cu.getLineNumber(open); // the 1-based line of '{' is the 0-based index of the line after it
      int end = cu.getLineNumber(close) - 1;
      if (start <= from && to <= end && start > bestStart) {
        best = t;
        bestStart = start;
        bestEnd = end;
      }
    }
    if (best == null || hasError(cu, best) || hasError(cu, topLevel(best))) {
      return Optional.empty();
    }
    List<Member> members = new ArrayList<>();
    int initializers = 0;
    for (ASTNode n : members(best)) {
      List<String> keys = keys(n, initializers);
      if (n instanceof Initializer) {
        initializers++;
      }
      if (keys == null) {
        return Optional.empty();
      }
      int s = n.getStartPosition();
      int e = s + n.getLength();
      boolean constant = n instanceof EnumConstantDeclaration;
      if (!blankBefore(text, s) || !(constant ? separatorOnlyAfter(text, e) : blankOrLineCommentAfter(text, e))) {
        return Optional.empty();
      }
      members.add(new Member(keys, commentsAbove(cu, text, n), cu.getLineNumber(e - 1)));
    }
    return Optional.of(new TypeBody(bestStart, bestEnd, members));
  }

  static CompilationUnit parse(String text) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    Map<String, String> options = JavaCore.getOptions();
    JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
    parser.setCompilerOptions(options);
    parser.setSource(text.toCharArray());
    return (CompilationUnit) parser.createAST(null);
  }

  /** Every top-level and member type except annotation types, outer before inner. */
  private static List<AbstractTypeDeclaration> types(CompilationUnit cu) {
    List<AbstractTypeDeclaration> out = new ArrayList<>();
    for (Object o : cu.types()) {
      collect((AbstractTypeDeclaration) o, out);
    }
    return out;
  }

  private static void collect(AbstractTypeDeclaration t, List<AbstractTypeDeclaration> out) {
    if (!(t instanceof AnnotationTypeDeclaration)) {
      out.add(t);
    }
    for (Object o : t.bodyDeclarations()) {
      if (o instanceof AbstractTypeDeclaration inner) {
        collect(inner, out);
      }
    }
  }

  static List<ASTNode> members(AbstractTypeDeclaration t) {
    List<ASTNode> out = new ArrayList<>();
    if (t instanceof EnumDeclaration e) {
      for (Object o : e.enumConstants()) {
        out.add((ASTNode) o);
      }
    }
    for (Object o : t.bodyDeclarations()) {
      out.add((ASTNode) o);
    }
    return out;
  }

  /** The keys of a member (resolvers 6c design §1), or null for a kind of member this locator does not know. */
  static List<String> keys(ASTNode n, int initializer) {
    if (n instanceof MethodDeclaration m) {
      if (m.isCompactConstructor()) {
        return List.of("ctor:compact");
      }
      StringBuilder sb = new StringBuilder(m.isConstructor() ? "ctor:(" : "method:" + m.getName().getIdentifier() + "(");
      List<?> params = m.parameters();
      for (int i = 0; i < params.size(); i++) {
        SingleVariableDeclaration p = (SingleVariableDeclaration) params.get(i);
        sb.append(i > 0 ? "," : "").append(erasure(p.getType())).append("[]".repeat(p.getExtraDimensions()))
            .append(p.isVarargs() ? "[]" : "");
      }
      return List.of(sb.append(')').toString());
    }
    if (n instanceof FieldDeclaration f) {
      List<String> keys = new ArrayList<>();
      for (Object o : f.fragments()) {
        keys.add("field:" + ((VariableDeclarationFragment) o).getName().getIdentifier());
      }
      return keys;
    }
    if (n instanceof AbstractTypeDeclaration t) {
      return List.of("type:" + t.getName().getIdentifier());
    }
    if (n instanceof Initializer) {
      return List.of("init:" + initializer);
    }
    if (n instanceof EnumConstantDeclaration c) {
      return List.of(Member.CONSTANT_PREFIX + c.getName().getIdentifier());
    }
    return null;
  }

  /** A parameter type without its type arguments and annotations, as written (no bindings). */
  static String erasure(Type t) {
    if (t instanceof ParameterizedType p) {
      return erasure(p.getType());
    }
    if (t instanceof ArrayType a) {
      return erasure(a.getElementType()) + "[]".repeat(a.getDimensions());
    }
    if (t instanceof SimpleType s) {
      return s.getName().getFullyQualifiedName();
    }
    if (t instanceof QualifiedType q) {
      return erasure(q.getQualifier()) + "." + q.getName().getIdentifier();
    }
    if (t instanceof NameQualifiedType q) {
      return q.getQualifier().getFullyQualifiedName() + "." + q.getName().getIdentifier();
    }
    if (t instanceof PrimitiveType p) {
      return p.getPrimitiveTypeCode().toString();
    }
    return t.toString();
  }

  /** The offset of the '{' that opens the body of {@code t}, or -1. */
  private static int openBrace(String text, AbstractTypeDeclaration t) {
    int from = end(t.getName());
    List<Object> header = new ArrayList<>();
    if (t instanceof TypeDeclaration d) {
      header.addAll(d.typeParameters());
      if (d.getSuperclassType() != null) {
        header.add(d.getSuperclassType());
      }
      header.addAll(d.superInterfaceTypes());
      header.addAll(d.permittedTypes());
    } else if (t instanceof EnumDeclaration d) {
      header.addAll(d.superInterfaceTypes());
    } else if (t instanceof RecordDeclaration d) {
      header.addAll(d.typeParameters());
      header.addAll(d.recordComponents());
      header.addAll(d.superInterfaceTypes());
    }
    for (Object o : header) {
      from = Math.max(from, end((ASTNode) o));
    }
    IScanner scanner = ToolFactory.createScanner(false, false, false, JavaCore.latestSupportedJavaVersion());
    scanner.setSource(text.toCharArray());
    scanner.resetTo(from, t.getStartPosition() + t.getLength() - 1);
    try {
      for (int tok = scanner.getNextToken(); tok != ITerminalSymbols.TokenNameEOF; tok = scanner.getNextToken()) {
        if (tok == ITerminalSymbols.TokenNameLBRACE) {
          return scanner.getCurrentTokenStartPosition();
        }
      }
    } catch (InvalidInputException e) {
      return -1;
    }
    return -1;
  }

  private static int end(ASTNode n) {
    return n.getStartPosition() + n.getLength();
  }

  /** The top-level type that holds {@code t}, or {@code t} itself. */
  private static ASTNode topLevel(ASTNode t) {
    ASTNode top = t;
    while (top.getParent() != null && !(top.getParent() instanceof CompilationUnit)) {
      top = top.getParent();
    }
    return top;
  }

  /** A syntax error reported inside the type, or a type the parser had to recover. */
  private static boolean hasError(CompilationUnit cu, ASTNode type) {
    if ((type.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
      return true;
    }
    int from = type.getStartPosition();
    int to = from + type.getLength();
    for (IProblem p : cu.getProblems()) {
      if (p.isError() && p.getSourceStart() >= from && p.getSourceStart() < to) {
        return true;
      }
    }
    return false;
  }

  /** The first line of {@code n}, moved up over the comments that stand alone on the lines directly above it. */
  static int commentsAbove(CompilationUnit cu, String text, ASTNode n) {
    int line = cu.getLineNumber(n.getStartPosition()) - 1;
    List<?> comments = cu.getCommentList();
    for (int k = comments.size() - 1; k >= 0; k--) {
      Comment c = (Comment) comments.get(k);
      int end = c.getStartPosition() + c.getLength();
      if (end > n.getStartPosition()) {
        continue; // the member's own Javadoc, or a comment after it
      }
      if (cu.getLineNumber(end - 1) - 1 != line - 1 || !blankBefore(text, c.getStartPosition())) {
        break;
      }
      line = cu.getLineNumber(c.getStartPosition()) - 1;
    }
    return line;
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
  private static boolean blankOrLineCommentAfter(String text, int at) {
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

  /** Only whitespace and at most one ',' or ';' follow {@code at} on its line. */
  private static boolean separatorOnlyAfter(String text, int at) {
    int i = at;
    while (i < text.length() && text.charAt(i) != '\n' && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    if (i < text.length() && (text.charAt(i) == ',' || text.charAt(i) == ';')) {
      i++;
    }
    for (; i < text.length() && text.charAt(i) != '\n'; i++) {
      if (!Character.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  static boolean hasLoneCarriageReturn(String text) {
    for (int i = text.indexOf('\r'); i >= 0; i = text.indexOf('\r', i + 1)) {
      if (i + 1 == text.length() || text.charAt(i + 1) != '\n') {
        return true;
      }
    }
    return false;
  }
}
