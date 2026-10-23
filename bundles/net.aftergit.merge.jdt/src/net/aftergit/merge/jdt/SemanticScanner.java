package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.GuardedPattern;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WildcardType;
import org.eclipse.jdt.core.dom.YieldStatement;

/**
 * Finds what the Java editor's semantic highlighting would colour in a text, with public JDT API only: an AST with
 * bindings resolved against the file's Java project. Without a project, or for names whose binding does not resolve,
 * only what the syntax tells is found (declarations); nothing is guessed.
 *
 * <p>Follows the behaviour of org.eclipse.jdt.ui 3.37 (SemanticHighlightings, SemanticHighlightingReconciler),
 * re-implemented, no code copied; re-check when JavaPanes.TESTED_JDT_UI is widened.
 */
final class SemanticScanner {
  /**
   * The highlightings of Java > Editor > Syntax Coloring in the order the editor tries them: of those enabled, the
   * first that applies to a name wins. The keys are those of the preferences "semanticHighlighting.KEY.*".
   */
  enum Kind {
    DEPRECATED_MEMBER("deprecatedMember"),
    AUTOBOXING("autoboxing"),
    STATIC_FINAL_FIELD("staticFinalField"),
    STATIC_FIELD("staticField"),
    INHERITED_FIELD("inheritedField"),
    FIELD("field"),
    METHOD_DECLARATION("methodDeclarationName"),
    STATIC_METHOD_INVOCATION("staticMethodInvocation"),
    ABSTRACT_METHOD_INVOCATION("abstractMethodInvocation"),
    ANNOTATION_ELEMENT_REFERENCE("annotationElementReference"),
    INHERITED_METHOD_INVOCATION("inheritedMethodInvocation"),
    PARAMETER_VARIABLE("parameterVariable"),
    LOCAL_VARIABLE_DECLARATION("localVariableDeclaration"),
    LOCAL_VARIABLE("localVariable"),
    TYPE_VARIABLE("typeParameter"),
    METHOD("method"),
    TYPE_ARGUMENT("typeArgument"),
    ABSTRACT_CLASS("abstractClass"),
    RECORD("record"),
    CLASS("class"),
    ENUM("enum"),
    ANNOTATION("annotation"),
    INTERFACE("interface"),
    NUMBER("number"),
    RESTRICTED_KEYWORDS("restrictedKeywords");

    final String key;

    Kind(String key) {
      this.key = key;
    }

    int bit() {
      return 1 << ordinal();
    }
  }

  /**
   * Ranges sorted by offset, none overlapping; {@code kinds[i]} has the bit of every kind that applies to range i, so
   * a change of the enabled set needs no new scan.
   */
  record Tokens(int[] offsets, int[] lengths, int[] kinds) {
    static final Tokens NONE = new Tokens(new int[0], new int[0], new int[0]);

    int size() {
      return offsets.length;
    }
  }

  private SemanticScanner() {}

  /** The tokens of {@code text} as the content of {@code file}; throws OperationCanceledException once cancelled. */
  static Tokens scan(IFile file, String text, IProgressMonitor monitor) {
    IJavaProject project = file == null ? null : JavaCore.create(file.getProject());
    boolean resolve = project != null && project.exists();
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setSource(text.toCharArray());
    parser.setStatementsRecovery(true);
    String compliance;
    if (resolve) {
      parser.setProject(project);
      parser.setUnitName(file.getFullPath().toString());
      parser.setResolveBindings(true);
      parser.setBindingsRecovery(true); // as the editor's AST
      compliance = project.getOption(JavaCore.COMPILER_COMPLIANCE, true);
    } else {
      Map<String, String> options = JavaCore.getOptions();
      JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
      parser.setCompilerOptions(options);
      compliance = JavaCore.latestSupportedJavaVersion();
    }
    CompilationUnit unit = (CompilationUnit) parser.createAST(monitor);
    if (monitor != null && monitor.isCanceled()) {
      throw new OperationCanceledException();
    }
    Collector c = new Collector(compliance, resolve, monitor);
    unit.accept(c);
    return c.tokens();
  }

  /** Collects the ranges as the editor's position collector does, kind by kind. */
  private static final class Collector extends ASTVisitor {
    private final String compliance;
    private final boolean resolved;
    private final IProgressMonitor monitor;
    private final List<long[]> found = new ArrayList<>();
    private int visited;

    Collector(String compliance, boolean resolved, IProgressMonitor monitor) {
      super(false); // no doc comments: the editor colours their names lexically
      this.compliance = compliance;
      this.resolved = resolved;
      this.monitor = monitor;
    }

    private void add(int offset, int length, int kinds) {
      if (offset > -1 && length > 0 && kinds != 0) {
        found.add(new long[] {offset, length, kinds});
      }
    }

    private void checkCancel() {
      if ((++visited & 0xff) == 0 && monitor != null && monitor.isCanceled()) {
        throw new OperationCanceledException();
      }
    }

    Tokens tokens() {
      found.sort((a, b) -> Long.compare(a[0], b[0]));
      List<long[]> kept = new ArrayList<>();
      long end = -1;
      for (long[] t : found) {
        if (t[0] >= end) { // never two styles on one character
          kept.add(t);
          end = t[0] + t[1];
        }
      }
      int n = kept.size();
      int[] offsets = new int[n];
      int[] lengths = new int[n];
      int[] kinds = new int[n];
      for (int i = 0; i < n; i++) {
        offsets[i] = (int) kept.get(i)[0];
        lengths[i] = (int) kept.get(i)[1];
        kinds[i] = (int) kept.get(i)[2];
      }
      return new Tokens(offsets, lengths, kinds);
    }

    @Override
    public boolean preVisit2(ASTNode node) {
      return (node.getFlags() & ASTNode.MALFORMED) == 0; // as the editor: nothing inside what did not parse
    }

    @Override
    public boolean visit(SimpleName node) {
      checkCancel();
      add(node.getStartPosition(), node.getLength(), kindsOf(node, compliance, resolved));
      return false;
    }

    @Override
    public boolean visit(NumberLiteral node) {
      add(node.getStartPosition(), node.getLength(), (autoboxed(node) ? Kind.AUTOBOXING.bit() : 0) | Kind.NUMBER.bit());
      return false;
    }

    @Override
    public boolean visit(BooleanLiteral node) {
      add(node.getStartPosition(), node.getLength(), autoboxed(node) ? Kind.AUTOBOXING.bit() : 0);
      return false;
    }

    @Override
    public boolean visit(CharacterLiteral node) {
      add(node.getStartPosition(), node.getLength(), autoboxed(node) ? Kind.AUTOBOXING.bit() : 0);
      return false;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
      IMethodBinding b = node.resolveConstructorBinding();
      if (b != null && b.isDeprecated()) {
        add(node.getStartPosition(), 4, Kind.DEPRECATED_MEMBER.bit()); // "this"
      }
      return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
      IMethodBinding b = node.resolveConstructorBinding();
      if (b != null && b.isDeprecated()) {
        add(node.getStartPosition(), 5, Kind.DEPRECATED_MEMBER.bit()); // "super"
      }
      return true;
    }

    @Override
    public boolean visit(SimpleType node) {
      if (node.getAST().apiLevel() >= AST.JLS10 && node.isVar()) {
        add(node.getStartPosition(), node.getLength(), Kind.RESTRICTED_KEYWORDS.bit());
        return false;
      }
      return true;
    }

    @Override
    public boolean visit(YieldStatement node) {
      if (!node.isImplicit()) {
        add(node.getStartPosition(), 5, Kind.RESTRICTED_KEYWORDS.bit());
      }
      return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
      add(node.getRestrictedIdentifierStartPosition(), 6, Kind.RESTRICTED_KEYWORDS.bit());
      return true;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
      if (!node.permittedTypes().isEmpty()) {
        add(node.getRestrictedIdentifierStartPosition(), 7, Kind.RESTRICTED_KEYWORDS.bit()); // "permits"
      }
      return true;
    }

    @Override
    public boolean visit(Modifier node) {
      if (node.isSealed() || node.isNonSealed()) {
        add(node.getStartPosition(), node.getLength(), Kind.RESTRICTED_KEYWORDS.bit());
      }
      return false;
    }

    @Override
    public boolean visit(GuardedPattern node) {
      add(node.getRestrictedIdentifierStartPosition(), 4, Kind.RESTRICTED_KEYWORDS.bit()); // "when"
      return true;
    }
  }

  /** Every kind that applies to {@code name}, as bits of {@link Kind}. */
  static int kindsOf(SimpleName name, String compliance, boolean resolved) {
    IBinding b = name.resolveBinding();
    if (b == null) {
      // with bindings, the editor colours an unresolved name only where the syntax alone decides
      return resolved ? methodDeclaration(name) | localVariableDeclaration(name) : syntaxKinds(name);
    }
    IBinding m = bindingWithConstructor(name, b);
    int k = 0;
    if (deprecated(m, name, compliance)) {
      k |= Kind.DEPRECATED_MEMBER.bit();
    }
    if (autoboxed(name)) {
      k |= Kind.AUTOBOXING.bit();
    }
    if (b instanceof IVariableBinding v && v.isField()) {
      int mod = v.getModifiers();
      if ((mod & (Modifier.STATIC | Modifier.FINAL)) == (Modifier.STATIC | Modifier.FINAL)) {
        k |= Kind.STATIC_FINAL_FIELD.bit();
      }
      if ((mod & Modifier.STATIC) != 0) {
        k |= Kind.STATIC_FIELD.bit();
      }
      if (!name.isDeclaration() && inherited(name, v.getDeclaringClass())) {
        k |= Kind.INHERITED_FIELD.bit();
      }
      k |= Kind.FIELD.bit();
    }
    k |= methodDeclaration(name);
    if (b instanceof IMethodBinding mb) {
      if (!name.isDeclaration()) {
        if ((mb.getModifiers() & Modifier.STATIC) != 0) {
          k |= Kind.STATIC_METHOD_INVOCATION.bit();
        }
        ITypeBinding declaring = mb.getDeclaringClass();
        if ((mb.getModifiers() & Modifier.ABSTRACT) != 0 && (declaring == null || !declaring.isAnnotation())) {
          k |= Kind.ABSTRACT_METHOD_INVOCATION.bit();
        }
        if (inherited(name, declaring)) {
          k |= Kind.INHERITED_METHOD_INVOCATION.bit();
        }
      }
      if (name.getParent() instanceof MemberValuePair) {
        k |= Kind.ANNOTATION_ELEMENT_REFERENCE.bit();
      }
    }
    if (b instanceof IVariableBinding v && !v.isField()) {
      ASTNode decl = ((CompilationUnit) name.getRoot()).findDeclaringNode(v);
      if (decl != null && decl.getLocationInParent() == MethodDeclaration.PARAMETERS_PROPERTY) {
        k |= Kind.PARAMETER_VARIABLE.bit();
      }
      if (decl instanceof VariableDeclaration) {
        k |= Kind.LOCAL_VARIABLE.bit();
      }
    }
    k |= localVariableDeclaration(name);
    ASTNode parent = name.getParent();
    int parentType = parent == null ? -1 : parent.getNodeType();
    if (b instanceof ITypeBinding t) {
      if ((parentType == ASTNode.SIMPLE_TYPE || parentType == ASTNode.TYPE_PARAMETER) && t.isTypeVariable()) {
        k |= Kind.TYPE_VARIABLE.bit();
      }
      boolean notImported = !inImport(name);
      boolean classPlace = isOneOf(parentType, ASTNode.SIMPLE_TYPE, ASTNode.THIS_EXPRESSION, ASTNode.QUALIFIED_TYPE,
          ASTNode.QUALIFIED_NAME, ASTNode.TYPE_DECLARATION, ASTNode.METHOD_INVOCATION) && notImported;
      if (classPlace && t.isClass() && (t.getModifiers() & Modifier.ABSTRACT) != 0) {
        k |= Kind.ABSTRACT_CLASS.bit();
      }
      if ((classPlace || parentType == ASTNode.RECORD_DECLARATION && notImported) && t.isRecord()) {
        k |= Kind.RECORD.bit();
      }
      if (classPlace && t.isClass()) {
        k |= Kind.CLASS.bit();
      }
      if (isOneOf(parentType, ASTNode.METHOD_INVOCATION, ASTNode.SIMPLE_TYPE, ASTNode.QUALIFIED_TYPE,
          ASTNode.QUALIFIED_NAME, ASTNode.ENUM_DECLARATION) && notImported && t.isEnum()) {
        k |= Kind.ENUM.bit();
      }
      if (isOneOf(parentType, ASTNode.SIMPLE_TYPE, ASTNode.QUALIFIED_TYPE, ASTNode.QUALIFIED_NAME,
          ASTNode.ANNOTATION_TYPE_DECLARATION, ASTNode.MARKER_ANNOTATION, ASTNode.NORMAL_ANNOTATION,
          ASTNode.SINGLE_MEMBER_ANNOTATION) && notImported && t.isAnnotation()) {
        k |= Kind.ANNOTATION.bit();
      }
      if (isOneOf(parentType, ASTNode.SIMPLE_TYPE, ASTNode.QUALIFIED_TYPE, ASTNode.QUALIFIED_NAME,
          ASTNode.TYPE_DECLARATION) && notImported && t.isInterface()) {
        k |= Kind.INTERFACE.bit();
      }
    }
    if (m != null && m.getKind() == IBinding.METHOD) {
      k |= Kind.METHOD.bit();
    }
    k |= typeArgument(name);
    return k;
  }

  /** The kinds the syntax alone tells, for a name without a binding: declarations only, never a reference. */
  static int syntaxKinds(SimpleName name) {
    int k = methodDeclaration(name);
    ASTNode parent = name.getParent();
    StructuralPropertyDescriptor at = name.getLocationInParent();
    BodyDeclaration declaration = null;
    if (at == VariableDeclarationFragment.NAME_PROPERTY && parent.getParent() instanceof FieldDeclaration f) {
      int mod = f.getModifiers();
      if (f.getParent() instanceof TypeDeclaration t && t.isInterface()
          || f.getParent() instanceof AnnotationTypeDeclaration) {
        mod |= Modifier.STATIC | Modifier.FINAL; // implicitly
      }
      if ((mod & (Modifier.STATIC | Modifier.FINAL)) == (Modifier.STATIC | Modifier.FINAL)) {
        k |= Kind.STATIC_FINAL_FIELD.bit();
      }
      if ((mod & Modifier.STATIC) != 0) {
        k |= Kind.STATIC_FIELD.bit();
      }
      k |= Kind.FIELD.bit();
      declaration = f;
    } else if (at == EnumConstantDeclaration.NAME_PROPERTY) {
      k |= Kind.STATIC_FINAL_FIELD.bit() | Kind.STATIC_FIELD.bit() | Kind.FIELD.bit();
      declaration = (BodyDeclaration) parent;
    } else if (at == SingleVariableDeclaration.NAME_PROPERTY
        && (parent.getLocationInParent() == MethodDeclaration.PARAMETERS_PROPERTY)) {
      k |= Kind.PARAMETER_VARIABLE.bit();
    } else if (at == TypeParameter.NAME_PROPERTY) {
      k |= Kind.TYPE_VARIABLE.bit();
    } else if (at == TypeDeclaration.NAME_PROPERTY) {
      TypeDeclaration t = (TypeDeclaration) parent;
      if (t.isInterface()) {
        k |= Kind.INTERFACE.bit();
      } else {
        if (Modifier.isAbstract(t.getModifiers())) {
          k |= Kind.ABSTRACT_CLASS.bit();
        }
        k |= Kind.CLASS.bit();
      }
      declaration = t;
    } else if (at == EnumDeclaration.NAME_PROPERTY) {
      k |= Kind.ENUM.bit();
      declaration = (BodyDeclaration) parent;
    } else if (at == AnnotationTypeDeclaration.NAME_PROPERTY) {
      k |= Kind.ANNOTATION.bit();
      declaration = (BodyDeclaration) parent;
    } else if (at == RecordDeclaration.NAME_PROPERTY) {
      k |= Kind.RECORD.bit();
      declaration = (BodyDeclaration) parent;
    } else if (at == MethodDeclaration.NAME_PROPERTY || at == AnnotationTypeMemberDeclaration.NAME_PROPERTY) {
      declaration = (BodyDeclaration) parent;
    }
    int local = localVariableDeclaration(name);
    k |= local;
    if (local != 0 && parent.getLocationInParent() != RecordDeclaration.RECORD_COMPONENTS_PROPERTY) {
      k |= Kind.LOCAL_VARIABLE.bit(); // a variable declared here is a local variable (or a parameter) of this text
    }
    if (declaration != null && markedDeprecated(declaration)) {
      k |= Kind.DEPRECATED_MEMBER.bit();
    }
    return k;
  }

  /** The declaration says it is deprecated: an annotation named Deprecated, or a Javadoc tag @deprecated. */
  private static boolean markedDeprecated(BodyDeclaration d) {
    for (Object o : d.modifiers()) {
      if (o instanceof org.eclipse.jdt.core.dom.Annotation a) {
        String n = a.getTypeName().getFullyQualifiedName();
        if (n.equals("Deprecated") || n.equals("java.lang.Deprecated")) {
          return true;
        }
      }
    }
    Javadoc doc = d.getJavadoc();
    if (doc != null) {
      for (Object o : doc.tags()) {
        if (o instanceof TagElement t && TagElement.TAG_DEPRECATED.equals(t.getTagName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static int methodDeclaration(SimpleName name) {
    StructuralPropertyDescriptor at = name.getLocationInParent();
    return at == MethodDeclaration.NAME_PROPERTY || at == AnnotationTypeMemberDeclaration.NAME_PROPERTY
        ? Kind.METHOD_DECLARATION.bit() : 0;
  }

  /** A local variable's (or a lambda's, catch clause's or resource's) declared name: not a field. */
  private static int localVariableDeclaration(SimpleName name) {
    StructuralPropertyDescriptor at = name.getLocationInParent();
    if (at == VariableDeclarationFragment.NAME_PROPERTY || at == SingleVariableDeclaration.NAME_PROPERTY) {
      ASTNode parent = name.getParent();
      if (parent instanceof VariableDeclaration) {
        ASTNode p = parent.getParent();
        return p == null || !(p instanceof FieldDeclaration) ? Kind.LOCAL_VARIABLE_DECLARATION.bit() : 0;
      }
    }
    return 0;
  }

  private static int typeArgument(SimpleName name) {
    ASTNode parent = name.getParent();
    int t = parent == null ? -1 : parent.getNodeType();
    if (t != ASTNode.SIMPLE_TYPE && t != ASTNode.QUALIFIED_TYPE) {
      return 0;
    }
    StructuralPropertyDescriptor at = parent.getLocationInParent();
    return at == ParameterizedType.TYPE_ARGUMENTS_PROPERTY || at == WildcardType.BOUND_PROPERTY
        || at == TypeParameter.TYPE_BOUNDS_PROPERTY ? Kind.TYPE_ARGUMENT.bit() : 0;
  }

  /** The binding the editor uses for deprecation and methods: a constructor's for the type of a {@code new}. */
  private static IBinding bindingWithConstructor(SimpleName name, IBinding binding) {
    ASTNode node = name;
    ASTNode parent = node.getParent();
    while (parent != null && typePath(node, parent)) {
      node = parent;
      parent = parent.getParent();
    }
    if (parent instanceof ClassInstanceCreation c && node.getLocationInParent() == ClassInstanceCreation.TYPE_PROPERTY) {
      IMethodBinding ctor = c.resolveConstructorBinding();
      return ctor != null ? ctor : binding;
    }
    return binding;
  }

  private static boolean typePath(ASTNode child, ASTNode parent) {
    StructuralPropertyDescriptor at = child.getLocationInParent();
    return parent instanceof SimpleType
        || parent instanceof ParameterizedType && at == ParameterizedType.TYPE_PROPERTY
        || parent instanceof QualifiedName && at == QualifiedName.NAME_PROPERTY
        || parent instanceof QualifiedType && at == QualifiedType.NAME_PROPERTY
        || parent instanceof NameQualifiedType && at == NameQualifiedType.NAME_PROPERTY;
  }

  private static boolean deprecated(IBinding binding, SimpleName name, String compliance) {
    if (binding == null) {
      return false;
    }
    if (binding.isDeprecated()) {
      for (IAnnotationBinding a : binding.getAnnotations()) {
        ITypeBinding type = a.getAnnotationType();
        if (type == null || !"java.lang.Deprecated".equals(type.getQualifiedName())) {
          continue;
        }
        for (IMemberValuePairBinding p : a.getAllMemberValuePairs()) {
          if ("since".equals(p.getName()) && p.getValue() instanceof String since) {
            try {
              if (Integer.parseInt(compliance) < Integer.parseInt(since)) {
                return false; // deprecated after the version the project compiles for
              }
            } catch (NumberFormatException e) {
              // "1.8" or a free text: deprecated as declared
            }
          }
        }
      }
      return true;
    }
    if (binding instanceof IMethodBinding m) {
      ITypeBinding declaring = m.getDeclaringClass();
      if (declaring == null) {
        return false;
      }
      if (declaring.isAnonymous() && m.isConstructor()) {
        ITypeBinding[] interfaces = declaring.getInterfaces();
        if (interfaces.length > 0) {
          return interfaces[0].isDeprecated();
        }
        ITypeBinding sup = declaring.getSuperclass();
        return sup != null && sup.isDeprecated();
      }
      return declaring.isDeprecated() && (m.isDefaultConstructor() || m.isCanonicalConstructor());
    }
    if (binding instanceof IVariableBinding v) {
      ITypeBinding declaring = v.getDeclaringClass();
      return declaring != null && declaring.isDeprecated() && !(name.getParent() instanceof VariableDeclaration);
    }
    return false;
  }

  /** Boxed or unboxed where it stands (also as the name of a qualified name or field access). */
  static boolean autoboxed(Expression e) {
    if (boxes(e)) {
      return true;
    }
    StructuralPropertyDescriptor at = e.getLocationInParent();
    if (at == QualifiedName.NAME_PROPERTY || at == FieldAccess.NAME_PROPERTY || at == SuperFieldAccess.NAME_PROPERTY) {
      return e.getParent() instanceof Expression p && boxes(p);
    }
    return false;
  }

  private static boolean boxes(Expression e) {
    return e.resolveBoxing() || e.resolveUnboxing();
  }

  /** {@code declaring} is a proper super type of the type around {@code node}. */
  private static boolean inherited(ASTNode node, ITypeBinding declaring) {
    ITypeBinding current = enclosingType(node);
    if (current == null || declaring == null || current == declaring) {
      return false;
    }
    return superType(declaring, current);
  }

  private static ITypeBinding enclosingType(ASTNode node) {
    for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
      if (n instanceof AbstractTypeDeclaration t) {
        return t.resolveBinding();
      }
      if (n instanceof AnonymousClassDeclaration a) {
        return a.resolveBinding();
      }
    }
    return null;
  }

  private static boolean superType(ITypeBinding possibleSuper, ITypeBinding type) {
    if (type == null || type.isArray() || type.isPrimitive()) {
      return false;
    }
    if (type == possibleSuper || type.isEqualTo(possibleSuper)) {
      return true;
    }
    if (superType(possibleSuper, type.getSuperclass())) {
      return true;
    }
    if (possibleSuper.isInterface()) {
      for (ITypeBinding i : type.getInterfaces()) {
        if (superType(possibleSuper, i)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The name is part of an import's qualified name. */
  private static boolean inImport(SimpleName name) {
    ASTNode n = name.getParent();
    while (n instanceof QualifiedName) {
      n = n.getParent();
    }
    return n instanceof ImportDeclaration;
  }

  private static boolean isOneOf(int type, int... types) {
    return Arrays.stream(types).anyMatch(t -> t == type);
  }
}
