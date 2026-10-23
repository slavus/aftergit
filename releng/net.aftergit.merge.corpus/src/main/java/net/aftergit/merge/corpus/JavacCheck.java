package net.aftergit.merge.corpus;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Single-file compile check with the JDK compiler, without the project's classpath: the corpus stand-in for the
 * resolver's check against ours and theirs (spec §5.4, core CompileCheck). Neither a class path nor a source path is
 * given, whatever the JVM running the corpus has. Errors are compared by message, with counts, over the whole file:
 * an error of the result that ours or theirs has too (most often a type of the project javac cannot see) is
 * inherited, any other is new.
 */
final class JavacCheck {
  private static final List<String> OPTIONS = List.of("-proc:none", "-Xlint:none", "-nowarn", "-implicit:none");

  private static final long TIMEOUT_SECONDS = 60;

  private JavacCheck() {}

  /** Error messages of {@code source} compiled as {@code fileName}; empty without a system compiler. */
  static Optional<List<String>> errors(String fileName, String source) {
    return errors(fileName, source, TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** As above; throws {@link IllegalStateException} when the compiler fails or does not finish in time. */
  static Optional<List<String>> errors(String fileName, String source, long timeout, TimeUnit unit) {
    if (ToolProvider.getSystemJavaCompiler() == null) {
      return Optional.empty();
    }
    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "corpus-javac");
      t.setDaemon(true);
      return t;
    });
    try {
      Future<List<String>> future = executor.submit(() -> compile(fileName, source));
      return Optional.of(future.get(timeout, unit));
    } catch (TimeoutException e) {
      throw new IllegalStateException("javac did not finish in " + timeout + " " + unit, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("javac failed: " + e.getCause(), e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private static List<String> compile(String fileName, String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
      }
    };
    try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
        StandardCharsets.UTF_8)) {
      standard.setLocation(StandardLocation.CLASS_PATH, List.of());
      standard.setLocation(StandardLocation.SOURCE_PATH, List.of());
      JavaFileManager noOutput = new ForwardingJavaFileManager<>(standard) {
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
            FileObject sibling) {
          return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
            @Override
            public OutputStream openOutputStream() {
              return OutputStream.nullOutputStream();
            }
          };
        }
      };
      compiler.getTask(null, noOutput, diagnostics, OPTIONS, null, List.of(file)).call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<String> out = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
      if (d.getKind() == Diagnostic.Kind.ERROR) {
        out.add(d.getMessage(Locale.ROOT));
      }
    }
    return List.copyOf(out);
  }

  /** True when every error of {@code result} is one that ours or theirs has too, as often; empty without a system compiler. */
  static Optional<Boolean> noNewErrors(String fileName, String result, String ours, String theirs) {
    Optional<List<String>> errors = errors(fileName, result);
    if (errors.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Integer> allowed = counts(errors(fileName, ours).orElseThrow());
    counts(errors(fileName, theirs).orElseThrow()).forEach((k, v) -> allowed.merge(k, v, Math::max));
    for (Map.Entry<String, Integer> e : counts(errors.get()).entrySet()) {
      if (e.getValue() > allowed.getOrDefault(e.getKey(), 0)) {
        return Optional.of(false);
      }
    }
    return Optional.of(true);
  }

  private static Map<String, Integer> counts(List<String> messages) {
    Map<String, Integer> m = new HashMap<>();
    messages.forEach(s -> m.merge(s, 1, Integer::sum));
    return m;
  }
}
