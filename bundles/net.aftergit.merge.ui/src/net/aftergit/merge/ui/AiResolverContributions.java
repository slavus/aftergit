package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.aftergit.merge.core.ai.AiRequests;
import net.aftergit.merge.core.ai.AiResolver;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * The AI resolvers contributed through net.aftergit.merge.aiResolver (resolve-with-ai design §2), read once. A
 * contribution that fails is never allowed to break the editor: a failing ready() means not ready, a failing name()
 * means its label, a failing remote() means remote (the user is asked), each failure logged once per session.
 */
final class AiResolverContributions {
  static final String POINT = "net.aftergit.merge.aiResolver";

  /** A contributed resolver; {@code label} is the name of its element, shown when the resolver has no name. */
  record Contribution(String id, String label, AiResolver resolver) {}

  private static volatile List<Contribution> cached;
  private static volatile List<Contribution> forTests;
  private static final Set<String> reported = ConcurrentHashMap.newKeySet();

  private AiResolverContributions() {}

  /** Replaces the contributions (null: the registry's). */
  static void setForTests(List<Contribution> contributions) {
    forTests = contributions == null ? null : List.copyOf(contributions);
    reported.clear();
  }

  static List<Contribution> all() {
    List<Contribution> t = forTests;
    if (t != null) {
      return t;
    }
    List<Contribution> c = cached;
    if (c == null) {
      synchronized (AiResolverContributions.class) {
        c = cached;
        if (c == null) {
          c = read(Platform.getExtensionRegistry().getConfigurationElementsFor(POINT));
          cached = c;
        }
      }
    }
    return c;
  }

  /** The contributions of {@code elements}, in order; one without id, with a taken id or no AiResolver is left out. */
  static List<Contribution> read(IConfigurationElement[] elements) {
    List<Contribution> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (IConfigurationElement e : elements) {
      if (!"resolver".equals(e.getName())) {
        continue;
      }
      String id = e.getAttribute("id");
      Object created;
      try {
        created = e.createExecutableExtension("class");
      } catch (CoreException | RuntimeException | LinkageError | StackOverflowError ex) {
        // the registry wraps only exceptions: an Error of the constructor arrives here as it is
        log("The AI resolver " + id + " of " + e.getNamespaceIdentifier() + " could not be created; it is left out",
            ex);
        continue;
      }
      if (!(created instanceof AiResolver) || id == null || id.isBlank() || !ids.add(id)) {
        log("The AI resolver " + id + " of " + e.getNamespaceIdentifier()
            + " is no AiResolver, has no id or one that is taken; it is left out", null);
        continue;
      }
      String label = e.getAttribute("name");
      out.add(new Contribution(id, label == null || label.isBlank() ? id : label, (AiResolver) created));
    }
    return List.copyOf(out);
  }

  /** The contributions that are ready now, in order (design §1: only these are shown). */
  static List<Contribution> ready() {
    List<Contribution> out = new ArrayList<>();
    for (Contribution c : all()) {
      if (isReady(c)) {
        out.add(c);
      }
    }
    return out;
  }

  static boolean isReady(Contribution c) {
    try {
      return c.resolver().ready();
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      once(c, "could not tell whether it is ready; it is not offered", e);
      return false;
    }
  }

  /** The resolver's name for menus and proposals; its label when it has none. */
  static String name(Contribution c) {
    try {
      String n = c.resolver().name();
      return n == null || n.isBlank() ? c.label() : n;
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      once(c, "has no name; its label is shown", e);
      return c.label();
    }
  }

  /** True when its requests leave this machine; unknown counts as true, so the user is asked. */
  static boolean remote(Contribution c) {
    try {
      return c.resolver().remote();
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      once(c, "could not tell whether it sends data off this machine; it counts as remote", e);
      return true;
    }
  }

  /** Where its requests go, as it says; empty when it does not say or cannot tell. */
  static String destination(Contribution c) {
    try {
      String d = c.resolver().destination();
      return d == null ? "" : d.strip();
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      once(c, "could not tell where its requests go", e);
      return "";
    }
  }

  /**
   * What the user's consent to a remote resolver is kept for (resolve-with-ai §5): its destination, or, when it
   * names none, the resolver itself.
   */
  static String consentKey(Contribution c) {
    String d = destination(c);
    return d.isEmpty() ? "resolver:" + c.id() : d;
  }

  /** The destination as the user is told it: as the resolver says it, else its id. */
  static String shownDestination(Contribution c) {
    String d = destination(c);
    return d.isEmpty() ? c.id() : d;
  }

  /** The resolver's context lines, from 0 to {@link AiRequests#MAX_CONTEXT_LINES}. */
  static int contextLines(Contribution c) {
    try {
      return Math.max(0, Math.min(c.resolver().contextLines(), AiRequests.MAX_CONTEXT_LINES));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      once(c, "could not tell its context lines; " + AiResolver.DEFAULT_CONTEXT_LINES + " are sent", e);
      return AiResolver.DEFAULT_CONTEXT_LINES;
    }
  }

  private static void once(Contribution c, String what, Throwable e) {
    if (reported.add(c.id() + " " + what)) {
      log("The AI resolver " + c.id() + " " + what, e);
    }
  }

  private static void log(String message, Throwable e) {
    ILog.of(AiResolverContributions.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui", message,
        e == null ? null : JavaBridge.loggable(e)));
  }
}
