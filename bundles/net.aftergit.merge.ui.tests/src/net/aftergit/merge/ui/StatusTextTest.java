package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusTextTest {
  @Test
  void pluralsAndReview() {
    assertEquals("1 conflict", StatusText.format(1, 0));
    assertEquals("2 conflicts", StatusText.format(2, 0));
    assertEquals("2 conflicts · 1 auto-resolved (to review)", StatusText.format(2, 1));
    assertEquals("✓ All conflicts resolved", StatusText.format(0, 0));
    assertEquals("✓ All conflicts resolved · 3 auto-resolved (to review)", StatusText.format(0, 3));
  }

  @Test
  void errorsAreCounted() {
    assertEquals("2 conflicts \u00B7 1 error", StatusText.format(2, 0, 1));
    assertEquals("\u2713 All conflicts resolved \u00B7 1 auto-resolved (to review) \u00B7 2 errors", StatusText.format(0, 1, 2));
    assertEquals("1 conflict", StatusText.format(1, 0, 0));
  }

  @Test
  void blocksThatDoNotCompileAreCountedAndTakeTheCheckMark() {
    assertEquals("All conflicts resolved · 1 auto-resolved block does not compile · 1 error",
        StatusText.format(0, 0, 1, 1));
    assertEquals("2 conflicts · 1 auto-resolved (to review) · 2 auto-resolved blocks do not compile",
        StatusText.format(2, 1, 0, 2));
    assertEquals("✓ All conflicts resolved", StatusText.format(0, 0, 0, 0));
  }

  @Test
  void aiProposalsToReviewAreCountedApartFromAutoResolvedOnes() {
    assertEquals("2 conflicts \u00B7 1 auto-resolved (to review) \u00B7 2 AI-proposed (to review)",
        StatusText.format(2, 1, 2, 0, 0));
    assertEquals("\u2713 All conflicts resolved \u00B7 1 AI-proposed (to review)", StatusText.format(0, 0, 1, 0, 0));
    assertEquals("1 conflict \u00B7 1 AI-proposed (to review) \u00B7 1 error", StatusText.format(1, 0, 1, 1, 0));
    assertEquals("1 conflict", StatusText.format(1, 0, 0, 0, 0));
  }

  @Test
  void thePositionNamesTheConflictAndItsState() {
    var m = BlockLocatorTest.model(); // two conflicts
    assertEquals("Conflict 1 of 2 · unresolved", StatusText.position(m, 0));
    m.take(0, net.aftergit.merge.core.merge.ThreeSide.LEFT);
    assertEquals("Conflict 1 of 2 · Take Left ◀", StatusText.position(m, 0));
    m.take(0, net.aftergit.merge.core.merge.ThreeSide.RIGHT);
    assertEquals("Conflict 1 of 2 · Take Both ◆", StatusText.position(m, 0));
    m.applyResolution(1, "X\n", net.aftergit.merge.core.model.BlockState.AUTO_RESOLVED,
        net.aftergit.merge.core.model.Strategy.TEXT, "imports", null);
    assertEquals("Conflict 2 of 2 · auto-resolved (imports) · needs review", StatusText.position(m, 1));
    m.markReviewed(1);
    assertEquals("Conflict 2 of 2 · auto-resolved (imports)", StatusText.position(m, 1));
    m.applyResolution(1, "Y\n", net.aftergit.merge.core.model.BlockState.AI_RESOLVED,
        net.aftergit.merge.core.model.Strategy.TEXT, "agent", "why");
    assertEquals("Conflict 2 of 2 · AI-resolved · needs review", StatusText.position(m, 1));
    assertNull(StatusText.position(m, -1), "no current block");
  }

  @Test
  void aChangeThatWasNeverAConflictHasNoPosition() {
    var m = BlockStylesTest.model();
    assertNull(StatusText.position(m, 1));
    assertEquals("Conflict 1 of 1 · unresolved", StatusText.position(m, 0));
  }

  @Test
  void editedAndIgnoredConflictsAreNamed() {
    var m = BlockLocatorTest.model();
    m.ignore(0);
    assertEquals("Conflict 1 of 2 · ignored", StatusText.position(m, 0));
    m.take(1, net.aftergit.merge.core.merge.ThreeSide.RIGHT);
    assertEquals("Conflict 2 of 2 · Take Right ▶", StatusText.position(m, 1));
    m.document().replaceLines(3, 4, "typed\n");
    m.onExternalEdit(3, 4, 1);
    assertEquals("Conflict 2 of 2 · edited ✎", StatusText.position(m, 1));
  }

  @Test
  void anErrorThatFramesABlockWithoutAnUnderlineIsNamed() {
    String named = StatusText.format(0, 0, 0, 0, 1, "Ambient only");
    assertTrue(named.endsWith(java.text.MessageFormat.format(Messages.statusNotCompiling, 1) + " ("
        + org.eclipse.osgi.util.NLS.bind(Messages.statusNotUnderlined, "Ambient only") + ")"), named);
    String longer = StatusText.format(0, 0, 0, 0, 1, "x".repeat(200) + "\nsecond line");
    assertTrue(longer.contains("x".repeat(StatusText.MAX_HIDDEN) + "\u2026)"), longer);
    String emoji = "x".repeat(StatusText.MAX_HIDDEN - 1) + "\uD83D\uDE00 tail";
    String cut = StatusText.format(0, 0, 0, 0, 1, emoji);
    assertTrue(cut.contains("x".repeat(StatusText.MAX_HIDDEN - 1) + "\u2026)"), "no half surrogate pair: " + cut);
    assertEquals(StatusText.format(0, 0, 0, 0, 1), StatusText.format(0, 0, 0, 0, 1, null));
  }
}
