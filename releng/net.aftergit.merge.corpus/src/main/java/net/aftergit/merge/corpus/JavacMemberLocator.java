package net.aftergit.merge.corpus;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import net.aftergit.merge.core.members.Member;
import net.aftergit.merge.core.members.MemberLocator;
import net.aftergit.merge.core.members.TypeBody;
import net.aftergit.merge.core.text.LineText;

/**
 * The corpus stand-in for the resolver's member locator (net.aftergit.merge.jdt JavaMemberLocator, resolvers 6c design
 * §1): the JDK parser, no project, the same keys. Approximations: a syntax error anywhere in the file finds nothing;
 * records, and field declarations with several variables, find nothing; the comments above a member are found by
 * reading the lines.
 */
final class JavacMemberLocator implements MemberLocator {
  static final JavacMemberLocator INSTANCE = new JavacMemberLocator();

  private JavacMemberLocator() {}

  @Override
  public Optional<TypeBody> locate(String text, int from, int to) {
    try {
      return find(text, from, to);
    } catch (RuntimeException | StackOverflowError | IOException e) {
      return Optional.empty();
    }
  }

  private static Optional<TypeBody> find(String text, int from, int to) throws IOException {
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
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      if (d.getKind() == Diagnostic.Kind.ERROR) {
        return Optional.empty();
      }
    }
    SourcePositions pos = Trees.instance(task).getSourcePositions();
    LineMap map = cu.getLineMap();
    List<String> lines = LineText.chunkLines(text);
    ClassTree best = null;
    int bestStart = -1;
    int bestEnd = -1;
    for (ClassTree c : classes(cu)) {
      if (c.getKind() == Tree.Kind.RECORD || c.getKind() == Tree.Kind.ANNOTATION_TYPE) {
        continue;
      }
      int open = openBrace(text, c, pos, cu);
      long close = pos.getEndPosition(cu, c) - 1;
      if (open < 0 || close <= open || close >= text.length() || text.charAt((int) close) != '}') {
        continue;
      }
      int start = (int) map.getLineNumber(open);
      int end = (int) map.getLineNumber(close) - 1;
      if (start <= from && to <= end && start > bestStart) {
        best = c;
        bestStart = start;
        bestEnd = end;
      }
    }
    if (best == null) {
      return Optional.empty();
    }
    List<Member> members = new ArrayList<>();
    int initializers = 0;
    for (Tree t : best.getMembers()) {
      int s = (int) pos.getStartPosition(cu, t);
      int e = (int) pos.getEndPosition(cu, t);
      if (s < 0 || e <= s) {
        return Optional.empty();
      }
      if (t instanceof BlockTree) {
        s = staticBlockStart(text, s);
      }
      List<String> keys = keys(text, best, t, s, initializers);
      if (t instanceof BlockTree) {
        initializers++;
      }
      if (keys == null) {
        return Optional.empty();
      }
      boolean constant = keys.get(0).startsWith(Member.CONSTANT_PREFIX);
      if (!blankBefore(text, s) || !(constant ? separatorOnlyAfter(text, e) : blankOrLineCommentAfter(text, e))) {
        return Optional.empty();
      }
      int startLine = commentsAbove(lines, (int) map.getLineNumber(s) - 1);
      if (!members.isEmpty() && members.get(members.size() - 1).end() > startLine) {
        return Optional.empty(); // two members on one line
      }
      members.add(new Member(keys, startLine, (int) map.getLineNumber(e - 1)));
    }
    return Optional.of(new TypeBody(bestStart, bestEnd, members));
  }

  private static List<ClassTree> classes(CompilationUnitTree cu) {
    List<ClassTree> out = new ArrayList<>();
    for (Tree t : cu.getTypeDecls()) {
      if (t instanceof ClassTree c) {
        collect(c, out);
      }
    }
    return out;
  }

  private static void collect(ClassTree c, List<ClassTree> out) {
    out.add(c);
    for (Tree m : c.getMembers()) {
      if (m instanceof ClassTree inner) {
        collect(inner, out);
      }
    }
  }

  private static List<String> keys(String text, ClassTree owner, Tree t, int start, int initializer) {
    if (t instanceof MethodTree m) {
      String params = m.getParameters().stream().map(p -> erasure(p.getType())).collect(Collectors.joining(","));
      String name = m.getName().toString();
      return List.of(name.equals("<init>") ? "ctor:(" + params + ")" : "method:" + name + "(" + params + ")");
    }
    if (t instanceof VariableTree v) {
      String name = v.getName().toString();
      boolean constant = owner.getKind() == Tree.Kind.ENUM && text.startsWith(name, start);
      return List.of((constant ? Member.CONSTANT_PREFIX : "field:") + name);
    }
    if (t instanceof ClassTree c) {
      return List.of("type:" + c.getSimpleName());
    }
    if (t instanceof BlockTree) {
      return List.of("init:" + initializer);
    }
    return null;
  }

  static String erasure(Tree type) {
    if (type instanceof ParameterizedTypeTree p) {
      return erasure(p.getType());
    }
    if (type instanceof ArrayTypeTree a) {
      return erasure(a.getType()) + "[]";
    }
    if (type instanceof AnnotatedTypeTree a) {
      return erasure(a.getUnderlyingType());
    }
    return type.toString();
  }

  /** The '{' of the body: the first one after the header, outside comments and literals. */
  private static int openBrace(String text, ClassTree c, SourcePositions pos, CompilationUnitTree cu) {
    long from = pos.getStartPosition(cu, c);
    List<Tree> header = new ArrayList<>();
    header.add(c.getModifiers());
    header.addAll(c.getTypeParameters());
    if (c.getExtendsClause() != null) {
      header.add(c.getExtendsClause());
    }
    header.addAll(c.getImplementsClause());
    header.addAll(c.getPermitsClause());
    for (Tree h : header) {
      from = Math.max(from, pos.getEndPosition(cu, h));
    }
    for (int i = (int) from; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
        i = text.indexOf('\n', i);
        if (i < 0) {
          return -1;
        }
      } else if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
        i = text.indexOf("*/", i + 2);
        if (i < 0) {
          return -1;
        }
        i++;
      } else if (ch == '"' || ch == '\'') {
        int j = i + 1;
        while (j < text.length() && text.charAt(j) != ch) {
          j += text.charAt(j) == '\\' ? 2 : 1;
        }
        i = j;
      } else if (ch == '{') {
        return i;
      }
    }
    return -1;
  }

  /** An initializer's start: the "static" before its '{' when only that precedes it on the line. */
  private static int staticBlockStart(String text, int brace) {
    int lineStart = text.lastIndexOf('\n', brace - 1) + 1;
    String before = text.substring(lineStart, brace).trim();
    return before.equals("static") ? text.indexOf("static", lineStart) : brace;
  }

  /** {@code line} moved up over the comment lines directly above it. */
  static int commentsAbove(List<String> lines, int line) {
    int at = line;
    while (at > 0) {
      String prev = lines.get(at - 1).trim();
      if (prev.startsWith("//")) {
        at--;
        continue;
      }
      if (prev.endsWith("*/")) {
        int k = at - 1;
        while (k >= 0 && !lines.get(k).contains("/*")) {
          k--;
        }
        if (k < 0 || !lines.get(k).trim().startsWith("/*")) {
          return at;
        }
        at = k;
        continue;
      }
      return at;
    }
    return at;
  }

  private static boolean blankBefore(String text, int at) {
    for (int i = at - 1; i >= 0 && text.charAt(i) != '\n'; i--) {
      if (!Character.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

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
}
