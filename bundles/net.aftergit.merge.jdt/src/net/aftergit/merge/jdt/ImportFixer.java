package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.aftergit.merge.core.imports.ImportDecl;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.imports.ImportSplit;
import net.aftergit.merge.core.model.LineEdits;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberRef;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodRef;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.ui.CodeStyleConfiguration;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

/**
 * Keeps the imports of the merge result right after a change of its body (spec §5.3): adds the import a new name
 * needs when ours and theirs offer exactly one for it, and removes the imports the change left unused (an import
 * that was unused already stays). New imports go where the project's Organize Imports settings put them. Works on
 * a scratch working copy of the file; the editor's working copy and the shared document are never touched, the
 * new text reaches the result through the model.
 *
 * <p>Whether an import is used is decided by a scan of the syntax tree, not by the compiler's unused-import warning,
 * which JDT gives only for a text without errors; a merge result often has some. On-demand imports are never removed.
 *
 * <p>Parses the whole file (with bindings) on the calling thread and takes the monitor's cancellation where JDT checks
 * it (the working copy, name resolution, the import rewrite) and between its own stages (before the second parse,
 * before the rewrite and before applying it), so a stage that has begun runs to its end. A caller on the UI thread must bound it: the resolver
 * passes a monitor that cancels after a time budget and skips very large results. Names that need an import are
 * looked for only on the lines the change wrote, so a name that becomes unresolved elsewhere (an inherited member type
 * after a change of {@code extends}) gets no import, and neither does a name in an unresolved conflict or in an
 * earlier edit. A file outside a Java project, or not on its class path, is left alone.
 */
public final class ImportFixer {
  private static final Set<Integer> UNRESOLVED = Set.of(IProblem.UndefinedType, IProblem.UndefinedName,
      IProblem.UnresolvedVariable, IProblem.UndefinedMethod);
  private static final Pattern IDENTIFIER = Pattern.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");
  private static final Pattern IMPORT_LINE = Pattern.compile(
      "(?m)^\\s*import\\s+(?:static\\s+)?([\\p{javaJavaIdentifierPart}.*\\s]+?)\\s*;");

  private ImportFixer() {}

  /** After a body change of {@code model} (Take, revert, auto-resolution); true when the model changed. */
  public static boolean fixAfterChange(IFile file, MergeModel model, String before, ImportSections sections,
      IProgressMonitor monitor) {
    return fixAfterChange(file, model, before, sections, monitor, e -> logFailure(file, e));
  }

  /**
   * Like {@link #fixAfterChange(IFile, MergeModel, String, ImportSections, IProgressMonitor)}; a failure of the fix
   * goes to {@code failures} instead of the log (an Error is thrown).
   */
  public static boolean fixAfterChange(IFile file, MergeModel model, String before, ImportSections sections,
      IProgressMonitor monitor, Consumer<Exception> failures) {
    if (sections == null || ImportSplit.importsUnresolved(model)) {
      return false; // no sections to take candidates from, or the user has not decided the imports yet
    }
    String after = model.document().chunk(0, model.document().lineCount());
    Optional<String> fixed = fix(file, before, after, sections.candidates(), monitor, failures);
    fixed.ifPresent(model::applyDerivedText);
    return fixed.isPresent();
  }

  /** At open, after auto-resolution: compares with the result before it (auto-resolved blocks at base text). */
  public static boolean fixAtOpen(IFile file, MergeModel model, ImportSections sections, IProgressMonitor monitor) {
    return fixAfterChange(file, model, model.textBeforeAutoResolution(), sections, monitor);
  }

  /** The text {@code after} with its imports fixed (model lines), or empty when nothing changes or the fix failed. */
  public static Optional<String> fix(IFile file, String before, String after, List<ImportDecl> candidates,
      IProgressMonitor monitor) {
    return fix(file, before, after, candidates, monitor, e -> logFailure(file, e));
  }

  /** Like {@link #fix(IFile, String, String, List, IProgressMonitor)}; a failure goes to {@code failures}. */
  public static Optional<String> fix(IFile file, String before, String after, List<ImportDecl> candidates,
      IProgressMonitor monitor, Consumer<Exception> failures) {
    if (before.equals(after) || !mayMatter(before, after, candidates)) {
      return Optional.empty();
    }
    ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
    if (unit == null || !onClasspath(unit, file)) {
      return Optional.empty(); // no Java project to resolve names in: nothing to add, nothing safe to remove
    }
    Scratch now = null;
    try {
      now = Scratch.open(unit, after, monitor);
      if (unterminated(now.ast())) {
        return Optional.empty(); // the rest of the file is one token: neither its names nor its problems mean anything
      }
      Map<String, Need> add = missing(now.ast(), after, changedLines(before, after), candidates,
          unit.getJavaProject());
      List<ImportDeclaration> remove = new ArrayList<>();
      List<ImportDeclaration> unusedNow = syntaxError(now.ast()) ? List.of() : unused(now.ast());
      if (!unusedNow.isEmpty()) {
        checkCanceled(monitor); // the plain parse below does not look at the monitor
        Set<String> usedBefore = usedNames(parse(unit, before, monitor));
        for (ImportDeclaration d : unusedNow) {
          if (usedBefore.contains(simpleName(d))) {
            remove.add(d); // the change left it unused; an import unused before stays
          }
        }
      }
      if (add.isEmpty() && remove.isEmpty()) {
        return Optional.empty();
      }
      ImportRewrite rewrite = CodeStyleConfiguration.createImportRewrite(now.ast(), true);
      for (Need n : add.values()) {
        String name = n.decl().name();
        if (n.decl().isStatic()) {
          int dot = name.lastIndexOf('.');
          rewrite.addStaticImport(name.substring(0, dot), name.substring(dot + 1), n.field());
        } else {
          rewrite.addImport(name);
        }
      }
      for (ImportDeclaration d : remove) {
        String name = d.getName().getFullyQualifiedName();
        if (d.isStatic()) {
          rewrite.removeStaticImport(name);
        } else {
          rewrite.removeImport(name);
        }
      }
      checkCanceled(monitor);
      TextEdit edit = rewrite.rewriteImports(monitor);
      checkCanceled(monitor); // applying the edit does not look at the monitor either
      IDocument doc = new Document(after);
      edit.apply(doc);
      String fixed = doc.get().replace("\r\n", "\n").replace('\r', '\n'); // model lines, whatever the delimiter
      return fixed.equals(after) ? Optional.empty() : Optional.of(fixed);
    } catch (OperationCanceledException e) {
      return Optional.empty(); // cancelled: the action stays, the imports as they are
    } catch (CoreException | BadLocationException | RuntimeException e) { // MalformedTreeException is a RuntimeException
      failures.accept(e);
      return Optional.empty();
    } finally {
      close(now, file);
    }
  }

  /** The file is a source of an existing Java project, so a working copy of it can be parsed with bindings. */
  private static boolean onClasspath(ICompilationUnit unit, IFile file) {
    IJavaProject project = unit.getJavaProject();
    return project != null && project.exists() && project.isOnClasspath(file);
  }

  /** Stops the fix where JDT would not: between the stages that run without looking at the monitor. */
  private static void checkCanceled(IProgressMonitor monitor) {
    if (monitor != null && monitor.isCanceled()) {
      throw new OperationCanceledException();
    }
  }

  private static void logFailure(IFile file, Exception e) {
    ILog.of(ImportFixer.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
        "The imports of the merge result of " + file.getFullPath() + " were left as they are", e));
  }

  /** Cheap test before parsing: does the changed text mention a name an import could be about? */
  public static boolean mayMatter(String before, String after, List<ImportDecl> candidates) {
    int prefix = 0;
    int n = Math.min(before.length(), after.length());
    while (prefix < n && before.charAt(prefix) == after.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < n - prefix
        && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
      suffix++;
    }
    // widen to whole identifiers: "int a;" and "List a;" share the suffix "t a;", which cuts into both names
    while (prefix > 0 && Character.isJavaIdentifierPart(before.charAt(prefix - 1))) {
      prefix--;
    }
    while (suffix > 0 && Character.isJavaIdentifierPart(before.charAt(before.length() - suffix))) {
      suffix--;
    }
    Set<String> words = identifiers(before.substring(prefix, before.length() - suffix));
    words.addAll(identifiers(after.substring(prefix, after.length() - suffix)));
    Set<String> names = new HashSet<>();
    for (ImportDecl d : candidates) {
      if (d.simpleName() != null) {
        names.add(d.simpleName());
      }
    }
    Matcher m = IMPORT_LINE.matcher(after);
    while (m.find()) {
      String name = m.group(1).replaceAll("\\s", "");
      if (name.endsWith(".*")) {
        return true; // an on-demand import could be about any name
      }
      names.add(name.substring(name.lastIndexOf('.') + 1));
    }
    names.retainAll(words);
    return !names.isEmpty();
  }

  private static Set<String> identifiers(String text) {
    Set<String> out = new HashSet<>();
    Matcher m = IDENTIFIER.matcher(text);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  /** An import to add; {@code field}: a static import of a field (not of a method). */
  private record Need(ImportDecl decl, boolean field) {}

  /**
   * The imports the unresolved names of {@code ast} need. A name can only take a static import of a field or a member
   * type, a method call only one of a method: the declaration does not say which, so a static candidate for a name is
   * looked up in {@code project}.
   */
  private static Map<String, Need> missing(CompilationUnit ast, String text, boolean[] changed,
      List<ImportDecl> candidates, IJavaProject project) {
    Set<String> imported = new HashSet<>();
    for (Object o : ast.imports()) {
      ImportDeclaration d = (ImportDeclaration) o;
      if (!d.isOnDemand()) {
        String name = d.getName().getFullyQualifiedName();
        imported.add(name.substring(name.lastIndexOf('.') + 1));
      }
    }
    Map<String, Need> out = new LinkedHashMap<>();
    int[] newlines = newlines(text);
    for (IProblem p : ast.getProblems()) {
      if (!p.isError() || !UNRESOLVED.contains(p.getID())) {
        continue;
      }
      int line = modelLine(newlines, p.getSourceStart()); // not getLineNumber: it also ends a line at a lone '\r'
      if (line < 0 || line >= changed.length || !changed[line]) {
        continue; // a name outside the change (an unresolved conflict, an earlier edit) is not this change's business
      }
      String name = identifierAt(text, p.getSourceStart(), p.getSourceEnd());
      if (name == null || imported.contains(name) || qualified(text, p.getSourceStart())) {
        continue;
      }
      boolean method = p.getID() == IProblem.UndefinedMethod;
      boolean type = p.getID() == IProblem.UndefinedType;
      Map<String, ImportDecl> matching = new LinkedHashMap<>();
      for (ImportDecl d : candidates) {
        boolean kind = method ? d.isStatic() : !type || !d.isStatic();
        if (kind && name.equals(d.simpleName())) {
          matching.putIfAbsent(d.key(), d);
        }
      }
      if (matching.size() == 1) {
        ImportDecl d = matching.values().iterator().next();
        if (!method && d.isStatic() && !isFieldOrType(project, d.name())) {
          continue; // a static import of a method does not resolve a name
        }
        out.putIfAbsent(d.key(), new Need(d, !method));
      }
    }
    return out;
  }

  /** {@code name} ("a.b.C.member") names a field or a member type of a type on {@code project}'s class path. */
  private static boolean isFieldOrType(IJavaProject project, String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0) {
      return false;
    }
    try {
      IType owner = project.findType(name.substring(0, dot));
      String member = name.substring(dot + 1);
      return owner != null && (owner.getField(member).exists() || owner.getType(member).exists());
    } catch (JavaModelException e) {
      return false; // cannot tell: no import rather than a wrong one
    }
  }

  /** The offsets of the '\n's of {@code text}, ascending. */
  private static int[] newlines(String text) {
    int[] out = new int[16];
    int n = 0;
    for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
      if (n == out.length) {
        out = Arrays.copyOf(out, n * 2);
      }
      out[n++] = i;
    }
    return Arrays.copyOf(out, n);
  }

  /** The model line ('\n' ends a line, nothing else) of {@code offset}, given the '\n' offsets; -1 for none. */
  private static int modelLine(int[] newlines, int offset) {
    if (offset < 0) {
      return -1;
    }
    int i = Arrays.binarySearch(newlines, offset);
    return i >= 0 ? i : -i - 1; // a '\n' at the offset still belongs to its line
  }

  /** Per line of {@code after} (model lines): true when the change from {@code before} wrote that line. */
  private static boolean[] changedLines(String before, String after) {
    List<String> a = LineText.chunkLines(after);
    boolean[] changed = new boolean[a.size() + 1]; // and the empty line after a final '\n'
    for (LineEdits.Hunk h : LineEdits.between(LineText.chunkLines(before), a)) {
      for (int l = h.newStart(); l < h.newEnd(); l++) {
        changed[l] = true;
      }
    }
    return changed;
  }

  private static String identifierAt(String text, int start, int end) {
    if (start < 0 || end < start || end >= text.length()) {
      return null;
    }
    Matcher m = IDENTIFIER.matcher(text.substring(start, end + 1));
    return m.lookingAt() ? m.group() : null;
  }

  /** The name at {@code start} follows a '.': a qualified name or a member access, which no import helps. */
  private static boolean qualified(String text, int start) {
    int i = start - 1;
    while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
      i--;
    }
    return i >= 0 && text.charAt(i) == '.';
  }

  /**
   * Single-type and single-static imports whose simple name the text never uses. On-demand imports are never
   * listed: which names they serve is not known without bindings.
   */
  private static List<ImportDeclaration> unused(CompilationUnit ast) {
    Set<String> used = usedNames(ast);
    List<ImportDeclaration> out = new ArrayList<>();
    for (Object o : ast.imports()) {
      ImportDeclaration d = (ImportDeclaration) o;
      if (!d.isOnDemand() && !Modifier.isModule(d.getModifiers()) && !used.contains(simpleName(d))) {
        out.add(d);
      }
    }
    return out;
  }

  private static String simpleName(ImportDeclaration d) {
    String name = d.getName().getFullyQualifiedName();
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /**
   * The names the text could use an import for, decided without the compiler's unused-import warning (JDT reports
   * it only for a text without errors): every simple name outside the package and import declarations, in code,
   * annotations and javadoc references, except a name after a qualifier ({@code a.Map}, {@code x.max()}), which no
   * import serves. Conservative: names are matched by their simple name only, so a local variable or method named
   * like an import counts as a use of it (a local {@code max} makes a static import of {@code max} used), in
   * {@code after} and in {@code before} alike.
   */
  private static Set<String> usedNames(CompilationUnit ast) {
    Set<String> out = new HashSet<>();
    ast.accept(new ASTVisitor(true) {
      @Override
      public boolean visit(PackageDeclaration node) {
        if (node.getJavadoc() != null) {
          node.getJavadoc().accept(this); // package-info.java: its javadoc and annotations may use imports
        }
        for (Object a : node.annotations()) {
          ((ASTNode) a).accept(this);
        }
        return false; // not the package name
      }

      @Override
      public boolean visit(ImportDeclaration node) {
        return false;
      }

      @Override
      public boolean visit(SimpleName node) {
        if (!qualifiedMember(node)) {
          out.add(node.getIdentifier());
        }
        return false;
      }
    });
    return out;
  }

  private static boolean qualifiedMember(SimpleName node) {
    StructuralPropertyDescriptor where = node.getLocationInParent();
    ASTNode parent = node.getParent();
    if (where == QualifiedName.NAME_PROPERTY || where == FieldAccess.NAME_PROPERTY
        || where == SuperFieldAccess.NAME_PROPERTY || where == SuperMethodInvocation.NAME_PROPERTY
        || where == QualifiedType.NAME_PROPERTY || where == NameQualifiedType.NAME_PROPERTY
        || where == ExpressionMethodReference.NAME_PROPERTY || where == TypeMethodReference.NAME_PROPERTY
        || where == SuperMethodReference.NAME_PROPERTY) {
      return true;
    }
    if (where == MethodInvocation.NAME_PROPERTY) {
      return ((MethodInvocation) parent).getExpression() != null;
    }
    if (where == MemberRef.NAME_PROPERTY) {
      return ((MemberRef) parent).getQualifier() != null;
    }
    if (where == MethodRef.NAME_PROPERTY) {
      return ((MethodRef) parent).getQualifier() != null;
    }
    return false;
  }

  private static final Set<Integer> UNTERMINATED = Set.of(IProblem.UnterminatedString, IProblem.UnterminatedComment,
      IProblem.UnterminatedTextBlock);

  /** An unterminated string, comment or text block, which swallows the rest of the file. */
  private static boolean unterminated(CompilationUnit ast) {
    for (IProblem p : ast.getProblems()) {
      if (UNTERMINATED.contains(p.getID())) {
        return true;
      }
    }
    return false;
  }

  /**
   * A syntax error: the recovered tree may have lost uses, so no import is judged unused. An unterminated text block
   * has no syntax bit, yet it swallows the rest of the file just like an unterminated string or comment.
   */
  private static boolean syntaxError(CompilationUnit ast) {
    for (IProblem p : ast.getProblems()) {
      if (p.getID() == IProblem.UnterminatedTextBlock || p.isError() && (p.getID() & IProblem.Syntax) != 0) {
        return true;
      }
    }
    return false;
  }

  /** {@code text} parsed as the file without bindings (enough for {@link #usedNames}). */
  private static CompilationUnit parse(ICompilationUnit unit, String text, IProgressMonitor monitor) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setSource(text.toCharArray());
    parser.setUnitName(unit.getElementName());
    parser.setStatementsRecovery(true);
    parser.setCompilerOptions(options(unit));
    return (CompilationUnit) parser.createAST(monitor);
  }

  /** The project's options with javadoc processing on, so references in doc comments count as uses. */
  private static Map<String, String> options(ICompilationUnit unit) {
    Map<String, String> options = unit.getJavaProject().getOptions(true);
    options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
    return options;
  }

  /** A working copy of the file in an owner of its own holding {@code text}, and its AST with bindings. */
  private record Scratch(ICompilationUnit copy, CompilationUnit ast) {
    static Scratch open(ICompilationUnit unit, String text, IProgressMonitor monitor) throws JavaModelException {
      ICompilationUnit copy = unit.getWorkingCopy(new WorkingCopyOwner() {}, monitor);
      JdtTestHooks.scratchCopies(1);
      try {
        Runnable hook = JdtTestHooks.parseHook();
        if (hook != null) {
          hook.run();
        }
        copy.getBuffer().setContents(text);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(copy);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setCompilerOptions(options(copy)); // after setSource, which takes the project's options
        return new Scratch(copy, (CompilationUnit) parser.createAST(monitor));
      } catch (Throwable e) { // also an Error: a StackOverflowError of the parser must not leak the copy
        try {
          discard(copy);
        } catch (JavaModelException | RuntimeException d) {
          e.addSuppressed(d); // the original failure is the one to report
        }
        throw e;
      }
    }
  }

  private static void discard(ICompilationUnit copy) throws JavaModelException {
    try {
      copy.discardWorkingCopy();
    } finally {
      JdtTestHooks.scratchCopies(-1);
    }
  }

  private static void close(Scratch s, IFile file) {
    if (s == null) {
      return;
    }
    try {
      discard(s.copy());
    } catch (JavaModelException | RuntimeException e) {
      ILog.of(ImportFixer.class).log(new Status(IStatus.WARNING, JavaPanes.PLUGIN_ID,
          "Could not discard a scratch working copy of " + file.getFullPath(), e));
    }
  }
}
