package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.core.structure.Visibility;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * The structure of a Java text (structure-compare design §1): the import section, then every type with its fields,
 * enum constants, methods, constructors, initializers and member types, nested as in the source. Parsed without a
 * project and without bindings, so it runs on any thread. Unlike the member locator it keeps what the parser recovered
 * from a syntax error: a half-typed member still shows the others. Keys are the member locator's (resolvers 6c §1).
 */
public final class JavaStructureLocator implements StructureLocator {
  public static final JavaStructureLocator INSTANCE = new JavaStructureLocator();

  private JavaStructureLocator() {}

  @Override
  public List<StructureItem> locate(String text) {
    if (JavaMemberLocator.hasLoneCarriageReturn(text)) {
      return List.of(); // the parser would end a line there, LineText does not
    }
    try {
      CompilationUnit cu = JavaMemberLocator.parse(text);
      List<StructureItem> out = new ArrayList<>();
      List<?> imports = cu.imports();
      if (!imports.isEmpty()) {
        ASTNode first = (ASTNode) imports.get(0);
        ASTNode last = (ASTNode) imports.get(imports.size() - 1);
        int start = Math.max(0, cu.getLineNumber(first.getStartPosition()) - 1); // a recovered node may lie nowhere
        out.add(new StructureItem("imports", NodeKind.IMPORTS, "", Visibility.PACKAGE, start, end(cu, last, start),
            List.of()));
      }
      for (Object o : cu.types()) {
        out.add(type(cu, text, (AbstractTypeDeclaration) o, false));
      }
      return out;
    } catch (VirtualMachineError e) {
      return List.of(); // an overwhelmed parser: no structure, never logged (a fatal dialog)
    }
  }

  /** {@code implicitPublic}: a member of an interface or an annotation type, public without a modifier. */
  private static StructureItem type(CompilationUnit cu, String text, AbstractTypeDeclaration t,
      boolean implicitPublic) {
    List<StructureItem> children = new ArrayList<>();
    int initializers = 0;
    boolean membersPublic = t instanceof AnnotationTypeDeclaration || t instanceof TypeDeclaration d && d.isInterface();
    for (ASTNode n : JavaMemberLocator.members(t)) {
      if (n instanceof AbstractTypeDeclaration inner) {
        children.add(type(cu, text, inner, membersPublic));
        continue;
      }
      String key = key(n, initializers);
      if (n instanceof Initializer) {
        initializers++;
      }
      if (key == null) {
        continue;
      }
      int start = Math.max(0, JavaMemberLocator.commentsAbove(cu, text, n));
      children.add(new StructureItem(key, kind(n), label(n), visibility(n, membersPublic), start, end(cu, n, start),
          List.of()));
    }
    String name = t.getName().getIdentifier();
    int start = Math.max(0, JavaMemberLocator.commentsAbove(cu, text, t));
    return new StructureItem("type:" + name, kind(t), name, visibility(t, implicitPublic), start, end(cu, t, start),
        children);
  }

  /** The 0-based line after the last line of {@code n}; at least the line after {@code start}. */
  private static int end(CompilationUnit cu, ASTNode n, int start) {
    int line = cu.getLineNumber(n.getStartPosition() + Math.max(1, n.getLength()) - 1);
    return Math.max(start + 1, line);
  }

  static String key(ASTNode n, int initializer) {
    if (n instanceof AnnotationTypeMemberDeclaration m) {
      return "method:" + m.getName().getIdentifier() + "()";
    }
    List<String> keys = JavaMemberLocator.keys(n, initializer);
    return keys == null ? null : String.join(",", keys);
  }

  private static NodeKind kind(ASTNode n) {
    if (n instanceof TypeDeclaration d) {
      return d.isInterface() ? NodeKind.INTERFACE : NodeKind.CLASS;
    }
    if (n instanceof EnumDeclaration) {
      return NodeKind.ENUM;
    }
    if (n instanceof RecordDeclaration) {
      return NodeKind.RECORD;
    }
    if (n instanceof AnnotationTypeDeclaration) {
      return NodeKind.ANNOTATION;
    }
    if (n instanceof MethodDeclaration m) {
      return m.isConstructor() || m.isCompactConstructor() ? NodeKind.CONSTRUCTOR : NodeKind.METHOD;
    }
    if (n instanceof AnnotationTypeMemberDeclaration) {
      return NodeKind.METHOD;
    }
    if (n instanceof FieldDeclaration) {
      return NodeKind.FIELD;
    }
    if (n instanceof EnumConstantDeclaration) {
      return NodeKind.CONSTANT;
    }
    return NodeKind.INITIALIZER;
  }

  /** What the tree shows (Ruling 19): simple parameter types, no type arguments. */
  static String label(ASTNode n) {
    if (n instanceof MethodDeclaration m) {
      if (m.isCompactConstructor()) {
        return m.getName().getIdentifier();
      }
      StringBuilder sb = new StringBuilder(m.getName().getIdentifier()).append('(');
      List<?> params = m.parameters();
      for (int i = 0; i < params.size(); i++) {
        sb.append(i > 0 ? ", " : "").append(param((SingleVariableDeclaration) params.get(i)));
      }
      return sb.append(')').toString();
    }
    if (n instanceof AnnotationTypeMemberDeclaration m) {
      return m.getName().getIdentifier() + "()";
    }
    if (n instanceof FieldDeclaration f) {
      List<String> names = new ArrayList<>();
      for (Object o : f.fragments()) {
        names.add(((VariableDeclarationFragment) o).getName().getIdentifier());
      }
      return String.join(", ", names);
    }
    if (n instanceof EnumConstantDeclaration c) {
      return c.getName().getIdentifier();
    }
    if (n instanceof Initializer i) {
      return Modifier.isStatic(i.getModifiers()) ? "static {...}" : "{...}";
    }
    return "";
  }

  static String param(SingleVariableDeclaration p) {
    String t = JavaMemberLocator.erasure(p.getType());
    int bracket = t.indexOf('[');
    String simple = t.substring(t.lastIndexOf('.', bracket < 0 ? t.length() : bracket) + 1);
    return simple + "[]".repeat(p.getExtraDimensions()) + (p.isVarargs() ? "..." : "");
  }

  private static Visibility visibility(ASTNode n, boolean implicitPublic) {
    int m = n instanceof BodyDeclaration b ? b.getModifiers() : 0;
    return Modifier.isPublic(m) ? Visibility.PUBLIC : Modifier.isProtected(m) ? Visibility.PROTECTED
        : Modifier.isPrivate(m) ? Visibility.PRIVATE : implicitPublic ? Visibility.PUBLIC : Visibility.PACKAGE;
  }
}
