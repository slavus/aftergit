package net.aftergit.merge.git.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import net.aftergit.merge.core.model.BlockState;
import org.junit.jupiter.api.Test;

/** Agents design §4 and design §11: agent proposals are AI-resolved, AfterGit's own passes auto-resolved. */
class ProposalTest {
  static final BlockId ID = new BlockId(0, "0123456789ab");
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");

  @Test
  void anAgentsProposalIsAiResolvedByTheAgent() {
    Proposal p = new Proposal(ID, "x\n", "keeps both", "test-agent", T);
    assertFalse(p.auto());
    assertEquals(BlockState.AI_RESOLVED, p.state());
    assertEquals("ai:test-agent", p.resolvedBy());
  }

  @Test
  void anAutoProposalIsAutoResolved() {
    Proposal p = new Proposal(ID, "x\n", "simple", Proposal.AUTO, T);
    assertTrue(p.auto());
    assertEquals(BlockState.AUTO_RESOLVED, p.state());
    assertEquals("auto", p.resolvedBy());
  }

  @Test
  void theTextBecomesAChunkOfWholeLines() {
    assertEquals("a\nb\n", new Proposal(ID, "a\r\nb", "", "test-agent", T).text());
    assertEquals("", new Proposal(ID, "", "", "test-agent", T).text());
    assertEquals("a\n", Proposal.chunk("a\n"));
  }

  @Test
  void aMissingRationaleIsEmpty() {
    assertEquals("", new Proposal(ID, "x\n", null, "test-agent", T).rationale());
  }

  @Test
  void requiredPartsMustBeThere() {
    assertThrows(NullPointerException.class, () -> new Proposal(null, "x", "", "a", T));
    assertThrows(NullPointerException.class, () -> new Proposal(ID, null, "", "a", T));
    assertThrows(NullPointerException.class, () -> new Proposal(ID, "x", "", null, T));
    assertThrows(NullPointerException.class, () -> new Proposal(ID, "x", "", "a", null));
  }
}
