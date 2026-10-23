package net.aftergit.merge.lsp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.LanguageServiceAccessor;

/**
 * Keeps lsp4e's save participants out of AfterGit's commit of the result (languages design, Part 2: the resolved
 * text reaches the file exactly, as Java's with the Save Actions off). Before a save of a buffer it connected, lsp4e
 * asks the server for edits (willSaveWaitUntil) and applies them, or formats the document (format on save); it does
 * so in the file buffer listener of each server, on the thread of the save, waiting for the server. lsp4e has no
 * switch for it, so while AfterGit commits, each such listener is replaced with one that passes every event on
 * except the one before the save of this document (checked with javap: LanguageServerWrapper.fileBufferListener,
 * added once the server is initialized and removed when it stops; LSFileBufferListener.stateChanging calls
 * DocumentContentSynchronizer.documentAboutToBeSaved only). The save itself (didSave) still reaches the servers.
 * Only the running servers connected to the document are looked at, and none is asked anything: lsp4e's look-up by
 * document waits for each server's initialization (up to 10 s on the UI thread) and starts stopped ones.
 */
@SuppressWarnings("restriction") // lsp4e exports LanguageServerWrapper and LanguageServiceAccessor to friends only
public final class SaveParticipants {
  private static final AtomicBoolean failureLogged = new AtomicBoolean();

  private SaveParticipants() {}

  /**
   * Until the returned runnable runs, no server of {@code document} changes it before a save; UI thread, around
   * the commit of the buffer. Never throws; when lsp4e's listener cannot be reached, nothing is held (logged once).
   */
  public static Runnable hold(IDocument document) {
    if (document == null) {
      return () -> {};
    }
    List<Runnable> resumes = new ArrayList<>();
    try {
      ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
      Field field = LanguageServerWrapper.class.getDeclaredField("fileBufferListener");
      field.setAccessible(true);
      URI uri = LSPEclipseUtils.toUri(document);
      // no capabilities predicate: lsp4e then neither waits for a server's initialization nor starts one (javap)
      for (LanguageServerWrapper w : LanguageServiceAccessor.getStartedWrappers(null, true)) {
        // only a server connected to this document has a save participant for its buffer; one still starting has
        // no listener yet (it is added once the server is initialized)
        if (uri == null || !w.isConnectedTo(uri) || !(field.get(w) instanceof IFileBufferListener original)) {
          continue;
        }
        IFileBufferListener held = withoutSaveParticipants(original, document);
        manager.removeFileBufferListener(original);
        manager.addFileBufferListener(held);
        resumes.add(() -> {
          manager.removeFileBufferListener(held);
          if (w.isActive()) { // a server stopped meanwhile removed its listener itself; adding is idempotent
            manager.addFileBufferListener(original);
          }
        });
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      resume(resumes);
      if (failureLogged.compareAndSet(false, true)) {
        ILog.of(SaveParticipants.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID, "The save participants"
            + " of lsp4e could not be held; a language server may change the merge result as it is saved",
            Grammars.loggable(e)));
      }
      return () -> {};
    }
    return () -> resume(resumes);
  }

  private static void resume(List<Runnable> resumes) {
    for (Runnable r : resumes) {
      try {
        r.run();
      } catch (RuntimeException e) {
        ILog.of(SaveParticipants.class).log(new Status(IStatus.WARNING, Grammars.PLUGIN_ID,
            "A file buffer listener of lsp4e could not be put back", e));
      }
    }
  }

  /** {@code original} for every event except stateChanging of the buffer of {@code document}. */
  private static IFileBufferListener withoutSaveParticipants(IFileBufferListener original, IDocument document) {
    InvocationHandler h = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        switch (m.getName()) {
          case "equals":
            if (m.getParameterCount() == 1) {
              return proxy == args[0];
            }
            break;
          case "hashCode":
            if (m.getParameterCount() == 0) {
              return System.identityHashCode(proxy);
            }
            break;
          case "toString":
            if (m.getParameterCount() == 0) {
              return "held " + original;
            }
            break;
          case "stateChanging":
            if (args != null && args.length == 1 && isBufferOf(args[0], document)) {
              return null; // lsp4e's willSaveWaitUntil or format on save
            }
            break;
          default:
            break;
        }
        try {
          return m.invoke(original, args);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      }
    };
    return (IFileBufferListener) Proxy.newProxyInstance(SaveParticipants.class.getClassLoader(),
        new Class<?>[] {IFileBufferListener.class}, h);
  }

  private static boolean isBufferOf(Object buffer, IDocument document) {
    return buffer instanceof ITextFileBuffer b && b.getDocument() == document;
  }
}
