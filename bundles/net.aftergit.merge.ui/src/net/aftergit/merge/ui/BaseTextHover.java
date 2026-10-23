package net.aftergit.merge.ui;

import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.osgi.util.NLS;

/**
 * Shows the base text of an unresolved conflict (spec 6.2: base is not a permanent pane) and a
 * warning over a block auto-resolved by ignoring whitespace or one that does not compile; over an agent's block,
 * who proposed it and why, or that a proposal waits (agents design §3).
 */
final class BaseTextHover implements ITextHover {
  private final MergeViewer viewer;
  private final PaneKind pane;

  BaseTextHover(MergeViewer viewer, PaneKind pane) {
    this.viewer = viewer;
    this.pane = pane;
  }

  @Override
  public IRegion getHoverRegion(ITextViewer textViewer, int offset) {
    try {
      return textViewer.getDocument().getLineInformationOfOffset(offset);
    } catch (BadLocationException e) {
      return new Region(offset, 0);
    }
  }

  @Override
  public String getHoverInfo(ITextViewer textViewer, IRegion region) {
    if (!(viewer.getInput() instanceof MergeSession s)) {
      return null;
    }
    try {
      int line = textViewer.getDocument().getLineOfOffset(region.getOffset());
      int index = BlockLocator.blockAt(s.model(), pane, line);
      if (index < 0) {
        return null;
      }
      MergeBlock b = s.model().blocks().get(index);
      if (pane == PaneKind.RESULT && viewer.notCompiling(index)) {
        java.util.List<String> hidden = viewer.notUnderlinedErrors(index);
        return hidden.isEmpty() ? Messages.notCompilingHover
            : Messages.notCompilingHover + "\n" + NLS.bind(Messages.notCompilingNotUnderlinedHover,
                capped(String.join("\n", hidden), MAX_RATIONALE));
      }
      if (pane == PaneKind.RESULT && BlockStyles.whitespaceIgnored(b)) {
        return Messages.whitespaceIgnoredHover;
      }
      if (pane == PaneKind.RESULT && BlockStyles.newerVersion(b)) {
        return Messages.newerVersionHover;
      }
      Proposal waiting = pane == PaneKind.RESULT ? viewer.pendingProposals().get(index) : null;
      if (waiting != null) {
        return NLS.bind(viewer.agentsHeld() ? Messages.aiHeldHover : Messages.aiPendingHover, agentName(waiting));
      }
      if (pane == PaneKind.RESULT && b.state() == BlockState.AI_RESOLVED) {
        String by = agentName(b.resolvedBy());
        return b.rationale() == null || b.rationale().isEmpty() ? NLS.bind(Messages.aiHoverNoRationale, by)
            : NLS.bind(Messages.aiHover, by, capped(b.rationale(), MAX_RATIONALE));
      }
      if (!b.isConflict() || b.state() != BlockState.UNRESOLVED) {
        return null;
      }
      return Messages.baseHoverTitle + "\n" + (b.baseText().isEmpty() ? Messages.baseEmpty : b.baseText());
    } catch (BadLocationException e) {
      return null;
    }
  }

  /** The longest rationale the hover shows; an agent's text has no other bound below the request size. */
  static final int MAX_RATIONALE = 1_000;

  /** {@code text} cut to at most {@code max} characters with an ellipsis, never inside a surrogate pair. */
  static String capped(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
    return text.substring(0, end) + "\u2026";
  }

  /** Who resolved a block: the agent of "ai:<agent>", AfterGit for its own passes. */
  static String agentName(String resolvedBy) {
    return resolvedBy != null && resolvedBy.startsWith("ai:") ? resolvedBy.substring(3) : "AfterGit";
  }

  static String agentName(Proposal p) {
    return p.auto() ? "AfterGit" : p.source();
  }
}
