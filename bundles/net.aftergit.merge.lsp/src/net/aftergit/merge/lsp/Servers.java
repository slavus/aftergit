package net.aftergit.merge.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.lsp4e.ContentTypeToLanguageServerDefinition;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;

/**
 * The language servers lsp4e has for a file (languages design, Part 2): every server of lsp4e's extension point or
 * of a launch configuration (Preferences > Language Servers) whose content type the file has and which is enabled
 * for it; never one for a Java file, which stays on JDT.
 */
@SuppressWarnings("restriction") // org.eclipse.lsp4e names its friends only; its registry is how clients find servers
public final class Servers {
  private static final AtomicBoolean failureLogged = new AtomicBoolean();

  private Servers() {}

  /** True when lsp4e has a server for {@code file} that AfterGit may use. */
  public static boolean hasServer(IFile file) {
    return !definitionsFor(file).isEmpty();
  }

  /** The servers for {@code file} in lsp4e's order; empty for a Java file, without a server, or when lsp4e fails. */
  static List<LanguageServerDefinition> definitionsFor(IFile file) {
    if (file == null || Grammars.isJava(file.getName())) {
      return List.of();
    }
    try {
      LanguageServersRegistry registry = LanguageServersRegistry.getInstance();
      Predicate<String> allowed = LspTestHooks.serverFilter();
      List<ContentTypeToLanguageServerDefinition> entries = new ArrayList<>(registry.getContentTypeToLSPExtensions());
      entries.addAll(registry.getContentTypeToLSPLaunches());
      List<LanguageServerDefinition> out = new ArrayList<>();
      for (ContentTypeToLanguageServerDefinition e : entries) {
        LanguageServerDefinition d = e.getValue();
        // matches: a content type of the file and the server's enablement for it, as lsp4e decides for an editor
        if (d != null && !out.contains(d) && allowed.test(d.id) && registry.matches(file, d)) {
          out.add(d);
        }
      }
      return List.copyOf(out);
    } catch (RuntimeException | LinkageError e) {
      // a broken registry must not cost the resolver: the file goes without the language server (spec Principles)
      if (failureLogged.compareAndSet(false, true)) {
        ILog.of(Servers.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID,
            "The language servers of lsp4e are not available; the resolver shows no errors of other languages",
            Grammars.loggable(e)));
      }
      return List.of();
    }
  }
}
