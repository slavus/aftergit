package net.aftergit.merge.corpus;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.WordTokenizer;
import net.aftergit.merge.core.markers.CleanParity;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.members.MembersResolver;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MarkerStyle;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.ResultWriter;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;

/**
 * One conflicted file the way the resolver opens it (spec §4.3, §5.1 passes 0 to 2; a Java file is merged in segments
 * around its imports, located with the JDK parser), measured for the corpus.
 */
final class FileEvaluation {
  /** System property that switches "Newer version wins" on for the run (default off, as in the plugin). */
  static final String NEWER_VERSION = "aftergit.corpus.newerVersion";

  private FileEvaluation() {}

  /** The file with every resolver switch as by default in the plugin. */
  static FileResult evaluate(String repo, String merge, String path, String base, String ours, String theirs,
      String working, String committed, boolean javac) {
    return evaluate(repo, merge, path, base, ours, theirs, working, committed, javac, false);
  }

  /** Like the other, with "Newer version wins" switched on when {@code newerVersion}. */
  static FileResult evaluate(String repo, String merge, String path, String base, String ours, String theirs,
      String working, String committed, boolean javac, boolean newerVersion) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    ResolverPipeline pipeline = pipeline(newerVersion).forFile(name);
    InitResult init;
    try {
      init = HybridInitializer.initialize(new MergeInput(base, ours, theirs, working), ComparisonPolicy.DEFAULT,
          pipeline, name.endsWith(".java") ? JavacImportLocator.INSTANCE : null);
    } catch (RuntimeException | StackOverflowError e) {
      return FileResult.failed(repo, merge, path, e.toString());
    }
    MergeModel m = init.model();
    int auto = 0;
    int open = 0;
    int madeUp = 0;
    String firstMadeUp = null;
    Map<String, Integer> byResolver = new TreeMap<>();
    for (int i = 0; i < m.blocks().size(); i++) {
      MergeBlock b = m.blocks().get(i);
      if (b.isConflict() && b.state() == BlockState.AUTO_RESOLVED) {
        auto++;
        byResolver.merge(b.resolvedBy(), 1, Integer::sum);
        String token = madeUpToken(m.resultText(i), m.sideText(i, ThreeSide.LEFT), m.sideText(i, ThreeSide.BASE),
            m.sideText(i, ThreeSide.RIGHT));
        if (token != null) {
          madeUp++;
          if (firstMadeUp == null) {
            firstMadeUp = "'" + token + "' by " + b.resolvedBy() + " in the block at result line "
                + (b.resultStart() + 1);
          }
        }
      } else if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        open++;
      }
    }
    LineText w = LineText.of(working);
    String rendered = ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs",
        ConflictMarkerParser.DEFAULT_MARKER_SIZE, w.eol(), w.trailingNewline());
    boolean full = auto > 0 && open == 0;
    boolean checked = false;
    boolean noNew = false;
    boolean javaFailed = false;
    if (javac && full && path.endsWith(".java")) {
      try {
        Optional<Boolean> r = JavacCheck.noNewErrors(name, rendered, ours, theirs);
        checked = r.isPresent();
        noNew = r.orElse(false);
      } catch (RuntimeException e) {
        javaFailed = true; // the compiler itself failed or timed out: counted, no compile figure for this file
      }
    }
    boolean known = full && committed != null;
    boolean matches = known && LineText.of(committed).lines().equals(LineText.of(rendered).lines());
    CleanParity.Result parity = CleanParity.compare(parityText(m, rendered, w), working);
    return new FileResult(repo, merge, path, auto + open, auto, open, init.usedFallback(), parity.deviations(),
        parity.first(), checked, noNew, known, matches, null, javaFailed, byResolver, madeUp, firstMadeUp);
  }

  /**
   * The text compared with git's clean parts: {@code rendered}, but with the import blocks the imports resolver
   * resolved shown as conflicts again. That resolver merges the set of imports, not lines: an import both sides added
   * at different places is written once, so git's clean line may move into the block, which the parity check would
   * count as a deviation. The model is changed, so this comes last.
   */
  private static String parityText(MergeModel m, String rendered, LineText w) {
    boolean reverted = false;
    for (int i = 0; i < m.blocks().size(); i++) {
      MergeBlock b = m.blocks().get(i);
      if (b.isConflict() && b.state() == BlockState.AUTO_RESOLVED && b.region() == Region.IMPORTS) {
        m.revert(i);
        reverted = true;
      }
    }
    return reverted ? ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs",
        ConflictMarkerParser.DEFAULT_MARKER_SIZE, w.eol(), w.trailingNewline()) : rendered;
  }

  /**
   * The first compound token of {@code resolved} (letters and digits joined by '.', '-' or ':' without whitespace,
   * such as a version or a qualified name) that none of the sides has, or null.
   */
  static String madeUpToken(String resolved, String ours, String base, String theirs) {
    Set<String> known = new HashSet<>();
    for (String side : List.of(ours, base, theirs)) {
      known.addAll(WordTokenizer.texts(WordTokenizer.compounds(side)));
    }
    for (WordTokenizer.Token t : WordTokenizer.compounds(resolved)) {
      if (!known.contains(t.text())) {
        return t.text();
      }
    }
    return null;
  }

  /**
   * The resolvers of the plugin's default pipeline, with the JDK parser locating Java members (no greedy pass), each
   * switch as by default; {@code newerVersion} also switches "Newer version wins" on.
   */
  static ResolverPipeline pipeline(boolean newerVersion) {
    return ResolverPipeline.defaults(false,
        id -> ResolverPipeline.enabledByDefault(id) || (newerVersion && id.equals(NewerVersionResolver.ID)),
        List.of(new MembersResolver(JavacMemberLocator.INSTANCE)));
  }
}
