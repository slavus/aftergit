package net.aftergit.merge.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.text.LineText;

/**
 * Merge state: three inputs, the result document and ordered, non-overlapping blocks.
 * All mutations of block text go through this class so block positions stay correct.
 */
public final class MergeModel {
  private final LineText left;
  private final LineText base;
  private final LineText right;
  private final List<MergeBlock> blocks;
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private ResultDocument document;
  private boolean applyingOwnEdit;
  private boolean formattingElsewhere;

  public MergeModel(LineText left, LineText base, LineText right, ResultDocument document, List<MergeBlock> blocks) {
    this.left = left;
    this.base = base;
    this.right = right;
    this.document = document;
    this.blocks = List.copyOf(blocks);
  }

  /** Result = base with every non-conflicting change applied; conflicts keep base text. */
  public static MergeModel fromChanges(LineText left, LineText base, LineText right, List<MergeChange> changes) {
    List<String> result = new ArrayList<>();
    List<MergeBlock> blocks = new ArrayList<>();
    int baseIndex = 0;
    for (MergeChange change : changes) {
      MergeRange r = change.range();
      for (; baseIndex < r.start2(); baseIndex++) {
        result.add(base.line(baseIndex));
      }
      String baseChunk = base.chunk(r.start2(), r.end2());
      int start = result.size();
      if (change.type().isConflict()) {
        result.addAll(base.lines().subList(r.start2(), r.end2()));
        blocks.add(new MergeBlock(blocks.size(), r, change.type(), change.region(), baseChunk, start, result.size(),
            BlockState.UNRESOLVED, Strategy.NONE, null));
      } else {
        LineText source = change.type().leftChanged() ? left : right;
        int from = change.type().leftChanged() ? r.start1() : r.start3();
        int to = change.type().leftChanged() ? r.end1() : r.end3();
        result.addAll(source.lines().subList(from, to));
        blocks.add(new MergeBlock(blocks.size(), r, change.type(), change.region(), baseChunk, start, result.size(),
            BlockState.RESOLVED_BY_GIT, Strategy.DEFAULT, "git"));
      }
      baseIndex = r.end2();
    }
    for (; baseIndex < base.size(); baseIndex++) {
      result.add(base.line(baseIndex));
    }
    return new MergeModel(left, base, right, new LinesResultDocument(result), blocks);
  }

  public List<MergeBlock> blocks() { return blocks; }
  public ResultDocument document() { return document; }
  public LineText left() { return left; }
  public LineText base() { return base; }
  public LineText right() { return right; }

  /** Text of one side of a block: the block's own side text when present, else the stage slice. */
  public String sideText(int block, ThreeSide side) {
    MergeBlock b = blocks.get(block);
    if (side == ThreeSide.LEFT && b.leftOverride() != null) {
      return b.leftOverride();
    }
    if (side == ThreeSide.RIGHT && b.rightOverride() != null) {
      return b.rightOverride();
    }
    MergeRange r = b.source();
    return side.select(left, base, right).chunk(r.start(side), r.end(side));
  }

  public String resultText(int block) {
    MergeBlock b = blocks.get(block);
    return document.chunk(b.resultStart(), b.resultEnd());
  }

  /** Unresolved and still holding exactly its base text (no manual edits). */
  public boolean isAutoResolvable(int block) {
    MergeBlock b = blocks.get(block);
    return b.state() == BlockState.UNRESOLVED && resultText(block).equals(b.baseText());
  }

  public void take(int block, ThreeSide side) {
    if (side == ThreeSide.BASE) {
      throw new IllegalArgumentException("take(BASE) - use revert()");
    }
    MergeBlock b = blocks.get(block);
    BlockState other = side == ThreeSide.LEFT ? BlockState.TAKEN_RIGHT : BlockState.TAKEN_LEFT;
    if (b.isConflict() && b.state() == other) {
      replace(block, resultText(block) + sideText(block, side));
      b.setResolution(BlockState.TAKEN_BOTH, b.strategy(), null, false, null);
    } else {
      replace(block, sideText(block, side));
      b.setResolution(side == ThreeSide.LEFT ? BlockState.TAKEN_LEFT : BlockState.TAKEN_RIGHT,
          b.strategy(), null, false, null);
    }
    fire();
  }

  public void takeBoth(int block, ThreeSide first) {
    ThreeSide second = first == ThreeSide.LEFT ? ThreeSide.RIGHT : ThreeSide.LEFT;
    replace(block, sideText(block, first) + sideText(block, second));
    MergeBlock b = blocks.get(block);
    b.setResolution(BlockState.TAKEN_BOTH, b.strategy(), null, false, null);
    fire();
  }

  public void ignore(int block) {
    MergeBlock b = blocks.get(block);
    b.setResolution(BlockState.IGNORED, b.strategy(), null, false, null);
    fire();
  }

  /**
   * Puts the block's base text back and makes it unresolved. The block's lines are replaced as they are now: after a
   * derived change that the block took over whole (see {@link #applyDerivedText}), that includes the lines of the
   * change outside the block's original lines, which go too.
   */
  public void revert(int block) {
    MergeBlock b = blocks.get(block);
    replace(block, b.baseText());
    b.setResolution(BlockState.UNRESOLVED, Strategy.NONE, null, false, null);
    fire();
  }

  public void applyResolution(int block, String chunk, BlockState state, Strategy strategy,
                              String resolvedBy, String rationale) {
    replace(block, chunk);
    blocks.get(block).setResolution(state, strategy, resolvedBy, true, rationale);
    fire();
  }

  public void resolveByGit(int block, String chunk) {
    replace(block, chunk);
    blocks.get(block).setResolution(BlockState.RESOLVED_BY_GIT, Strategy.DEFAULT, "git", false, null);
    fire();
  }

  public void markReviewed(int block) {
    blocks.get(block).setNeedsReview(false);
    fire();
  }

  /**
   * Must be called by the owner of the document after a user edit, expressed in whole lines of the
   * {@link ResultDocument} line model: the lines [startLine, endLine) of the old text were replaced
   * by {@code newLineCount} lines.
   *
   * <p>A character-level edit maps to these values as follows: {@code startLine} is the line of the
   * edit start; {@code endLine} is 1 + the line of the edit end in the old text (so the range covers
   * every line the edit touched); {@code newLineCount} is the number of lines those touched lines
   * became. Typing inside one line is {@code (l, l + 1, 1)}; inserting {@code k} whole lines at column 0
   * before line {@code p} (between lines) is {@code (p, p, k)}; deleting whole lines [a, b) is
   * {@code (a, b, 0)}.
   *
   * <p>Blocks touched by the edit become {@link BlockState#EDITED}. A pure insertion exactly at the
   * start or end of a non-empty block belongs to the neighbour, not to the block; a pure insertion at
   * the position of an empty conflict block is attributed to the first such block, which then holds
   * the inserted lines.
   *
   * <p>Ignored while {@link #isApplyingOwnEdit()} is true: the model applies its own edits through
   * {@link ResultDocument#replaceLines} and already accounts for them.
   */
  public void onExternalEdit(int startLine, int endLine, int newLineCount) {
    if (applyingOwnEdit) {
      return;
    }
    externalEdit(startLine, endLine, newLineCount);
    fire();
  }

  /**
   * Like {@link #onExternalEdit}, for a user edit whose old and new lines are known: lines [startLine, startLine +
   * oldLines.size()) became {@code newLines} (model lines, without '\n'). Only lines that really differ count (see
   * {@link LineEdits}), so a formatter or a paste that rewrites a span of lines leaves every block on its own lines,
   * and a block becomes {@link BlockState#EDITED} only when one of its own lines changed.
   *
   * <p>A change of whitespace only ({@link ComparisonPolicy#IGNORE_WHITESPACES}), line for line with as many lines as
   * before, keeps the state and flags of an {@link BlockState#UNRESOLVED} block, so reindenting or respacing the lines
   * of a conflict never resolves it. Another block keeps them only while {@link #setFormattingElsewhere} is on (another
   * editor's save formats the file); otherwise such a change makes it EDITED, as whitespace can carry meaning (a
   * Python indent, a string literal). A formatting that wraps or joins lines, or adds or removes a blank line, changes
   * the line count and still makes the blocks it touches EDITED.
   *
   * <p>Otherwise a replacement of fewer than two old lines is exactly {@link #onExternalEdit}. Listeners are told
   * once, also when no model line changed (an edit that only adds or removes the final line end, for example).
   *
   * <p>The diff sees lines, not the offsets of the edit, so runs of identical lines make it ambiguous: deleting one of
   * two equal adjacent lines is reported where the diff aligns it, which may be the neighbour of the line the user
   * removed, and then that neighbour's block becomes {@link BlockState#EDITED} instead. The text is always right; only
   * which block the change is attributed to can differ.
   */
  public void onExternalReplace(int startLine, List<String> oldLines, List<String> newLines) {
    if (applyingOwnEdit) {
      return;
    }
    if (oldLines.size() < 2) {
      if (oldLines.size() == 1 && newLines.size() == 1 && whitespaceOnly(oldLines, newLines)
          && keepsStates(startLine, startLine + 1)) {
        fire(); // a line reindented in place: no block moves, none changes its state
        return;
      }
      onExternalEdit(startLine, startLine + oldLines.size(), newLines.size());
      return;
    }
    List<LineEdits.Hunk> hunks = LineEdits.between(oldLines, newLines);
    for (int k = hunks.size() - 1; k >= 0; k--) { // bottom up: the hunks above keep their line numbers
      LineEdits.Hunk h = hunks.get(k);
      if (h.end() - h.start() == h.newEnd() - h.newStart()
          && whitespaceOnly(oldLines.subList(h.start(), h.end()), newLines.subList(h.newStart(), h.newEnd()))
          && keepsStates(startLine + h.start(), startLine + h.end())) {
        continue; // as many lines as before, changed in whitespace only: ranges stay, states and flags too
      }
      externalEdit(startLine + h.start(), startLine + h.end(), h.newEnd() - h.newStart());
    }
    fire();
  }

  /**
   * Whether a whitespace-only change of lines [from, to) leaves the blocks there as they are: always for unresolved
   * blocks, for the others only while another editor formats the file.
   */
  private boolean keepsStates(int from, int to) {
    if (formattingElsewhere) {
      return true;
    }
    for (MergeBlock b : blocks) {
      if (b.resultStart() < to && b.resultEnd() > from && b.state() != BlockState.UNRESOLVED) {
        return false;
      }
    }
    return true;
  }

  /**
   * On while another editor's save may format the result (its save actions): a change of whitespace only then keeps
   * the state of every block, see {@link #onExternalReplace}.
   */
  public void setFormattingElsewhere(boolean on) {
    formattingElsewhere = on;
  }

  /** The lines are equal but for whitespace ({@link ComparisonPolicy#IGNORE_WHITESPACES}), pair by pair. */
  private static boolean whitespaceOnly(List<String> a, List<String> b) {
    for (int i = 0; i < a.size(); i++) {
      if (!ComparisonPolicy.IGNORE_WHITESPACES.equalTexts(a.get(i), b.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Makes the result hold {@code chunk} as the consequence of another change (an import rewrite after a Take, spec
   * §5.3): only the lines that differ change, blocks move around them and keep their states. A block that holds a
   * changed run grows or shrinks with it. A non-empty block that a changed run only partly overlaps, or covers
   * entirely, grows over the whole run, so it holds the lines that replaced its own and a later Revert replaces them
   * instead of inserting the base text next to them (a Revert then replaces the whole run, also lines of it that were
   * outside the block before); when one run covers lines of several blocks, the first of them
   * takes the run, and the others, whose lines went into it, become {@link BlockState#EDITED}. Lines inserted right
   * before or after an auto-resolved block of imports join it (an import the body needs belongs to the resolved
   * imports: it is shown with them and goes when they are reverted). Nothing happens when the texts are equal.
   */
  public void applyDerivedText(String chunk) {
    List<String> oldLines = LineText.chunkLines(document.chunk(0, document.lineCount()));
    List<String> newLines = LineText.chunkLines(chunk);
    List<LineEdits.Hunk> hunks = LineEdits.between(oldLines, newLines);
    if (hunks.isEmpty()) {
      return;
    }
    for (int k = hunks.size() - 1; k >= 0; k--) {
      LineEdits.Hunk h = hunks.get(k);
      boolean outer = applyingOwnEdit;
      applyingOwnEdit = true;
      try {
        document.replaceLines(h.start(), h.end(), chunkOf(newLines, h.newStart(), h.newEnd()));
      } finally {
        applyingOwnEdit = outer;
      }
      int shift = (h.newEnd() - h.newStart()) - (h.end() - h.start());
      int runEnd = h.newEnd();
      boolean taken = false;
      MergeBlock importsGrow = h.start() == h.end() ? autoResolvedImportsAt(h.start()) : null;
      boolean afterGrow = false;
      for (MergeBlock b : blocks) {
        if (b == importsGrow) {
          b.setResult(b.resultStart(), b.resultEnd() + shift); // the new lines join the auto-resolved imports
          afterGrow = true;
          continue;
        }
        if (afterGrow && b.resultStart() == h.start() && b.resultEnd() == h.start()) {
          b.setResult(h.start() + shift, h.start() + shift); // an empty block after the imports stays after them
          continue;
        }
        boolean nonEmpty = b.resultStart() < b.resultEnd();
        RangeUpdater.Updated u = RangeUpdater.update(b.resultStart(), b.resultEnd(), h.start(), h.end(), shift);
        if (!u.damaged()) {
          b.setResult(u.start(), u.end());
        } else if (!nonEmpty) {
          int at = taken ? u.start() : h.start(); // an empty block inside the run stays in order around its taker
          b.setResult(at, at);
        } else if (!taken) {
          taken = true; // the first block the run cuts into takes the whole run
          b.setResult(Math.min(u.start(), h.start()), Math.max(u.end(), runEnd));
        } else {
          b.setResult(u.start(), u.end()); // its lines went into the run, which the first block holds
          b.markEdited();
        }
      }
    }
    fire();
  }

  /** A non-empty auto-resolved block of imports that starts or ends at {@code line}, else null. */
  private MergeBlock autoResolvedImportsAt(int line) {
    for (MergeBlock b : blocks) {
      if (b.region() == Region.IMPORTS && b.state() == BlockState.AUTO_RESOLVED && b.resultStart() < b.resultEnd()
          && (b.resultEnd() == line || b.resultStart() == line)) {
        return b;
      }
    }
    return null;
  }

  /** The result text with every auto-resolved block holding its base text: the result before auto-resolution. */
  public String textBeforeAutoResolution() {
    StringBuilder sb = new StringBuilder();
    int line = 0;
    for (MergeBlock b : blocks) {
      sb.append(document.chunk(line, b.resultStart()));
      sb.append(b.state() == BlockState.AUTO_RESOLVED ? b.baseText()
          : document.chunk(b.resultStart(), b.resultEnd()));
      line = b.resultEnd();
    }
    sb.append(document.chunk(line, document.lineCount()));
    return sb.toString();
  }

  private static String chunkOf(List<String> lines, int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }

  private void externalEdit(int startLine, int endLine, int newLineCount) {
    int shift = newLineCount - (endLine - startLine);
    MergeBlock target = startLine == endLine && newLineCount > 0 ? emptyConflictAt(startLine) : null;
    boolean afterTarget = false;
    for (MergeBlock b : blocks) {
      if (b == target) {
        // typing into an empty conflict block is editing that block
        b.setResult(startLine, startLine + newLineCount);
        b.markEdited();
        afterTarget = true;
        continue;
      }
      if (afterTarget && b.resultStart() == startLine && b.resultEnd() == startLine) {
        b.setResult(startLine + shift, startLine + shift); // other empty blocks there stay after it
        continue;
      }
      // insertion exactly at a non-empty block's start or end belongs to the neighbour, not to the block
      boolean overlaps = b.resultStart() < endLine && b.resultEnd() > startLine;
      RangeUpdater.Updated u = RangeUpdater.update(b.resultStart(), b.resultEnd(), startLine, endLine, shift);
      b.setResult(u.start(), u.end());
      if ((u.damaged() || overlaps) && b.state() != BlockState.EDITED) {
        b.markEdited();
      }
    }
  }

  private MergeBlock emptyConflictAt(int line) {
    for (MergeBlock b : blocks) {
      if (b.isConflict() && b.resultStart() == line && b.resultEnd() == line) {
        return b;
      }
    }
    return null;
  }

  /**
   * True while the model itself is calling {@link ResultDocument#replaceLines}; the document owner
   * must not report the resulting change via {@link #onExternalEdit}.
   */
  public boolean isApplyingOwnEdit() {
    return applyingOwnEdit;
  }

  /**
   * Replaces the backing document (for example when the UI attaches its own document adapter). The
   * new document must contain exactly the same text in the {@link ResultDocument} line model, else
   * {@link IllegalArgumentException} is thrown. Block positions are kept.
   */
  public void rebindDocument(ResultDocument newDocument) {
    String current = document.chunk(0, document.lineCount());
    String other = newDocument.chunk(0, newDocument.lineCount());
    if (!current.equals(other)) {
      throw new IllegalArgumentException("Document text differs from model text");
    }
    this.document = newDocument;
  }

  public int unresolvedConflicts() {
    int n = 0;
    for (MergeBlock b : blocks) {
      if (b.isConflict() && b.state() == BlockState.UNRESOLVED) {
        n++;
      }
    }
    return n;
  }

  public int needsReviewCount() {
    int n = 0;
    for (MergeBlock b : blocks) {
      if (b.needsReview()) {
        n++;
      }
    }
    return n;
  }

  public ModelSnapshot snapshot() {
    List<ModelSnapshot.Entry> entries = new ArrayList<>(blocks.size());
    for (MergeBlock b : blocks) {
      entries.add(new ModelSnapshot.Entry(b.resultStart(), b.resultEnd(), b.state(), b.strategy(),
          b.resolvedBy(), b.needsReview(), b.rationale()));
    }
    return new ModelSnapshot(List.copyOf(entries));
  }

  /**
   * Restores block positions and states (resolution, strategy, review flag) from a snapshot taken by
   * {@link #snapshot()} of this same model. Document text is not touched: the caller (typically the
   * UI undo manager) restores the document text first, so that the restored positions match it.
   *
   * @throws IllegalArgumentException if the snapshot does not have one entry per block of this model
   */
  public void restore(ModelSnapshot snapshot) {
    if (snapshot.entries().size() != blocks.size()) {
      throw new IllegalArgumentException("Snapshot has " + snapshot.entries().size()
          + " blocks, model has " + blocks.size() + "; it must come from the same model");
    }
    for (int i = 0; i < blocks.size(); i++) {
      ModelSnapshot.Entry e = snapshot.entries().get(i);
      MergeBlock b = blocks.get(i);
      b.setResult(e.resultStart(), e.resultEnd());
      b.setResolution(e.state(), e.strategy(), e.resolvedBy(), e.needsReview(), e.rationale());
    }
    fire();
  }

  public void addChangeListener(Runnable listener) {
    listeners.add(listener);
  }

  public void removeChangeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void replace(int block, String chunk) {
    MergeBlock b = blocks.get(block);
    int start = b.resultStart();
    int newCount = LineText.chunkLines(chunk).size();
    int shift = newCount - (b.resultEnd() - start);
    boolean outer = applyingOwnEdit;
    applyingOwnEdit = true;
    try {
      document.replaceLines(start, b.resultEnd(), chunk);
    } finally {
      applyingOwnEdit = outer;
    }
    b.setResult(start, start + newCount);
    for (int k = block + 1; k < blocks.size(); k++) {
      MergeBlock o = blocks.get(k);
      o.setResult(o.resultStart() + shift, o.resultEnd() + shift);
    }
  }

  private void fire() {
    for (Runnable l : listeners) {
      l.run();
    }
  }
}
