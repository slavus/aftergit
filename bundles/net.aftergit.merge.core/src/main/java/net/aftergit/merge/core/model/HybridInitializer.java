package net.aftergit.merge.core.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.diff.Diffs;
import net.aftergit.merge.core.diff.Range;
import net.aftergit.merge.core.imports.ImportLocator;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.imports.ImportSplit;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.markers.ParsedFile;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.resolve.ResolveReport;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;

/**
 * Builds the initial merge result (spec §4.3): git-equivalent non-conflicting changes applied,
 * auto-resolution, alignment with git's working-tree file (git wins on rerere-style
 * resolutions), fallback to a marker-based model when they disagree. In the marker fallback a side whose stage is
 * missing, or does not hold every hunk of that side, is built from the markers (spec 4.3.5).
 */
public final class HybridInitializer {
  // Distinct sentinels: a model conflict never "matches" a git hunk in the line diff, so a
  // conflict and its git hunk(s) always end up in the same differing region.
  static final String SENTINEL_MODEL = "\u0000aftergit-model-conflict\u0000";
  static final String SENTINEL_GIT = "\u0000aftergit-git-hunk\u0000";
  // An import block (spec §5.3) in any state: git's text there decides only when git merged it cleanly.
  static final String SENTINEL_IMPORTS = "\u0000aftergit-model-imports\u0000";

  private HybridInitializer() {}

  /**
   * @throws IllegalArgumentException when a stage is missing and there is no working-tree text either
   */
  public static InitResult initialize(MergeInput input, ComparisonPolicy policy, ResolverPipeline pipeline) {
    return initialize(input, policy, pipeline, null);
  }

  /**
   * Like {@link #initialize(MergeInput, ComparisonPolicy, ResolverPipeline)}; with {@code imports} the three texts
   * are merged in segments around their import sections (spec §5.3) when all three have one.
   *
   * @throws IllegalArgumentException when a stage is missing and there is no working-tree text either
   */
  public static InitResult initialize(MergeInput input, ComparisonPolicy policy, ResolverPipeline pipeline,
      ImportLocator imports) {
    LineText ours = LineText.of(orEmpty(input.ours()));
    LineText base = LineText.of(orEmpty(input.base()));
    LineText theirs = LineText.of(orEmpty(input.theirs()));
    if (input.hasMissingStage()) {
      if (input.workingTree() == null) {
        throw new IllegalArgumentException(
            "A merge stage (base, ours or theirs) is missing and there is no working-tree text to fall back to");
      }
      ParsedFile working = ConflictMarkerParser.parse(LineText.of(input.workingTree()).lines());
      return fallback(input, ours, base, theirs, working, pipeline, edits(input, ours, base, theirs, working));
    }
    ImportSections sections = imports == null ? null
        : ImportSplit.locate(imports, input.ours(), input.base(), input.theirs()).orElse(null);
    List<MergeChange> changes = sections == null ? LineMerger.merge(ours, base, theirs, policy)
        : ImportSplit.merge(ours, base, theirs, policy, sections);
    MergeModel model = MergeModel.fromChanges(ours, base, theirs, changes);
    ResolveReport report = pipeline.run(model);
    if (input.workingTree() == null) {
      return new InitResult(model, false, 0, report, sections);
    }
    ParsedFile working = ConflictMarkerParser.parse(LineText.of(input.workingTree()).lines());
    List<int[]> gitResolved = new ArrayList<>(); // {blockIndex, g0, g1}
    List<int[]> pairs = new ArrayList<>(); // {blockIndex, hunkIndex} for 1:1 conflict/hunk regions
    List<int[]> edits = new ArrayList<>(); // {blockIndex, relStart, relEnd, g0, g1}, offsets relative to block start
    List<String> g = working.linesWithSentinels(SENTINEL_GIT);
    int[] handEdits = edits(input, ours, base, theirs, working);
    if (align(model, g, handEdits, gitResolved, edits, pairs)) {
      List<ParsedFile.Hunk> hunks = working.hunks();
      for (int[] p : pairs) {
        ParsedFile.Hunk h = hunks.get(p[1]);
        String oursChunk = chunkOf(h.ours(), 0, h.ours().size());
        String theirsChunk = chunkOf(h.theirs(), 0, h.theirs().size());
        boolean differs = !oursChunk.equals(model.sideText(p[0], ThreeSide.LEFT))
            || !theirsChunk.equals(model.sideText(p[0], ThreeSide.RIGHT));
        if (differs) {
          model.blocks().get(p[0]).setSideOverrides(oursChunk, theirsChunk);
        }
      }
      for (int k = gitResolved.size() - 1; k >= 0; k--) {
        int[] e = gitResolved.get(k);
        model.resolveByGit(e[0], chunkOf(g, e[1], e[2]));
      }
      int applied = gitResolved.size() + applyEdits(model, g, edits);
      return new InitResult(model, false, applied, new ResolveReport(countAutoResolved(model),
          model.unresolvedConflicts()), sections);
    }
    return fallback(input, ours, base, theirs, working, pipeline, handEdits);
  }

  /** A hunk's ours or theirs section is not the text of that stage: edited by hand inside git's markers. */
  static final int EDITED_SIDES = 1;
  /** A hunk's diff3 base section is not the text of the base stage. */
  static final int EDITED_BASE = 2;

  /**
   * For each hunk of {@code working}: which of its sections are no longer the text of their stage (bits
   * {@link #EDITED_SIDES}, {@link #EDITED_BASE}). git writes every section as a slice of its stage, in order, so a
   * section the stage does not hold at that point was edited by hand after the merge. A missing stage tells nothing.
   */
  private static int[] edits(MergeInput input, LineText ours, LineText base, LineText theirs, ParsedFile working) {
    List<ParsedFile.Hunk> hunks = working.hunks();
    int[] result = new int[hunks.size()];
    int[] cursors = new int[3];
    for (int i = 0; i < hunks.size(); i++) {
      ParsedFile.Hunk h = hunks.get(i);
      boolean oursEdited = input.ours() != null && !advance(ours, h.ours(), cursors, 0);
      boolean theirsEdited = input.theirs() != null && !advance(theirs, h.theirs(), cursors, 2);
      if (oursEdited || theirsEdited) {
        result[i] |= EDITED_SIDES;
      }
      if (h.base() != null && input.base() != null && !advance(base, h.base(), cursors, 1)) {
        result[i] |= EDITED_BASE;
      }
    }
    return result;
  }

  /** Finds {@code needle} in {@code side} from {@code cursors[k]} and moves that cursor past it; false if absent. */
  private static boolean advance(LineText side, List<String> needle, int[] cursors, int k) {
    int at = find(side.lines(), needle, cursors[k]);
    if (!needle.isEmpty() && !matches(side.lines(), needle, at)) {
      return false;
    }
    cursors[k] = at + needle.size();
    return true;
  }

  /** Any hunk among those of {@code g[g0, g1)} with one of the {@code bits} edited. */
  private static boolean edited(int[] handEdits, List<String> g, int g0, int g1, int bits) {
    int hunk = countSentinels(g, g0);
    for (int i = g0; i < g1; i++) {
      if (SENTINEL_GIT.equals(g.get(i))) {
        if ((handEdits[hunk] & bits) != 0) {
          return true;
        }
        hunk++;
      }
    }
    return false;
  }

  /** Blocks whose auto-resolution survived the git overrides. */
  private static int countAutoResolved(MergeModel model) {
    int n = 0;
    for (MergeBlock b : model.blocks()) {
      if (b.state() == BlockState.AUTO_RESOLVED) {
        n++;
      }
    }
    return n;
  }

  /** Applies working-tree text inside non-conflicting or auto-resolved blocks; returns the number of blocks changed. */
  private static int applyEdits(MergeModel model, List<String> g, List<int[]> edits) {
    java.util.TreeMap<Integer, List<int[]>> byBlock = new java.util.TreeMap<>();
    for (int[] e : edits) {
      byBlock.computeIfAbsent(e[0], x -> new ArrayList<>()).add(e);
    }
    for (var entry : byBlock.entrySet()) {
      int k = entry.getKey();
      MergeBlock b = model.blocks().get(k);
      List<String> lines = new ArrayList<>();
      for (int i = b.resultStart(); i < b.resultEnd(); i++) {
        lines.add(lineAt(model.document(), i));
      }
      List<int[]> list = entry.getValue();
      list.sort((x, y) -> Integer.compare(y[1], x[1]));
      for (int[] e : list) {
        List<String> head = new ArrayList<>(lines.subList(0, e[1]));
        head.addAll(g.subList(e[3], e[4]));
        head.addAll(lines.subList(e[2], lines.size()));
        lines = head;
      }
      String chunk = chunkOf(lines, 0, lines.size());
      if (b.isConflict()) {
        model.resolveByGit(k, chunk);
      } else {
        model.applyResolution(k, chunk, BlockState.RESOLVED_BY_GIT, Strategy.DEFAULT, "git", null);
      }
    }
    return byBlock.size();
  }

  /**
   * Returns false on any disagreement other than the tolerated ones, and wherever a rule would put our text in place
   * of a git hunk whose sections were edited by hand ({@code handEdits}): the marker fallback keeps those edits.
   */
  private static boolean align(MergeModel model, List<String> g, int[] handEdits, List<int[]> gitResolved,
                              List<int[]> edits, List<int[]> pairs) {
    List<String> c = new ArrayList<>();
    List<Integer> owner = new ArrayList<>(); // block index for each line of c, -1 outside blocks
    List<Integer> docLine = new ArrayList<>(); // document line for each line of c, -1 for sentinels
    ResultDocument doc = model.document();
    int line = 0;
    for (MergeBlock b : model.blocks()) {
      for (; line < b.resultStart(); line++) {
        c.add(lineAt(doc, line));
        owner.add(-1);
        docLine.add(line);
      }
      if (b.isConflict() && b.region() == Region.IMPORTS) {
        c.add(SENTINEL_IMPORTS);
        owner.add(b.index());
        docLine.add(-1);
      } else if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        c.add(SENTINEL_MODEL);
        owner.add(b.index());
        docLine.add(-1);
      } else {
        for (int k = b.resultStart(); k < b.resultEnd(); k++) {
          c.add(lineAt(doc, k));
          owner.add(b.index());
          docLine.add(k);
        }
      }
      line = b.resultEnd();
    }
    for (; line < doc.lineCount(); line++) {
      c.add(lineAt(doc, line));
      owner.add(-1);
      docLine.add(line);
    }

    List<Range> unchanged = new ArrayList<>(Diffs.unchanged(c, g));
    unchanged.add(new Range(c.size(), c.size(), g.size(), g.size()));
    int ic = 0;
    int ig = 0;
    for (Range u : unchanged) {
      int c0 = ic, c1 = u.start1(), g0 = ig, g1 = u.start2();
      if (c1 > c0 || g1 > g0) {
        boolean cIsImports = c1 - c0 == 1 && SENTINEL_IMPORTS.equals(c.get(c0));
        boolean cIsSentinel = c1 - c0 == 1 && SENTINEL_MODEL.equals(c.get(c0));
        boolean gHasSentinel = g.subList(g0, g1).contains(SENTINEL_GIT);
        boolean cHasSentinel = c.subList(c0, c1).stream().anyMatch(HybridInitializer::isModelSentinel);
        boolean gIsSentinel = g1 - g0 == 1 && SENTINEL_GIT.equals(g.get(g0));
        // a 1:1 pair keeps edited ours/theirs sections as overrides, but not an edited base section
        int kept = cIsSentinel && gIsSentinel ? EDITED_BASE : EDITED_SIDES | EDITED_BASE;
        if (gHasSentinel && edited(handEdits, g, g0, g1, kept)) {
          return false;
        }
        if (cIsImports && !gHasSentinel) {
          // git merged the imports cleanly: git wins. A git hunk over the imports that the line diff pushed into
          // the neighbouring region leaves an empty model side there; no rule takes that, the alignment falls back
          // and nothing recorded here is applied.
          gitResolved.add(new int[] {owner.get(c0), g0, g1});
        } else if (cIsImports) {
          // git left markers in the imports: our import resolution (or the open import block) stays
        } else if (cIsSentinel && !gHasSentinel) {
          gitResolved.add(new int[] {owner.get(c0), g0, g1});
        } else if (gIsSentinel && !cHasSentinel && c1 > c0 && singleAutoResolvedOwner(model, owner, c0, c1)) {
          // we resolved what git could not - keep ours
        } else if (gIsSentinel && !cHasSentinel && c1 > c0 && autoResolvedRun(model, owner, c0, c1)) {
          // generalises the single-owner case: one git hunk over several of our blocks, none open - keep ours
        } else if (gIsSentinel && !cHasSentinel && c1 > c0 && splitAtImportBoundary(model, owner, docLine, c0, c1)) {
          // git's conflict straddles an import-segment boundary, the split merged both parts cleanly - keep ours
        } else if (gIsSentinel && c1 == c0 && deletionsAtImportBoundary(model, c, docLine, c0)) {
          // git's conflict between two adjacent deletions (ours the last import, theirs the line after it) is
          // an empty spot in our text: the split applied both deletions - keep ours
        } else if (cIsSentinel && gIsSentinel) {
          pairs.add(new int[] {owner.get(c0), countSentinels(g, g0)});
        } else if (cHasSentinel && gHasSentinel && sharedContextOnly(model, c, owner, c0, c1, g, g0, g1)) {
          // same conflict(s), different granularity: git's "zealous" level splits a conflict around
          // lines both sides share - keep our blocks
        } else if (!cHasSentinel && !gHasSentinel && addEdit(model, owner, docLine, c0, c1, g0, g1, edits)) {
          // git's text differs from our block content - git wins inside that block
        } else {
          return false;
        }
      }
      ic = u.end1();
      ig = u.end2();
    }
    return true;
  }

  private static boolean isModelSentinel(String line) {
    return SENTINEL_MODEL.equals(line) || SENTINEL_IMPORTS.equals(line);
  }

  /** Number of git-hunk sentinels before index {@code end} = index of the hunk at {@code end}. */
  private static int countSentinels(List<String> g, int end) {
    int n = 0;
    for (int i = 0; i < end; i++) {
      if (SENTINEL_GIT.equals(g.get(i))) {
        n++;
      }
    }
    return n;
  }

  private static boolean addEdit(MergeModel model, List<Integer> owner, List<Integer> docLine, int c0, int c1,
                                 int g0, int g1, List<int[]> edits) {
    int k;
    int docStart;
    int docEnd;
    if (c1 > c0) {
      k = owner.get(c0);
      if (k < 0) {
        return false;
      }
      for (int i = c0; i < c1; i++) {
        if (owner.get(i) != k) {
          return false;
        }
      }
      docStart = docLine.get(c0);
      docEnd = docLine.get(c1 - 1) + 1;
    } else {
      // pure insertion in git's text: only inside a block, between two lines of the same block
      if (c0 == 0 || owner.get(c0 - 1) < 0) {
        return false;
      }
      k = owner.get(c0 - 1);
      if (c0 >= owner.size() || owner.get(c0) != k) {
        return false;
      }
      docStart = docLine.get(c0 - 1) + 1;
      docEnd = docStart;
    }
    int base = model.blocks().get(k).resultStart();
    edits.add(new int[] {k, docStart - base, docEnd - base, g0, g1});
    return true;
  }

  /**
   * Every clean line git put between its hunks must be a line both sides of our conflict(s) contain.
   * Only git's clean lines are checked: the non-sentinel lines on our side of the region (text of our
   * own blocks or context between them) are not compared with anything.
   */
  private static boolean sharedContextOnly(MergeModel model, List<String> c, List<Integer> owner, int c0, int c1,
                                           List<String> g, int g0, int g1) {
    Set<String> shared = new HashSet<>();
    for (int k = c0; k < c1; k++) {
      if (isModelSentinel(c.get(k))) {
        int block = owner.get(k);
        Set<String> both = new HashSet<>(LineText.chunkLines(model.sideText(block, ThreeSide.LEFT)));
        both.retainAll(LineText.chunkLines(model.sideText(block, ThreeSide.RIGHT)));
        shared.addAll(both);
      }
    }
    for (int k = g0; k < g1; k++) {
      String line = g.get(k);
      if (!SENTINEL_GIT.equals(line) && !shared.contains(line)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Git's conflict hunk straddles an import-segment boundary (spec §5.3) that the segment merge resolved: every line
   * of our side lies in a block that is not open, and the blocks touching the region lie on both sides of the
   * boundary. Touching are the owners of the lines, the blocks of the lines right next to the region (the line diff
   * may have matched them to git's clean lines) and the zero-width blocks at the region's edges (deletions).
   */
  private static boolean splitAtImportBoundary(MergeModel model, List<Integer> owner, List<Integer> docLine,
      int from, int to) {
    boolean imports = false;
    boolean body = false;
    for (int k = from; k < to; k++) {
      int o = owner.get(k);
      if (o < 0) {
        return false;
      }
      MergeBlock b = model.blocks().get(o);
      if (b.isConflict() && b.state() != BlockState.AUTO_RESOLVED) {
        return false;
      }
      imports |= b.region() == Region.IMPORTS;
      body |= b.region() == Region.BODY;
    }
    Set<Integer> near = new HashSet<>();
    if (from > 0 && owner.get(from - 1) >= 0) {
      near.add(owner.get(from - 1));
    }
    if (to < owner.size() && owner.get(to) >= 0) {
      near.add(owner.get(to));
    }
    // the region holds no sentinel, so its document lines are [docLine(from), docLine(to - 1)] + 1
    int docFrom = docLine.get(from);
    int docTo = docLine.get(to - 1) + 1;
    for (MergeBlock b : model.blocks()) {
      if (b.resultStart() == b.resultEnd() && b.resultStart() >= docFrom && b.resultStart() <= docTo) {
        near.add(b.index());
      }
    }
    for (int o : near) {
      Region r = model.blocks().get(o).region();
      imports |= r == Region.IMPORTS;
      body |= r == Region.BODY;
    }
    return imports && body;
  }

  /**
   * The empty spot before line {@code at} of {@code c}, where git has a conflict: the zero-width blocks there (the
   * deletions of both sides) reach the imports and the body, and none of them is open.
   */
  private static boolean deletionsAtImportBoundary(MergeModel model, List<String> c, List<Integer> docLine, int at) {
    if (at > 0 && docLine.get(at - 1) < 0 || at < c.size() && docLine.get(at) < 0) {
      return false;
    }
    int spot = at > 0 ? docLine.get(at - 1) + 1 : 0;
    boolean imports = false;
    boolean body = false;
    for (MergeBlock b : model.blocks()) {
      if (b.resultStart() == spot && b.resultEnd() == spot) {
        if (b.isConflict() && b.state() != BlockState.AUTO_RESOLVED) {
          return false;
        }
        imports |= b.region() == Region.IMPORTS;
        body |= b.region() == Region.BODY;
      }
    }
    return imports && body;
  }

  /**
   * Every line of the region is context or belongs to a block that is no open conflict, and at least one of those
   * blocks is auto-resolved: git conflicted over several changes that the resolvers took apart (spec 4.3.4).
   */
  private static boolean autoResolvedRun(MergeModel model, List<Integer> owner, int from, int to) {
    boolean auto = false;
    for (int k = from; k < to; k++) {
      int o = owner.get(k);
      if (o < 0) {
        continue;
      }
      MergeBlock b = model.blocks().get(o);
      if (b.isConflict() && b.state() != BlockState.AUTO_RESOLVED) {
        return false;
      }
      auto |= b.state() == BlockState.AUTO_RESOLVED;
    }
    return auto;
  }

  private static boolean singleAutoResolvedOwner(MergeModel model, List<Integer> owner, int from, int to) {
    int first = owner.get(from);
    if (first < 0 || model.blocks().get(first).state() != BlockState.AUTO_RESOLVED) {
      return false;
    }
    for (int k = from; k < to; k++) {
      if (owner.get(k) != first) {
        return false;
      }
    }
    return true;
  }

  /** {@code handEdits}: see {@link #edits}; a block of an edited hunk stays open for the user to decide. */
  private static InitResult fallback(MergeInput input, LineText ours, LineText base, LineText theirs,
                                     ParsedFile working, ResolverPipeline pipeline, int[] handEdits) {
    List<ParsedFile.Hunk> hunks = working.hunks();
    Set<ThreeSide> fromMarkers = EnumSet.noneOf(ThreeSide.class);
    boolean leftFromMarkers = input.ours() == null || !holdsAll(ours, hunks, true);
    boolean rightFromMarkers = input.theirs() == null || !holdsAll(theirs, hunks, false);
    LineText left = ours;
    LineText right = theirs;
    if (leftFromMarkers) {
      left = sideOfMarkers(working, true);
      fromMarkers.add(ThreeSide.LEFT);
    }
    if (rightFromMarkers) {
      right = sideOfMarkers(working, false);
      fromMarkers.add(ThreeSide.RIGHT);
    }
    List<String> result = new ArrayList<>();
    List<MergeBlock> blocks = new ArrayList<>();
    // a stage side is searched from the cursor; a side built from the markers has every hunk exactly at the cursor
    int oursCursor = 0;
    int theirsCursor = 0;
    for (ParsedFile.Segment s : working.segments()) {
      if (s instanceof ParsedFile.Clean clean) {
        result.addAll(clean.lines());
        if (leftFromMarkers) {
          oursCursor += clean.lines().size();
        }
        if (rightFromMarkers) {
          theirsCursor += clean.lines().size();
        }
        continue;
      }
      ParsedFile.Hunk h = (ParsedFile.Hunk) s;
      int oStart = leftFromMarkers ? oursCursor : find(left.lines(), h.ours(), oursCursor);
      int tStart = rightFromMarkers ? theirsCursor : find(right.lines(), h.theirs(), theirsCursor);
      int oEnd = oStart + h.ours().size();
      int tEnd = tStart + h.theirs().size();
      List<String> baseLines = h.base() == null ? List.of() : h.base();
      int start = result.size();
      result.addAll(baseLines);
      MergeRange source = new MergeRange(oStart, oEnd, 0, 0, tStart, tEnd);
      MergeBlock block = new MergeBlock(blocks.size(), source,
          new ConflictType(ConflictType.Kind.CONFLICT, true, true),
          LineText.ofLines(baseLines).chunk(0, baseLines.size()), start, result.size(),
          BlockState.UNRESOLVED, Strategy.NONE, null);
      String oursChunk = chunkOf(h.ours(), 0, h.ours().size());
      String theirsChunk = chunkOf(h.theirs(), 0, h.theirs().size());
      if (!oursChunk.equals(left.chunk(oStart, oEnd)) || !theirsChunk.equals(right.chunk(tStart, tEnd))) {
        block.setSideOverrides(oursChunk, theirsChunk); // defensive: a side always holds its hunks by now
      }
      blocks.add(block);
      oursCursor = oEnd;
      theirsCursor = tEnd;
    }
    MergeModel model = new MergeModel(left, base, right, new LinesResultDocument(result), blocks);
    ResolveReport report = pipeline.run(model, i -> handEdits[i] != 0); // one block per hunk, in order
    return new InitResult(model, true, 0, report, null, fromMarkers);
  }

  /** Every hunk's side (ours or theirs) occurs in {@code side}, in order and without overlap. */
  private static boolean holdsAll(LineText side, List<ParsedFile.Hunk> hunks, boolean ours) {
    int cursor = 0;
    for (ParsedFile.Hunk h : hunks) {
      List<String> needle = ours ? h.ours() : h.theirs();
      int at = find(side.lines(), needle, cursor);
      if (!needle.isEmpty() && !matches(side.lines(), needle, at)) {
        return false;
      }
      cursor = at + needle.size();
    }
    return true;
  }

  /** One side as git's markers show it: the clean text and that side of every hunk. */
  private static LineText sideOfMarkers(ParsedFile working, boolean ours) {
    List<String> lines = new ArrayList<>();
    for (ParsedFile.Segment s : working.segments()) {
      if (s instanceof ParsedFile.Clean c) {
        lines.addAll(c.lines());
      } else {
        ParsedFile.Hunk h = (ParsedFile.Hunk) s;
        lines.addAll(ours ? h.ours() : h.theirs());
      }
    }
    return LineText.ofLines(lines);
  }

  /** First index >= from where {@code needle} occurs in {@code hay}; {@code from} if not found. */
  private static int find(List<String> hay, List<String> needle, int from) {
    if (needle.isEmpty()) {
      return Math.min(from, hay.size());
    }
    for (int i = from; i + needle.size() <= hay.size(); i++) {
      if (matches(hay, needle, i)) {
        return i;
      }
    }
    return Math.min(from, hay.size());
  }

  private static boolean matches(List<String> hay, List<String> needle, int at) {
    if (at + needle.size() > hay.size()) {
      return false;
    }
    return hay.subList(at, at + needle.size()).equals(needle);
  }

  private static String orEmpty(String text) {
    return text == null ? "" : text;
  }

  private static String lineAt(ResultDocument doc, int line) {
    String chunk = doc.chunk(line, line + 1);
    return chunk.substring(0, chunk.length() - 1);
  }

  private static String chunkOf(List<String> lines, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }
}
