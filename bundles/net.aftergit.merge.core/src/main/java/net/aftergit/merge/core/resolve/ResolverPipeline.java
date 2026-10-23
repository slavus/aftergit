package net.aftergit.merge.core.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;

/**
 * Runs resolvers in order over every auto-resolvable conflict of a model; each resolver says which blocks it
 * takes ({@link ConflictResolver#isApplicable}). A failing resolver is logged and skipped (spec §9).
 * A pipeline bound to a file name ({@link #forFile}) runs only the resolvers for that file; an exclusive resolver
 * ({@link ConflictResolver#exclusive}) that cannot decide, or fails, keeps later resolvers off the block.
 */
public final class ResolverPipeline {
  /** Rationale key of a block resolved only by ignoring whitespace (one side's whitespace edit dropped). */
  public static final String RATIONALE_WHITESPACE_IGNORED = "whitespace-ignored";

  private static final Logger LOG = Logger.getLogger(ResolverPipeline.class.getName());

  private final List<ConflictResolver> resolvers;
  private final String fileName;

  public ResolverPipeline(List<ConflictResolver> resolvers) {
    this(resolvers, null);
  }

  private ResolverPipeline(List<ConflictResolver> resolvers, String fileName) {
    this.resolvers = List.copyOf(resolvers);
    this.fileName = fileName;
  }

  /**
   * This pipeline for a file named {@code fileName} (its last path segment; null: unknown). Only the resolvers that
   * apply to that name ({@link ConflictResolver#appliesTo}) run; without a name only those for every file run.
   */
  public ResolverPipeline forFile(String fileName) {
    return new ResolverPipeline(resolvers, fileName);
  }

  /** Ids of the resolvers that run, in order. */
  public List<String> ids() {
    return active().stream().map(a -> name(a.resolver())).toList();
  }

  /** A resolver that runs on this file; {@code broken} when it failed to say whether it applies. */
  private record Active(ConflictResolver resolver, boolean broken) {}

  /**
   * The resolvers for this file. One that fails to say whether it applies runs as one whose isApplicable fails: it
   * may be the format resolver of the file, so when it is exclusive its blocks stay conflicts.
   */
  private List<Active> active() {
    List<Active> out = new ArrayList<>();
    for (ConflictResolver r : resolvers) {
      try {
        if (r.appliesTo(fileName)) {
          out.add(new Active(r, false));
        }
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        warn(r, "failed on the file name " + fileName, e);
        out.add(new Active(r, true));
      }
    }
    return out;
  }

  /** Ids of the built-in resolvers, in pipeline order; a contributed resolver must not use one. */
  public static final List<String> BUILT_IN_IDS = List.of(ImportsResolver.ID, NewerVersionResolver.ID,
      PropertiesResolver.ID, ManifestResolver.ID, "simple", "greedy");

  /** Whether the switch of the resolver {@code id} is on until the user sets it: all are but "Newer version wins". */
  public static boolean enabledByDefault(String id) {
    return !NewerVersionResolver.ID.equals(id);
  }

  /** The built-in resolvers with their switches as by default ({@link #enabledByDefault}), nothing contributed. */
  public static ResolverPipeline defaults(boolean greedyEnabled) {
    return defaults(greedyEnabled, ResolverPipeline::enabledByDefault, List.of());
  }

  /**
   * The resolvers of the editor and of Resolve All (resolvers 6c design): imports, "Newer version wins", the
   * .properties and the MANIFEST.MF resolvers, each when {@code enabled} accepts its id; the contributed
   * {@code extra}, which the caller has already chosen by their switches (their id() is not asked here, so one that
   * fails cannot break the build of the pipeline); simple; greedy when enabled. Each acts only in its files
   * ({@link #forFile}).
   */
  public static ResolverPipeline defaults(boolean greedyEnabled, Predicate<String> enabled,
      List<ConflictResolver> extra) {
    List<ConflictResolver> list = new ArrayList<>();
    if (enabled.test(ImportsResolver.ID)) {
      list.add(new ImportsResolver()); // pass 1; it acts only on import blocks, which only Java files have
    }
    for (ConflictResolver r : List.of(new NewerVersionResolver(), new PropertiesResolver(), new ManifestResolver())) {
      if (enabled.test(r.id())) {
        list.add(r);
      }
    }
    list.addAll(extra);
    list.add(new SimpleTextResolver());
    if (greedyEnabled) {
      list.add(new GreedyTextResolver());
    }
    return new ResolverPipeline(list);
  }

  public static ResolverPipeline none() {
    return new ResolverPipeline(List.of());
  }

  public ResolveReport run(MergeModel model) {
    return run(model, i -> false);
  }

  /** Like {@link #run(MergeModel)}, but leaves the blocks whose index {@code skip} accepts as they are. */
  public ResolveReport run(MergeModel model, java.util.function.IntPredicate skip) {
    List<Active> active = active();
    int resolved = 0;
    for (MergeBlock block : model.blocks()) {
      int i = block.index();
      if (!block.isConflict() || !model.isAutoResolvable(i) || skip.test(i)) {
        continue;
      }
      BlockContext ctx = new BlockContext(model, block, model.sideText(i, ThreeSide.LEFT), block.baseText(),
          model.sideText(i, ThreeSide.RIGHT), fileName);
      for (Active a : active) {
        ConflictResolver resolver = a.resolver();
        boolean exclusive = exclusive(resolver);
        if (a.broken()) {
          if (exclusive) {
            break; // it failed on the file name: as when isApplicable fails, the block stays a conflict
          }
          continue;
        }
        boolean claimed = false;
        String text = null;
        Strategy strategy = null;
        String id = null;
        String rationale = null;
        try {
          if (!resolver.isApplicable(ctx)) {
            continue;
          }
          claimed = exclusive;
          Optional<ConflictResolver.Resolution> res = resolver.tryResolve(ctx);
          if (res.isPresent()) {
            // everything a resolver says is asked here, so that a failing one leaves the block as it is
            strategy = resolver.strategy();
            id = resolver.id();
            rationale = res.get().rationale() != null ? res.get().rationale()
                : res.get().whitespaceIgnored() ? RATIONALE_WHITESPACE_IGNORED : null;
            text = terminate(res.get().text());
          }
        } catch (RuntimeException | LinkageError | StackOverflowError e) {
          // a contributed resolver may miss a class or recurse too deep: the block stays a conflict (an
          // OutOfMemoryError is not caught; it never becomes a result)
          warn(resolver, "failed on block " + i, e);
          claimed = exclusive; // also when isApplicable failed: it may be the format resolver of this block
          text = null;
        }
        if (text != null) {
          model.applyResolution(i, text, BlockState.AUTO_RESOLVED, strategy, id, rationale);
          resolved++;
          break;
        }
        if (claimed) {
          break; // an exclusive resolver that could not decide leaves the block a conflict
        }
      }
    }
    return new ResolveReport(resolved, model.unresolvedConflicts());
  }

  /** {@link ConflictResolver#exclusive}; one that fails to say counts as exclusive, so no text resolver guesses. */
  private static boolean exclusive(ConflictResolver resolver) {
    try {
      return resolver.exclusive();
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      warn(resolver, "failed to say whether it is exclusive", e);
      return true;
    }
  }

  /** The id of {@code resolver}, or its class name when it fails to say. */
  private static String name(ConflictResolver resolver) {
    try {
      return resolver.id();
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      return resolver.getClass().getName();
    }
  }

  /** Logs a failure of {@code resolver}; a StackOverflowError only by its message, without its deep stack. */
  private static void warn(ConflictResolver resolver, String what, Throwable e) {
    String message = "Resolver " + name(resolver) + " " + what;
    if (e instanceof VirtualMachineError) {
      LOG.warning(message + ": " + e);
    } else {
      LOG.log(Level.WARNING, message, e);
    }
  }

  private static String terminate(String chunk) {
    return chunk.isEmpty() || chunk.endsWith("\n") ? chunk : chunk + "\n";
  }
}
