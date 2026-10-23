package net.aftergit.merge.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.aftergit.merge.core.check.CompileCheck;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.ResultWriter;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;

/**
 * Resolve All Simple Conflicts (spec §5.5): the resolver pipeline over many files without an editor. A file is
 * written only when at least one of its conflicts was resolved; the rest stay git conflict markers in the
 * repository's style. Java files get pass 1 and the compile check through {@link JavaChecks}.
 */
public final class ResolveAll {
  static final String PLUGIN_ID = "net.aftergit.merge.git";

  /**
   * {@code policy}: whitespace policy of the merge; {@code pipeline}: the resolvers that run (the UI passes the defaults
   * with its switches and the contributed resolvers; resolvers 6c design); {@code greedy}: whether pass 3 (spec §5.1)
   * was asked for, for information only: the pipeline alone decides which resolvers run.
   */
  public record Options(ComparisonPolicy policy, boolean greedy, ResolverPipeline pipeline) {
    public Options {
      Objects.requireNonNull(pipeline, "pipeline");
    }

    /** The built-in resolvers, each switch as by default, nothing contributed. */
    public Options(ComparisonPolicy policy, boolean greedy) {
      this(policy, greedy, ResolverPipeline.defaults(greedy));
    }
  }

  private ResolveAll() {}

  /**
   * Resolves {@code files} in order. {@code java} gives the Java checks of a file (null for none); {@code busy} are
   * files others hold (unsaved editors, an open resolver), matched by handle or by location. A file saved or
   * rewritten while it is resolved is left as it is ({@code CHANGED_DURING_RUN}). Cancelling stops before the next
   * file; what was written stays and can be undone through the report. A failing file is logged and left as it was;
   * an Error beyond StackOverflowError, LinkageError and AssertionError stops the run, and the report keeps the undo.
   *
   * <p>Each file is checked and written under its own modify rule ({@code IWorkspace.run}), so call this without a
   * scheduling rule, or under one that contains every file. A caller holding an unrelated rule (a job with some other
   * rule, a resource-change listener) cannot get the files' rules and every file fails.
   */
  public static ResolveAllReport run(List<IFile> files, Options options, Function<IFile, JavaChecks> java,
      Set<IFile> busy, IProgressMonitor monitor) {
    Set<IFile> fixed = Set.copyOf(busy);
    return run(files, options, java, () -> fixed, monitor);
  }

  /**
   * Like {@link #run(List, Options, Function, Set, IProgressMonitor)}, but asks {@code busy} for the files others
   * hold again for each file, when it is reached and right before its resolution is written: a file opened in the
   * resolver while the run works on it is left alone.
   */
  public static ResolveAllReport run(List<IFile> files, Options options, Function<IFile, JavaChecks> java,
      Supplier<Set<IFile>> busy, IProgressMonitor monitor) {
    return run(files, f -> options, java, busy, monitor);
  }

  /**
   * Like {@link #run(List, Options, Function, Supplier, IProgressMonitor)}, with the options of each file asked for
   * when the file is reached (preferences design §6: the settings of the file's project).
   */
  public static ResolveAllReport run(List<IFile> files, Function<IFile, Options> options,
      Function<IFile, JavaChecks> java, Supplier<Set<IFile>> busy, IProgressMonitor monitor) {
    SubMonitor sm = SubMonitor.convert(monitor, files.size());
    ResolveAllUndo undo = new ResolveAllUndo();
    List<FileOutcome> out = new ArrayList<>();
    Predicate<IFile> isBusy = f -> isBusy(f, busy.get());
    boolean cancelled = false;
    for (IFile file : files) {
      if (sm.isCanceled()) {
        cancelled = true;
        break;
      }
      sm.subTask(file.getFullPath().toString());
      if (sm.isCanceled()) {
        cancelled = true;
        break;
      }
      FileOutcome o;
      boolean stop = false;
      try {
        o = isBusy.test(file) ? FileOutcome.untouched(file, FileOutcome.Reason.BUSY)
            : resolve(file, options.apply(file), java, isBusy, undo, sm.newChild(1));
      } catch (OperationCanceledException e) {
        o = null; // thrown before anything was written (resolve puts a file back that it began to write)
      } catch (PartlyWrittenException e) {
        ILog.of(ResolveAll.class).log(new Status(IStatus.ERROR, PLUGIN_ID, "Resolve All could not finish writing "
            + file.getFullPath() + "; Undo puts back its earlier content, also kept in the local history",
            loggable(e.getCause())));
        o = FileOutcome.untouched(file, FileOutcome.Reason.FAILED);
      } catch (CoreException | IOException | RuntimeException | StackOverflowError | LinkageError
          | AssertionError e) {
        ILog.of(ResolveAll.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
            "Resolve All left " + file.getFullPath() + " as it was", loggable(e)));
        o = FileOutcome.untouched(file, FileOutcome.Reason.FAILED);
      } catch (Error e) {
        // the runtime is in trouble: stop, but keep the report so the files written so far can be undone
        ILog.of(ResolveAll.class).log(new Status(IStatus.ERROR, PLUGIN_ID,
            "Resolve All stopped at " + file.getFullPath() + ", which it left as it was", loggable(e)));
        o = FileOutcome.untouched(file, FileOutcome.Reason.FAILED);
        stop = true;
      }
      if (o == null) { // cancelled before writing
        cancelled = true;
        break;
      }
      out.add(o);
      if (stop) {
        break;
      }
    }
    return new ResolveAllReport(out, cancelled, files.size(), undo);
  }

  /** {@code file} is one of {@code busy}, by handle or by location (the same disk file may be open through another
   * project's handle). */
  static boolean isBusy(IFile file, Set<IFile> busy) {
    if (busy.contains(file)) {
      return true;
    }
    IPath location = file.getLocation();
    if (location == null) {
      return false;
    }
    for (IFile f : busy) {
      if (location.equals(f.getLocation())) {
        return true;
      }
    }
    return false;
  }

  /** The outcome of one file, or null when the run was cancelled before it was written. */
  static FileOutcome resolve(IFile file, Options options, Function<IFile, JavaChecks> javaFor,
      Predicate<IFile> isBusy, ResolveAllUndo undo, IProgressMonitor monitor) throws CoreException, IOException {
    Optional<RepoFile> located = RepositoryLocator.locate(file);
    if (located.isEmpty()) {
      return FileOutcome.untouched(file, FileOutcome.Reason.NOT_IN_REPOSITORY);
    }
    RepoFile rf = located.get();
    ConflictKind kind = ConflictKinds.classify(rf.repository(), rf.path());
    if (kind == ConflictKind.NONE) {
      return FileOutcome.untouched(file, FileOutcome.Reason.NOT_CONFLICTED);
    }
    if (kind != ConflictKind.TEXT) {
      return FileOutcome.otherKind(file, kind);
    }
    if (!rf.file().isFile()) {
      return FileOutcome.untouched(file, FileOutcome.Reason.NO_WORKING_TREE_FILE);
    }
    file.refreshLocal(IResource.DEPTH_ZERO, monitor); // a command-line merge may have rewritten it
    Charset charset = Charset.forName(file.getCharset());
    MergeSources s = StageReader.read(rf, charset, new Labels("ours", "theirs"));
    if (s.base() == null || s.ours() == null || s.theirs() == null) {
      return FileOutcome.untouched(file, FileOutcome.Reason.UNREADABLE);
    }
    if (s.workingTree() == null) {
      return FileOutcome.untouched(file, FileOutcome.Reason.NO_WORKING_TREE_FILE);
    }
    String eol = LineEnds.ofContent(s.workingTree());
    if (eol == null) {
      return FileOutcome.untouched(file, FileOutcome.Reason.MIXED_LINE_ENDINGS);
    }
    // bytes read as ISO-8859-1 are no Java source to parse or compile
    JavaChecks java = charset.equals(s.decodedAs()) ? javaFor.apply(file) : null;
    ResolverPipeline pipeline = options.pipeline().forFile(file.getName());
    InitResult init = HybridInitializer.initialize(new MergeInput(s.base(), s.ours(), s.theirs(), s.workingTree()),
        options.policy(), pipeline, java == null ? null : java.locator());
    MergeModel m = init.model();
    int resolved = 0;
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && b.state() == BlockState.AUTO_RESOLVED) {
        resolved++;
      }
    }
    int conflicts = resolved + m.unresolvedConflicts();
    if (resolved == 0) {
      return new FileOutcome(file, file.getFullPath().toString(), FileOutcome.Result.UNTOUCHED, kind, 0, conflicts,
          0, FileOutcome.Reason.NOTHING_RESOLVED);
    }
    int notCompiling = 0;
    if (java != null) {
      // as in the editor (preferences ruling 5): with the Java imports switch off the imports stay as they are
      if (init.imports() != null && pipeline.ids().contains(ImportsResolver.ID)) {
        java.fixImports(m, init.imports(), monitor);
      }
      notCompiling = notCompiling(java, m, monitor);
    }
    if (monitor.isCanceled()) {
      return null;
    }
    MarkerConfig markers = MarkerConfig.of(rf.repository(), s.workingTree());
    LineText w = LineText.of(s.workingTree());
    boolean trailing = LineText.mergeTrailingNewline(LineText.of(s.base()), LineText.of(s.ours()),
        LineText.of(s.theirs()), w);
    String text = ResultWriter.render(m, markers.style(), markers.oursLabel(), markers.baseLabel(),
        markers.theirsLabel(), ConflictMarkerParser.DEFAULT_MARKER_SIZE, eol, trailing);
    byte[] bytes = Decoding.encode(text, s.decodedAs());
    byte[] read = Decoding.encode(s.workingTree(), s.decodedAs()); // exact: decodedAs round-trips every input
    if (isBusy.test(file)) {
      return new FileOutcome(file, file.getFullPath().toString(), FileOutcome.Result.UNTOUCHED, kind, 0, conflicts,
          0, FileOutcome.Reason.BUSY); // opened while it was resolved
    }
    boolean[] changed = new boolean[1];
    // under the file's rule no Eclipse save can come between the check and the write
    ResourcesPlugin.getWorkspace().run(pm -> {
      byte[] original = Files.isRegularFile(rf.file().toPath()) ? readAll(rf) : null;
      if (!Arrays.equals(original, read)) {
        changed[0] = true; // saved or rewritten while this run resolved it: the resolution is stale
        return;
      }
      write(file, rf, original, bytes, undo, pm);
    }, ResourcesPlugin.getWorkspace().getRuleFactory().modifyRule(file), 0, monitor);
    if (changed[0]) {
      return new FileOutcome(file, file.getFullPath().toString(), FileOutcome.Result.UNTOUCHED, kind, 0, conflicts,
          0, FileOutcome.Reason.CHANGED_DURING_RUN);
    }
    FileOutcome.Result result = m.unresolvedConflicts() == 0 && notCompiling == 0 ? FileOutcome.Result.FULL
        : FileOutcome.Result.PARTIAL;
    return new FileOutcome(file, file.getFullPath().toString(), result, kind, resolved, conflicts, notCompiling,
        FileOutcome.Reason.NONE);
  }

  private static byte[] readAll(RepoFile rf) throws CoreException {
    try {
      return Files.readAllBytes(rf.file().toPath());
    } catch (IOException e) {
      throw new CoreException(Status.error("Could not read " + rf.file(), e));
    }
  }

  /**
   * Writes {@code bytes} over {@code original} and records it for undo. A failed write that changed the file puts
   * {@code original} back; if even that fails, the file goes into the undo as it now is and the failure is a
   * {@link PartlyWrittenException}.
   */
  private static void write(IFile file, RepoFile rf, byte[] original, byte[] bytes, ResolveAllUndo undo,
      IProgressMonitor monitor) throws CoreException {
    try {
      file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
    } catch (CoreException | RuntimeException e) {
      byte[] now;
      try {
        now = Files.readAllBytes(rf.file().toPath());
      } catch (IOException unreadable) {
        now = null;
      }
      if (Arrays.equals(now, original)) {
        throw e; // nothing was written
      }
      try {
        file.refreshLocal(IResource.DEPTH_ZERO, null);
        file.setContents(new ByteArrayInputStream(original), IResource.FORCE | IResource.KEEP_HISTORY, null);
      } catch (CoreException | RuntimeException again) {
        e.addSuppressed(again);
        undo.add(file, rf, original, now);
        throw new PartlyWrittenException(e);
      }
      throw e;
    }
    undo.add(file, rf, original, bytes);
  }

  /** A write failed after it changed the file, and the file could not be put back; it is in the undo. */
  static final class PartlyWrittenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PartlyWrittenException(Throwable cause) {
      super(cause);
    }
  }

  /** Auto-resolved blocks with a compile error ours and theirs do not have there (spec §5.4); 0 while unknown. */
  private static int notCompiling(JavaChecks java, MergeModel m, IProgressMonitor monitor) {
    // the result first: without an error there, ours and theirs need no check
    List<List<LineProblem>> found = java.errors(m.document().chunk(0, m.document().lineCount()),
        LineText.render(m.left().lines(), "\n", true), LineText.render(m.right().lines(), "\n", true), monitor);
    if (found == null || found.get(0).isEmpty()) {
      return 0;
    }
    SortedSet<Integer> failing = CompileCheck.failingBlocks(m, found.get(0), found.get(1), found.get(2));
    return failing.size();
  }

  /**
   * {@code e} for a logged status, like the UI's JavaBridge.loggable (merge.git cannot reach it): a
   * VirtualMachineError goes in as the cause of a wrapper, because the workbench treats a logged one as fatal.
   */
  static Throwable loggable(Throwable e) {
    return e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e;
  }
}
