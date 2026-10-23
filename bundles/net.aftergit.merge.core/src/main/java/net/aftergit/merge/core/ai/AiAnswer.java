package net.aftergit.merge.core.ai;

import java.util.Objects;

/** A model's resolution of one block (whole lines) and its rationale, which may be empty (design §2). */
public record AiAnswer(String resolution, String rationale) {
  /** The longest resolution AfterGit takes (design §5), as for a proposal of an agent. */
  public static final int MAX_CHARS = 200_000;

  public AiAnswer {
    Objects.requireNonNull(resolution, "resolution");
    resolution = lines(resolution);
    rationale = rationale == null ? "" : rationale.strip();
  }

  /** {@code text} as whole '\n'-terminated lines: CRLF and lone CR become '\n', a missing final '\n' is added. */
  public static String lines(String text) {
    String t = text.replace("\r\n", "\n").replace('\r', '\n');
    return t.isEmpty() || t.endsWith("\n") ? t : t + "\n";
  }
}
