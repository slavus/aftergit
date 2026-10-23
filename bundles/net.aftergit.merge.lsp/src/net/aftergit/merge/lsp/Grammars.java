package net.aftergit.merge.lsp;

import java.util.Locale;
import java.util.function.Function;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;

/**
 * The TextMate grammar Eclipse uses for a file type (tm4e's registry, as the Generic Editor finds it), and none for
 * Java files, which stay on JDT (languages design, Principles).
 */
public final class Grammars {
  public static final String PLUGIN_ID = "net.aftergit.merge.lsp";
  static final String JAVA_SOURCE = "org.eclipse.jdt.core.javaSource";
  static final String JAVA_SCOPE = "source.java";
  /**
   * The Markdown grammar of tm4e's language pack (tm4e 0.17) does not compile with the joni of Eclipse (2.2): a
   * look-behind of its strikethrough rule is rejected and tm4e logs an error for every line it tokenizes. Markdown
   * files therefore stay plain text, and the grammar is not even loaded (loading it logs every language its code
   * blocks embed that Eclipse lacks).
   */
  static final String MARKDOWN_SCOPE = "text.html.markdown";

  private Grammars() {}

  /** The scope name of the grammar for files named {@code fileName} (e.g. "source.ts"), or null. */
  public static String scopeOf(String fileName) {
    IGrammar g = forFileName(fileName);
    // tm4e's registry names a grammar with the bundle that contributed it ("source.ts@org.eclipse.tm4e.language_pack")
    return g == null ? null : ITMScope.parse(g.getScopeName()).getName();
  }

  /** True for a Java source file, with or without the JDT installed. */
  public static boolean isJava(String fileName) {
    if (fileName == null) {
      return false;
    }
    if (fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
      return true;
    }
    IContentTypeManager types = Platform.getContentTypeManager();
    IContentType java = types.getContentType(JAVA_SOURCE);
    if (java == null) {
      return false;
    }
    for (IContentType t : types.findContentTypesFor(fileName)) {
      if (t != null && t.isKindOf(java)) {
        return true;
      }
    }
    return false;
  }

  /** The grammar for files named {@code fileName}, or null (Java, no grammar, or a failing registry, logged). */
  static IGrammar forFileName(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
      return null;
    }
    try {
      if (isJava(fileName)) {
        return null;
      }
      Function<String, IGrammar> hook = LspTestHooks.grammarLookup();
      IGrammar g = hook != null ? hook.apply(fileName) : lookUp(fileName);
      if (g == null) {
        return null;
      }
      String scope = ITMScope.parse(g.getScopeName()).getName();
      // whatever content type or extension led to it, a Java grammar never colours a pane: Java stays on JDT
      return JAVA_SCOPE.equals(scope) || MARKDOWN_SCOPE.equals(scope) ? null : g;
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      // a broken grammar or registry must not keep the resolver from opening: plain text instead (spec Principles)
      ILog.of(Grammars.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
          "No TextMate grammar for " + fileName + "; the resolver shows it as plain text", loggable(e)));
      return null;
    }
  }

  private static IGrammar lookUp(String fileName) {
    IGrammarRegistryManager registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
    IContentType[] types = Platform.getContentTypeManager().findContentTypesFor(fileName);
    if (isMarkdown(registry, types)) {
      return null;
    }
    IGrammar g = types.length == 0 ? null : registry.getGrammarFor(types);
    if (g == null) {
      String ext = new Path(fileName).getFileExtension();
      g = ext == null ? null : registry.getGrammarForFileExtension(ext);
    }
    return g;
  }

  /** Whether one of {@code types} is (a kind of) a content type of the Markdown grammar. */
  private static boolean isMarkdown(IGrammarRegistryManager registry, IContentType[] types) {
    for (IGrammarDefinition d : registry.getDefinitions()) {
      if (!MARKDOWN_SCOPE.equals(d.getScope().getName())) {
        continue;
      }
      for (IContentType markdown : registry.getContentTypesForScope(d.getScope())) {
        for (IContentType t : types) {
          if (t != null && t.isKindOf(markdown)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * {@code e} for a logged status. A VirtualMachineError goes in as the cause of a wrapper: the workbench treats a
   * logged one as fatal and offers to exit, though AfterGit recovered from it.
   */
  public static Throwable loggable(Throwable e) {
    return e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e;
  }
}
