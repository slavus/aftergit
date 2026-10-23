package net.aftergit.merge.core.ai;

import java.util.function.BooleanSupplier;

/**
 * Something that resolves one conflict block with a language model (resolve-with-ai design §2). AfterGit asks
 * {@link #ready()} whenever it may show its entries, so it must be fast, never block and never do I/O.
 * {@link #resolve} runs on a background thread, one request at a time per editor; it polls {@code cancelled} and
 * gives up soon after it turns true.
 */
public interface AiResolver {
  int DEFAULT_CONTEXT_LINES = 40;

  /** Shown in menus and as the source of its proposals, e.g. "Anthropic claude-sonnet-5-5". */
  String name();

  /** Switched on and configured: only then does AfterGit show it or call {@link #resolve}. */
  boolean ready();

  /** True when a request leaves this machine; AfterGit then asks the user before the first one. */
  boolean remote();

  /**
   * Where a request goes, such as "https://api.anthropic.com:443"; empty when the resolver does not say. The user's
   * "do not ask again" for a remote resolver holds for this destination only, so a new one is asked about again.
   */
  default String destination() {
    return "";
  }

  /** Lines of the result before and after the block that go with a request. */
  default int contextLines() {
    return DEFAULT_CONTEXT_LINES;
  }

  /** The resolution of {@code request}; an {@link AiException} with a message for the user when there is none. */
  AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) throws AiException;
}
