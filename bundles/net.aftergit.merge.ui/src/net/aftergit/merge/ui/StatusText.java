package net.aftergit.merge.ui;

import java.text.MessageFormat;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/** Status line text (spec §6.7). */
final class StatusText {
  private StatusText() {}

  static String format(int unresolved, int toReview) {
    return format(unresolved, toReview, 0);
  }

  private static final String SEP = " \u00B7 ";

  static String format(int unresolved, int toReview, int errors) {
    return format(unresolved, toReview, errors, 0);
  }

  static String format(int unresolved, int toReview, int errors, int notCompiling) {
    return format(unresolved, toReview, 0, errors, notCompiling);
  }

  /** {@code toReview}: auto-resolved blocks to review; {@code aiToReview}: AI proposals to review (agents design §3). */
  static String format(int unresolved, int toReview, int aiToReview, int errors, int notCompiling) {
    return format(unresolved, toReview, aiToReview, errors, notCompiling, null);
  }

  /** The longest error message the status line names; the hover has the whole of it. */
  static final int MAX_HIDDEN = 80;

  /**
   * As above; {@code hidden}: the message of an error that frames a block without being underlined in the result
   * (languages design, Part 2), named after the count of such blocks, or null.
   */
  static String format(int unresolved, int toReview, int aiToReview, int errors, int notCompiling, String hidden) {
    String head = unresolved > 0 ? MessageFormat.format(Messages.statusConflicts, unresolved)
        : notCompiling > 0 ? Messages.allResolvedNotCompiling : Messages.allResolved;
    StringBuilder s = new StringBuilder(head);
    if (toReview > 0) {
      s.append(SEP).append(MessageFormat.format(Messages.statusToReview, toReview));
    }
    if (aiToReview > 0) {
      s.append(SEP).append(MessageFormat.format(Messages.statusAiToReview, aiToReview));
    }
    if (notCompiling > 0) {
      s.append(SEP).append(MessageFormat.format(Messages.statusNotCompiling, notCompiling));
      if (hidden != null) {
        String h = hidden.replaceAll("\\s+", " ").strip();
        s.append(" (").append(org.eclipse.osgi.util.NLS.bind(Messages.statusNotUnderlined,
            BaseTextHover.capped(h, MAX_HIDDEN))).append(')'); // never half of a surrogate pair
      }
    }
    if (errors > 0) {
      s.append(SEP).append(MessageFormat.format(Messages.statusErrors, errors));
    }
    return s.toString();
  }

  /**
   * Where the user is (block-states design §3): "Conflict 3 of 7 · Take Left ◀" for a current block that was a
   * conflict, null otherwise.
   */
  static String position(MergeModel m, int block) {
    if (block < 0 || block >= m.blocks().size() || !m.blocks().get(block).isConflict()) {
      return null;
    }
    int n = 0;
    int total = 0;
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict()) {
        total++;
        if (b.index() <= block) {
          n++;
        }
      }
    }
    return MessageFormat.format(Messages.statusPosition, n, total, state(m.blocks().get(block)));
  }

  /** The state of a block as the status line names it, with " · needs review" while it waits for a review. */
  static String state(MergeBlock b) {
    String s = switch (b.state()) {
      case UNRESOLVED -> Messages.statusStateUnresolved;
      case TAKEN_LEFT -> Messages.statusStateTakenLeft;
      case TAKEN_RIGHT -> Messages.statusStateTakenRight;
      case TAKEN_BOTH -> Messages.statusStateTakenBoth;
      case EDITED -> Messages.statusStateEdited;
      case IGNORED -> Messages.statusStateIgnored;
      case RESOLVED_BY_GIT -> Messages.statusStateGit;
      case AUTO_RESOLVED -> b.resolvedBy() == null || b.resolvedBy().isBlank() ? Messages.statusStateAuto
          : MessageFormat.format(Messages.statusStateAutoBy, b.resolvedBy());
      case AI_RESOLVED -> Messages.statusStateAi;
    };
    return b.needsReview() ? MessageFormat.format(Messages.statusStateNeedsReview, s) : s;
  }
}
