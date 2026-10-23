package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.ResolverPipeline;

/** Maps the merge model to per-pane line decorations (ui-polish design §2, block-states design §1, §4). */
public final class BlockStyles {
  private BlockStyles() {}

  public static PaneStyle compute(MergeModel model, PaneKind pane, int lineCount) {
    return compute(model, pane, lineCount, i -> false);
  }

  public static PaneStyle compute(MergeModel model, PaneKind pane, int lineCount, IntPredicate hasInner) {
    return compute(model, pane, lineCount, hasInner, i -> false);
  }

  /** {@code notCompiling}: blocks framed as "auto-resolved, but does not compile" (result pane only). */
  public static PaneStyle compute(MergeModel model, PaneKind pane, int lineCount, IntPredicate hasInner,
      IntPredicate notCompiling) {
    return compute(model, pane, lineCount, hasInner, notCompiling, origins(model));
  }

  /** {@code origins}: {@link #origins} of the model, computed once per refresh. */
  public static PaneStyle compute(MergeModel model, PaneKind pane, int lineCount, IntPredicate hasInner,
      IntPredicate notCompiling, PaneStyle.Origin[] origins) {
    LineStyle[] lines = new LineStyle[lineCount];
    Arrays.fill(lines, LineStyle.NONE);
    List<PaneStyle.Marker> markers = new ArrayList<>();
    List<PaneStyle.Frame> frames = new ArrayList<>();
    List<PaneStyle.Gutter> gutters = new ArrayList<>();
    for (MergeBlock b : model.blocks()) {
      if (!painted(b, pane)) {
        continue;
      }
      PaneStyle.Origin origin = b.index() < origins.length ? origins[b.index()] : origin(model, b);
      LineStyle.Kind kind = kind(b, pane, origin);
      LineStyle.Fill fill = fill(b, pane, hasInner);
      int start = start(b, pane);
      int end = end(b, pane);
      boolean failing = pane == PaneKind.RESULT && notCompiling.test(b.index());
      boolean review = pane == PaneKind.RESULT && pendingAuto(b) && !failing; // the error frame wins (§4)
      boolean ai = b.state() == BlockState.AI_RESOLVED;
      boolean dropped = kind == LineStyle.Kind.DROPPED;
      boolean border = review || ai || dropped;
      LineStyle.Border style = dropped ? LineStyle.Border.DASH
          : review || (ai && pane != PaneKind.RESULT) ? LineStyle.Border.DOT : LineStyle.Border.SOLID;
      boolean hatched = pane == PaneKind.RESULT && pendingAuto(b);
      if (pane == PaneKind.RESULT) {
        PaneStyle.Gutter g = gutter(b, origin);
        if (g != null) {
          gutters.add(g);
        }
        if (start < end && failing) {
          frames.add(new PaneStyle.Frame(start, Math.min(end, lineCount)));
        }
      }
      if (start == end) {
        markers.add(new PaneStyle.Marker(start, kind));
        continue;
      }
      for (int i = start; i < end && i < lineCount; i++) {
        lines[i] = new LineStyle(kind, fill, border && i == start, border && i == end - 1, style, hatched);
      }
    }
    return new PaneStyle(lines, List.copyOf(markers), List.copyOf(frames), List.copyOf(gutters));
  }

  /** Auto-resolved by ignoring whitespace (one side's whitespace edit dropped) and not reviewed yet. */
  static boolean whitespaceIgnored(MergeBlock b) {
    return b.needsReview()
        && b.state() == BlockState.AUTO_RESOLVED
        && ResolverPipeline.RATIONALE_WHITESPACE_IGNORED.equals(b.rationale());
  }

  /** Auto-resolved by "Newer version wins" (both sides' versions differ, the higher one kept) and not reviewed yet. */
  static boolean newerVersion(MergeBlock b) {
    return b.needsReview()
        && b.state() == BlockState.AUTO_RESOLVED
        && NewerVersionResolver.RATIONALE.equals(b.rationale());
  }

  static boolean visible(MergeBlock b, PaneKind pane) {
    ConflictType t = b.type();
    return switch (pane) {
      case LEFT -> t.leftChanged();
      case RIGHT -> t.rightChanged();
      case RESULT -> b.state() != BlockState.RESOLVED_BY_GIT;
    };
  }

  /**
   * The block is painted in {@code pane}: in a side pane where that side changed; in the result every block but
   * an ignored one, git's own resolutions included. Navigation keeps using {@link #visible}.
   */
  static boolean painted(MergeBlock b, PaneKind pane) {
    return pane == PaneKind.RESULT ? b.state() != BlockState.IGNORED : visible(b, pane);
  }

  /** An automatic resolution of the rules (not of an agent) that waits for the user's review. */
  static boolean pendingAuto(MergeBlock b) {
    return b.state() == BlockState.AUTO_RESOLVED && b.needsReview();
  }

  /**
   * Where the result of a block that was a conflict came from (block-states design §1, §4): taken sides by state;
   * an automatic resolution by its text (equal to ours ◀, to theirs ▶, otherwise ◆); an AI one only when its text
   * equals a side. NONE for open conflicts, ignored blocks and changes that were never conflicts.
   */
  static PaneStyle.Origin origin(MergeModel m, MergeBlock b) {
    if (!b.isConflict()) {
      return PaneStyle.Origin.NONE;
    }
    return switch (b.state()) {
      case TAKEN_LEFT -> PaneStyle.Origin.LEFT;
      case TAKEN_RIGHT -> PaneStyle.Origin.RIGHT;
      case TAKEN_BOTH -> PaneStyle.Origin.BOTH;
      case EDITED -> PaneStyle.Origin.EDITED;
      case AUTO_RESOLVED -> byText(m, b, PaneStyle.Origin.BOTH);
      case AI_RESOLVED -> byText(m, b, PaneStyle.Origin.NONE);
      default -> PaneStyle.Origin.NONE;
    };
  }

  private static PaneStyle.Origin byText(MergeModel m, MergeBlock b, PaneStyle.Origin otherwise) {
    String result = m.resultText(b.index());
    if (result.equals(m.sideText(b.index(), ThreeSide.LEFT))) {
      return PaneStyle.Origin.LEFT;
    }
    if (result.equals(m.sideText(b.index(), ThreeSide.RIGHT))) {
      return PaneStyle.Origin.RIGHT;
    }
    return otherwise;
  }

  /** {@link #origin} of every block, by block index. */
  static PaneStyle.Origin[] origins(MergeModel m) {
    PaneStyle.Origin[] out = new PaneStyle.Origin[m.blocks().size()];
    for (MergeBlock b : m.blocks()) {
      out[b.index()] = origin(m, b);
    }
    return out;
  }

  /** The side pane {@code pane} gave nothing to a result with {@code origin} (block-states design §1: dropped). */
  static boolean dropped(PaneStyle.Origin origin, PaneKind pane) {
    return switch (origin) {
      case LEFT -> pane == PaneKind.RIGHT;
      case RIGHT -> pane == PaneKind.LEFT;
      case EDITED -> pane != PaneKind.RESULT;
      case BOTH, NONE -> false;
    };
  }

  static LineStyle.Kind kind(MergeBlock b, PaneKind pane, PaneStyle.Origin origin) {
    BlockState s = b.state();
    if (b.isConflict() && s == BlockState.UNRESOLVED) {
      return LineStyle.Kind.CONFLICT;
    }
    if (s == BlockState.AI_RESOLVED) { // keeps its phase-1 look; the origin is only a glyph in the ruler
      return pane == PaneKind.RESULT ? LineStyle.Kind.AI : changeKind(b, side(pane));
    }
    if (pane != PaneKind.RESULT && dropped(origin, pane)) {
      return LineStyle.Kind.DROPPED;
    }
    if (s == BlockState.AUTO_RESOLVED) {
      return LineStyle.Kind.AUTO;
    }
    if (b.isConflict() && (s == BlockState.TAKEN_LEFT || s == BlockState.TAKEN_RIGHT
        || s == BlockState.TAKEN_BOTH || s == BlockState.EDITED)) {
      return LineStyle.Kind.MODIFIED; // the taken colour (§1)
    }
    if (pane != PaneKind.RESULT) {
      return changeKind(b, side(pane));
    }
    return switch (s) {
      case TAKEN_LEFT -> changeKind(b, ThreeSide.LEFT);
      case TAKEN_RIGHT -> changeKind(b, ThreeSide.RIGHT);
      case TAKEN_BOTH, EDITED -> LineStyle.Kind.MODIFIED;
      default -> b.type().leftChanged() == b.type().rightChanged() ? LineStyle.Kind.MODIFIED
          : changeKind(b, b.type().leftChanged() ? ThreeSide.LEFT : ThreeSide.RIGHT);
    };
  }

  private static ThreeSide side(PaneKind pane) {
    return pane == PaneKind.LEFT ? ThreeSide.LEFT : ThreeSide.RIGHT;
  }

  /** The ruler marks of a block that was a conflict, or null when it shows none (open, ignored, AI with new text). */
  static PaneStyle.Gutter gutter(MergeBlock b, PaneStyle.Origin origin) {
    if (!b.isConflict()) {
      return null;
    }
    PaneStyle.Bar bar = switch (b.state()) {
      case TAKEN_LEFT, TAKEN_RIGHT, TAKEN_BOTH -> PaneStyle.Bar.TAKEN;
      case EDITED -> PaneStyle.Bar.EDITED;
      case AUTO_RESOLVED -> PaneStyle.Bar.AUTO;
      default -> PaneStyle.Bar.NONE;
    };
    PaneStyle.Badge badge = b.state() != BlockState.AUTO_RESOLVED ? PaneStyle.Badge.NONE
        : b.needsReview() ? PaneStyle.Badge.PENDING : PaneStyle.Badge.REVIEWED;
    if (bar == PaneStyle.Bar.NONE && origin == PaneStyle.Origin.NONE && badge == PaneStyle.Badge.NONE) {
      return null;
    }
    return new PaneStyle.Gutter(b.index(), b.resultStart(), b.resultEnd(), bar, origin, badge);
  }

  /** What {@code side} did to the block: removed its lines, added lines to an empty base, or modified them. */
  static LineStyle.Kind changeKind(MergeBlock b, ThreeSide side) {
    if (b.source().isEmpty(side)) {
      return LineStyle.Kind.REMOVED;
    }
    return b.source().isEmpty(ThreeSide.BASE) ? LineStyle.Kind.ADDED : LineStyle.Kind.MODIFIED;
  }

  private static LineStyle.Fill fill(MergeBlock b, PaneKind pane, IntPredicate hasInner) {
    BlockState s = b.state();
    if (b.isConflict() && s == BlockState.UNRESOLVED) {
      // without word ranges (whitespace only, inner-line off) the strong fill keeps the conflict visible
      return hasInner.test(b.index()) ? LineStyle.Fill.LIGHT : LineStyle.Fill.STRONG;
    }
    if (s == BlockState.AI_RESOLVED && pane != PaneKind.RESULT) {
      return LineStyle.Fill.NONE;
    }
    return LineStyle.Fill.LIGHT;
  }

  static int start(MergeBlock b, PaneKind pane) {
    return switch (pane) {
      case LEFT -> b.source().start(ThreeSide.LEFT);
      case RIGHT -> b.source().start(ThreeSide.RIGHT);
      case RESULT -> b.resultStart();
    };
  }

  static int end(MergeBlock b, PaneKind pane) {
    return switch (pane) {
      case LEFT -> b.source().end(ThreeSide.LEFT);
      case RIGHT -> b.source().end(ThreeSide.RIGHT);
      case RESULT -> b.resultEnd();
    };
  }
}
