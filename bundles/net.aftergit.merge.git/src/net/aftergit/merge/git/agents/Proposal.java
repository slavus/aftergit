package net.aftergit.merge.git.agents;

import java.time.Instant;
import java.util.Objects;
import net.aftergit.merge.core.model.BlockState;

/**
 * A resolution proposed for one conflict block (agents design §2). {@code source}: the agent's name from MCP
 * initialize, or {@link #AUTO} for AfterGit's own automatic passes. {@code text} is a chunk of whole lines.
 */
public record Proposal(BlockId block, String text, String rationale, String source, Instant time) {
  public static final String AUTO = "auto";

  public Proposal {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(time, "time");
    text = chunk(text);
    rationale = rationale == null ? "" : rationale;
  }

  public boolean auto() {
    return AUTO.equals(source);
  }

  /** The state the block gets when the proposal is applied: always one that needs review (design §11). */
  public BlockState state() {
    return auto() ? BlockState.AUTO_RESOLVED : BlockState.AI_RESOLVED;
  }

  /** {@code resolvedBy} of the applied block: "auto", or "ai:" and the agent's name (design §3.3). */
  public String resolvedBy() {
    return auto() ? AUTO : "ai:" + source;
  }

  /** {@code text} as whole '\n'-terminated lines: CRLF becomes '\n', a missing final '\n' is added. */
  public static String chunk(String text) {
    String t = text.replace("\r\n", "\n");
    return t.isEmpty() || t.endsWith("\n") ? t : t + "\n";
  }
}
