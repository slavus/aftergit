package net.aftergit.merge.ui;

import java.text.MessageFormat;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.NodeState;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.core.structure.Who;

/** The texts of a row of the structure tree (structure-compare design §2): name, who, chip, tooltip. */
final class StructureLabels {
  enum ChipStyle { CONFLICT, AUTO_FILLED, AUTO_OUTLINED, AI_FILLED, AI_OUTLINED, TAKEN, EDITED, GREY, NONE }

  record Chip(String text, ChipStyle style) {}

  private StructureLabels() {}

  static String label(StructureNode n) {
    return n.kind() == NodeKind.IMPORTS ? Messages.structureImports
        : n.kind() == NodeKind.PROPERTY && n.label().isEmpty() ? Messages.structureEmptyKey : n.label();
  }

  static String who(Who w) {
    return switch (w) {
      case NONE -> "";
      case OURS -> Messages.structureWhoOurs;
      case THEIRS -> Messages.structureWhoTheirs;
      case BOTH -> Messages.structureWhoBoth;
      case REMOVED_BY_OURS -> Messages.structureWhoRemovedByOurs;
      case REMOVED_BY_THEIRS -> Messages.structureWhoRemovedByTheirs;
      case REMOVED_BY_BOTH -> Messages.structureWhoRemovedByBoth;
    };
  }

  /**
   * {@code origin}: where the worst block's result came from; an automatic resolution shows it after "A". A container
   * (a type, a header with clauses) shows no "git" chip.
   */
  static Chip chip(StructureNode n, PaneStyle.Origin origin) {
    if (n.state() == NodeState.GIT && (n.kind().isType() || !n.children().isEmpty())) {
      return new Chip("", ChipStyle.NONE); // a container merged by git stays quiet; its members show their own chips
    }
    String count = n.stateCount() > 1 ? MessageFormat.format(Messages.structureCount, n.stateCount()) : "";
    return switch (n.state()) {
      case CONFLICT -> new Chip(Messages.structureChipConflict + count, ChipStyle.CONFLICT);
      case AUTO_TO_REVIEW -> new Chip(Messages.structureChipAuto + glyph(origin) + count, ChipStyle.AUTO_FILLED);
      case AUTO_REVIEWED -> new Chip(Messages.structureChipAuto + glyph(origin) + count, ChipStyle.AUTO_OUTLINED);
      case AI_TO_REVIEW -> new Chip(Messages.structureChipAi + count, ChipStyle.AI_FILLED);
      case AI_REVIEWED -> new Chip(Messages.structureChipAi + count, ChipStyle.AI_OUTLINED);
      case TAKEN_LEFT -> new Chip("◀" + count, ChipStyle.TAKEN);
      case TAKEN_RIGHT -> new Chip("▶" + count, ChipStyle.TAKEN);
      case TAKEN_BOTH -> new Chip("◆" + count, ChipStyle.TAKEN);
      case EDITED -> new Chip("✎" + count, ChipStyle.EDITED);
      case IGNORED -> new Chip(Messages.structureChipIgnored + count, ChipStyle.GREY);
      case GIT -> new Chip(Messages.structureChipGit + count, ChipStyle.GREY);
      case NONE -> new Chip("", ChipStyle.NONE);
    };
  }

  private static String glyph(PaneStyle.Origin o) {
    return switch (o) {
      case LEFT -> " ◀";
      case RIGHT -> " ▶";
      case BOTH -> " ◆";
      default -> "";
    };
  }

  /** The chip's state in words, as the status line names it; "" for none. */
  static String state(NodeState s) {
    return switch (s) {
      case CONFLICT -> Messages.structureChipConflict;
      case AI_TO_REVIEW -> MessageFormat.format(Messages.statusStateNeedsReview, Messages.statusStateAi);
      case AUTO_TO_REVIEW -> MessageFormat.format(Messages.statusStateNeedsReview, Messages.statusStateAuto);
      case AI_REVIEWED -> Messages.statusStateAi;
      case AUTO_REVIEWED -> Messages.statusStateAuto;
      case TAKEN_LEFT -> Messages.statusStateTakenLeft;
      case TAKEN_RIGHT -> Messages.statusStateTakenRight;
      case TAKEN_BOTH -> Messages.statusStateTakenBoth;
      case EDITED -> Messages.statusStateEdited;
      case IGNORED -> Messages.statusStateIgnored;
      case GIT -> Messages.statusStateGit;
      case NONE -> "";
    };
  }

  /**
   * The row's text for assistive technology and type-ahead: the name ("(removed)" after it when not in the result),
   * "who" unless {@code compact}, the state in words when the row shows a chip.
   */
  static String text(StructureNode n, boolean compact, Chip chip) {
    StringBuilder s = new StringBuilder(
        n.inResult() ? label(n) : MessageFormat.format(Messages.structureRemoved, label(n)));
    String who = compact ? "" : who(n.who());
    if (!who.isEmpty()) {
      s.append(Messages.structureRowSeparator).append(who);
    }
    if (!chip.text().isEmpty()) {
      String count = n.stateCount() > 1 ? MessageFormat.format(Messages.structureCount, n.stateCount()) : "";
      s.append(Messages.structureRowSeparator).append(state(n.state())).append(count);
    }
    return s.toString();
  }

  /** Who changed the node, then the state of each of its blocks as the status line names it (design §2). */
  static String tooltip(StructureNode n, MergeModel m) {
    StringBuilder s = new StringBuilder(switch (n.who()) {
      case NONE -> Messages.structureTipUnchanged;
      case OURS -> Messages.structureTipOurs;
      case THEIRS -> Messages.structureTipTheirs;
      case BOTH -> Messages.structureTipBoth;
      case REMOVED_BY_OURS -> Messages.structureTipRemovedByOurs;
      case REMOVED_BY_THEIRS -> Messages.structureTipRemovedByTheirs;
      case REMOVED_BY_BOTH -> Messages.structureTipRemovedByBoth;
    });
    for (int b : n.blocks()) {
      if (b < m.blocks().size()) {
        s.append('\n').append(MessageFormat.format(Messages.structureTipBlock, b + 1, StatusText.state(m.blocks().get(b))));
      }
    }
    return s.toString();
  }
}
